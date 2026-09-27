package com.github.guoyyit.orderlifecycleomsjava.service;

import com.github.guoyyit.orderlifecycleomsjava.domain.ExecReport;
import com.github.guoyyit.orderlifecycleomsjava.domain.FillDecision;
import com.github.guoyyit.orderlifecycleomsjava.domain.FillOutcome;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderView;
import com.github.guoyyit.orderlifecycleomsjava.store.OrderStore;
import com.github.guoyyit.orderlifecycleomsjava.store.QuarantineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus.CANCELLED;
import static com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus.FILLED;
import static org.junit.jupiter.api.Assertions.*;

class OrderConcurrencyTest {

    private OrderStore orders;
    private QuarantineStore quarantine;
    private OrderCommandService commands;
    private ExecutionReportService reports;
    private OrderQueryService queries;

    @BeforeEach
    void setUp() {
        orders = new OrderStore();
        quarantine = new QuarantineStore();
        commands = new OrderCommandService(orders);
        reports = new ExecutionReportService(orders, quarantine);
        queries = new OrderQueryService(orders);
    }

    private ExecReport fr(long seq, String execId, long last, long cum) {
        return new ExecReport("O1", seq, execId, last, 100, cum);
    }

    /**
     * 撤销与成交回报并发到达：无论谁先拿到锁，最终状态必须可解释且自洽。
     */
    @RepeatedTest(20)
    void concurrentCancelAndFillsEndsInDeterministicState() throws Exception {
        commands.accept("O1", 100);
        commands.markNew("O1");

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger seq = new AtomicInteger();

        // 一半线程持续发成交（每笔 10，共远超订单量），一半线程尝试撤销（生效点=5）
        for (int t = 0; t < threads; t++) {
            final int ti = t;
            pool.submit(() -> {
                try {
                    start.await();
                    if (ti % 2 == 0) {
                        for (int i = 0; i < 10; i++) {
                            long s = seq.incrementAndGet();
                            reports.process(fr(s, "E-" + ti + "-" + i, 10, s * 10));
                        }
                    } else {
                        commands.cancel("O1", "C-" + ti, 5);
                    }
                } catch (Exception ignored) {
                    // 撤销终结订单时可能抛 ORDER_TERMINAL / DUPLICATE_REQUEST，属于预期竞争结果
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        OrderView v = queries.get("O1");
        OrderStatus s = v.status();
        assertTrue(s == CANCELLED || s == FILLED, "unexpected status " + s);

        // 不变量：已成交量是 10 的整数倍（每笔 10），不超订单量
        assertTrue(v.filledQty() >= 0 && v.filledQty() <= 100);
        assertEquals(v.filledQty() % 10, 0);
        assertEquals(100 - v.filledQty(), v.remainingQty());
        assertEquals(v.fills().size() * 10L, v.filledQty(),
                "snapshot fills must sum to filledQty in the same version");

        if (s == CANCELLED) {
            assertNotNull(v.cancelEffectiveSeq());
            // 撤销窗口规则可解释：每一笔已接受成交的 seq 必须 <= 生效点
            for (var f : v.fills()) {
                assertTrue(f.seq() <= v.cancelEffectiveSeq(),
                        "accepted fill seq " + f.seq() + " > cancelEffectiveSeq " + v.cancelEffectiveSeq());
            }
        } else {
            assertEquals(100, v.filledQty());
        }
    }

    /**
     * 查询与写入并发：每个读到的快照内部必须自洽（版本一致、无中间态）。
     */
    @Test
    void readsAlwaysSeeSelfConsistentSnapshot() throws Exception {
        commands.accept("O1", 100);
        commands.markNew("O1");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch stopWriters = new CountDownLatch(2);
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        Set<Long> observedVersions = ConcurrentHashMapNewSet();

        // 2 个写线程：成交与撤销/重建交替（这里仅发成交直到终结）
        for (int w = 0; w < 2; w++) {
            final int wi = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 5; i++) {
                        reports.process(fr(wi * 5L + i + 1, "W" + wi + "-" + i, 10, (wi * 5L + i + 1) * 10));
                    }
                } catch (Exception ignored) {
                } finally {
                    stopWriters.countDown();
                }
            });
        }
        // 2 个读线程：反复读取并校验快照自洽
        for (int r = 0; r < 2; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (stopWriters.getCount() > 0) {
                        OrderView v = queries.get("O1");
                        observedVersions.add(v.version());
                        long sum = v.fills().stream().mapToLong(f -> f.lastQty()).sum();
                        if (sum != v.filledQty()) {
                            violations.add("sum=" + sum + " filledQty=" + v.filledQty());
                        }
                        if (v.remainingQty() != v.orderQty() - v.filledQty()) {
                            violations.add("remaining inconsistent");
                        }
                        if (v.filledQty() > 100) {
                            violations.add("overfilled: " + v.filledQty());
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }
        start.countDown();
        assertTrue(stopWriters.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue(violations.isEmpty(), "inconsistent snapshots observed: " + violations);
        // 读到过多个版本，证明读确实在并发地观察写过程，且每个版本都自洽
        assertTrue(observedVersions.size() >= 2, "expected to observe multiple versions: " + observedVersions);
        OrderView finalView = queries.get("O1");
        assertEquals(100, finalView.filledQty());
        assertEquals(FILLED, finalView.status());
    }

    /**
     * 乱序 + 重复回报在多线程下并发处理，最终聚合必须与“去重后全部回报”一致。
     */
    @Test
    void concurrentOutOfOrderDuplicateFillsAggregateExactlyOnce() throws Exception {
        commands.accept("O1", 100);
        commands.markNew("O1");
        List<ExecReport> reportsToSend = new ArrayList<>();
        // 10 笔不同成交，每笔 10；乱序排列后，每笔再重复发 3 次
        List<ExecReport> unique = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            unique.add(fr(i, "E" + i, 10, i * 10L));
        }
        java.util.Collections.reverse(unique);
        for (ExecReport r : unique) {
            for (int k = 0; k < 3; k++) {
                reportsToSend.add(r);
            }
        }
        java.util.Collections.shuffle(reportsToSend);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(reportsToSend.size());
        for (ExecReport r : reportsToSend) {
            pool.submit(() -> {
                try {
                    FillDecision d = reports.process(r);
                    assertTrue(d.outcome() == FillOutcome.APPLIED
                            || d.outcome() == FillOutcome.DUPLICATE);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        OrderView v = queries.get("O1");
        assertEquals(100, v.filledQty());
        assertEquals(10, v.fills().size());
        assertEquals(0, v.remainingQty());
        assertEquals(FILLED, v.status());
        assertEquals(Set.copyOf(v.fills().stream().map(f -> f.execId()).toList()).size(), 10);
    }

    private static <T> Set<T> ConcurrentHashMapNewSet() {
        return java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    }
}
