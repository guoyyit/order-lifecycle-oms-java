package com.github.guoyyit.orderlifecycleomsjava.tests;

import com.github.guoyyit.orderlifecycleomsjava.domain.ExecutionReport;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderSnapshot;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus;
import com.github.guoyyit.orderlifecycleomsjava.domain.RejectReason;
import com.github.guoyyit.orderlifecycleomsjava.service.OrderService;
import com.github.guoyyit.orderlifecycleomsjava.statemachine.TransitionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发测试：成交回报与撤销并发到达时最终状态确定；
 * 并发读取与状态更新同时进行时快照不出现中间态。
 */
@SpringBootTest
class ConcurrencyTest {

    @Autowired
    private OrderService orderService;

    private static ExecutionReport exec(String execId, String orderId, long qty) {
        return new ExecutionReport(execId, orderId, qty, 1000L, Instant.now());
    }

    @Test
    void concurrentExecutionsNeverLoseOrDoubleCount() throws Exception {
        String orderId = "C-concurrent-exec";
        int threads = 16;
        int perThread = 25; // 共 400 笔，每笔 1，订单量 400
        orderService.acceptOrder(orderId, (long) threads * perThread);
        orderService.markReported(orderId);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            futures.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < perThread; i++) {
                    orderService.onExecutionReport(exec("exec-" + (base + i), orderId, 1));
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        OrderSnapshot snapshot = orderService.getSnapshot(orderId).orElseThrow();
        assertEquals(threads * perThread, snapshot.getFilledQty());
        assertEquals(OrderStatus.FILLED, snapshot.getStatus());
        assertEquals(threads * perThread, snapshot.getFills().size());
    }

    @Test
    void concurrentCancelAndExecutionHasDeterministicFinalState() throws Exception {
        // 重复多轮：每轮最终状态必须确定且可解释
        for (int round = 0; round < 20; round++) {
            String orderId = "C-cancel-vs-exec-" + round;
            orderService.acceptOrder(orderId, 100);
            orderService.markReported(orderId);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<TransitionResult> cancelFuture = pool.submit(() -> {
                await(start);
                return orderService.cancel(orderId);
            });
            Future<TransitionResult> execFuture = pool.submit(() -> {
                await(start);
                return orderService.onExecutionReport(exec("e-full", orderId, 100));
            });
            start.countDown();
            TransitionResult cancelResult = cancelFuture.get(10, TimeUnit.SECONDS);
            TransitionResult execResult = execFuture.get(10, TimeUnit.SECONDS);
            pool.shutdown();

            // 恰好一个成功：成交先到则撤销被 ORDER_ALREADY_TERMINAL 拒绝；
            // 撤销先到则成交被 LATE_EXECUTION_AFTER_TERMINAL 拒绝。
            assertTrue(cancelResult.isAccepted() ^ execResult.isAccepted(),
                    "round " + round + ": exactly one of cancel/exec must win, cancel="
                            + cancelResult + ", exec=" + execResult);

            OrderSnapshot snapshot = orderService.getSnapshot(orderId).orElseThrow();
            if (execResult.isAccepted()) {
                assertEquals(OrderStatus.FILLED, snapshot.getStatus());
                assertEquals(100, snapshot.getFilledQty());
                assertEquals(RejectReason.ORDER_ALREADY_TERMINAL, cancelResult.getRejectReason());
            } else {
                assertEquals(OrderStatus.CANCELLED, snapshot.getStatus());
                assertEquals(0, snapshot.getFilledQty());
                assertEquals(RejectReason.LATE_EXECUTION_AFTER_TERMINAL, execResult.getRejectReason());
            }
        }
    }

    @Test
    void snapshotReadDuringConcurrentUpdatesIsConsistent() throws Exception {
        String orderId = "C-snapshot-consistency";
        int total = 2000;
        orderService.acceptOrder(orderId, total);
        orderService.markReported(orderId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> writer = pool.submit(() -> {
            await(start);
            for (int i = 0; i < total; i++) {
                orderService.onExecutionReport(exec("w-" + i, orderId, 1));
            }
        });
        AtomicInteger inconsistent = new AtomicInteger();
        Future<?> reader = pool.submit(() -> {
            await(start);
            while (!writer.isDone()) {
                OrderSnapshot s = orderService.getSnapshot(orderId).orElseThrow();
                // 快照一致性：filledQty 必须等于成交明细数量之和（同一版本）
                long sum = s.getFills().stream().mapToLong(ExecutionReport::getExecQty).sum();
                if (sum != s.getFilledQty() || s.getFills().size() != s.getFilledQty()) {
                    inconsistent.incrementAndGet();
                }
                if (s.getFilledQty() < 0 || s.getFilledQty() > total) {
                    inconsistent.incrementAndGet();
                }
            }
        });
        start.countDown();
        writer.get(60, TimeUnit.SECONDS);
        reader.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(0, inconsistent.get(), "snapshot must never expose intermediate state");
        assertEquals(total, orderService.getSnapshot(orderId).orElseThrow().getFilledQty());
    }

    @Test
    void concurrentAmendAndExecutionIsSerialized() throws Exception {
        String orderId = "C-amend-vs-exec";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e0", orderId, 40));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<TransitionResult> amendFuture = pool.submit(() -> {
            await(start);
            return orderService.amend(orderId, 60);
        });
        Future<TransitionResult> execFuture = pool.submit(() -> {
            await(start);
            return orderService.onExecutionReport(exec("e1", orderId, 50));
        });
        start.countDown();
        TransitionResult amendResult = amendFuture.get(10, TimeUnit.SECONDS);
        TransitionResult execResult = execFuture.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // 串行化后两种合法结局：
        // a) 改单先到：qty 100->60，成交 50 超过剩余 20 被隔离拒绝，最终 leavesQty=20
        // b) 成交先到：filled 90，改单 60 < 90 被 INVALID_AMEND_QUANTITY 拒绝
        OrderSnapshot snapshot = orderService.getSnapshot(orderId).orElseThrow();
        if (amendResult.isAccepted()) {
            assertEquals(60, snapshot.getOrderQty());
            assertEquals(40, snapshot.getFilledQty());
            assertFalse(execResult.isAccepted());
            assertEquals(RejectReason.QUARANTINED, execResult.getRejectReason());
        } else {
            assertEquals(RejectReason.INVALID_AMEND_QUANTITY, amendResult.getRejectReason());
            assertEquals(90, snapshot.getFilledQty());
            assertEquals(100, snapshot.getOrderQty());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
