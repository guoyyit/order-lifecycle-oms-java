package com.github.guoyyit.orderlifecycleomsjava.tests;

import com.github.guoyyit.orderlifecycleomsjava.domain.ExecutionReport;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderSnapshot;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus;
import com.github.guoyyit.orderlifecycleomsjava.domain.RejectReason;
import com.github.guoyyit.orderlifecycleomsjava.service.OrderService;
import com.github.guoyyit.orderlifecycleomsjava.service.QuarantineStore;
import com.github.guoyyit.orderlifecycleomsjava.statemachine.TransitionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 成交回报乱序、重复、迟到、异常隔离的测试。
 */
@SpringBootTest
class ExecutionReportHandlingTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private QuarantineStore quarantineStore;

    private static ExecutionReport exec(String execId, String orderId, long qty) {
        return new ExecutionReport(execId, orderId, qty, 1000L, Instant.now());
    }

    @Test
    void outOfOrderExecutionsAccumulateCorrectly() {
        String orderId = "E-out-of-order";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);

        // 乱序到达：e3(40) 先于 e1(30)、e2(30) 到达
        assertTrue(orderService.onExecutionReport(exec("e3", orderId, 40)).isAccepted());
        assertTrue(orderService.onExecutionReport(exec("e1", orderId, 30)).isAccepted());
        TransitionResult last = orderService.onExecutionReport(exec("e2", orderId, 30));
        assertTrue(last.isAccepted());
        assertEquals(OrderStatus.FILLED, last.getNewStatus());

        OrderSnapshot snapshot = orderService.getSnapshot(orderId).orElseThrow();
        assertEquals(100, snapshot.getFilledQty());
        assertEquals(3, snapshot.getFills().size());
    }

    @Test
    void duplicateExecutionDoesNotDoubleCount() {
        String orderId = "E-duplicate";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);

        assertTrue(orderService.onExecutionReport(exec("e1", orderId, 30)).isAccepted());
        // 同一 execId 重复到达
        TransitionResult dup = orderService.onExecutionReport(exec("e1", orderId, 30));
        assertFalse(dup.isAccepted());
        assertEquals(RejectReason.DUPLICATE_EXECUTION, dup.getRejectReason());

        OrderSnapshot snapshot = orderService.getSnapshot(orderId).orElseThrow();
        assertEquals(30, snapshot.getFilledQty(), "duplicate must not double count");
        assertEquals(1, snapshot.getFills().size());
    }

    @Test
    void lateExecutionAfterTerminalIsRejectedDeterministically() {
        String orderId = "E-late";
        orderService.acceptOrder(orderId, 50);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e1", orderId, 50));
        assertEquals(OrderStatus.FILLED, orderService.getSnapshot(orderId).orElseThrow().getStatus());

        // 全部成交后迟到的回报：确定性拒绝
        TransitionResult late = orderService.onExecutionReport(exec("e2", orderId, 10));
        assertFalse(late.isAccepted());
        assertEquals(RejectReason.LATE_EXECUTION_AFTER_TERMINAL, late.getRejectReason());

        OrderSnapshot snapshot = orderService.getSnapshot(orderId).orElseThrow();
        assertEquals(50, snapshot.getFilledQty());
        assertEquals(1, snapshot.getFills().size());
    }

    @Test
    void lateExecutionAfterCancelIsRejected() {
        String orderId = "E-late-after-cancel";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e1", orderId, 40));
        orderService.cancel(orderId);

        TransitionResult late = orderService.onExecutionReport(exec("e2", orderId, 10));
        assertFalse(late.isAccepted());
        assertEquals(RejectReason.LATE_EXECUTION_AFTER_TERMINAL, late.getRejectReason());
        assertEquals(40, orderService.getSnapshot(orderId).orElseThrow().getFilledQty());
    }

    @Test
    void overFillingExecutionIsQuarantinedAndDoesNotPollute() {
        String orderId = "E-overfill";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e1", orderId, 60));

        // 成交量 50 超过剩余 40：异常回报，隔离且不污染订单
        TransitionResult bad = orderService.onExecutionReport(exec("e2", orderId, 50));
        assertFalse(bad.isAccepted());
        assertEquals(RejectReason.QUARANTINED, bad.getRejectReason());

        OrderSnapshot snapshot = orderService.getSnapshot(orderId).orElseThrow();
        assertEquals(60, snapshot.getFilledQty(), "quarantined report must not change state");
        assertEquals(1, snapshot.getFills().size());

        List<?> quarantined = quarantineStore.listByOrder(orderId);
        assertEquals(1, quarantined.size());
    }

    @Test
    void executionForUnknownOrderIsQuarantined() {
        quarantineStore.clear();
        TransitionResult r = orderService.onExecutionReport(exec("ex", "no-such-order", 10));
        assertFalse(r.isAccepted());
        assertEquals(RejectReason.ORDER_NOT_FOUND, r.getRejectReason());
        assertEquals(1, quarantineStore.listByOrder("no-such-order").size());
    }

    @Test
    void zeroOrNegativeQtyExecutionIsQuarantined() {
        String orderId = "E-zero-qty";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);

        TransitionResult r = orderService.onExecutionReport(exec("e0", orderId, 0));
        assertFalse(r.isAccepted());
        assertEquals(RejectReason.QUARANTINED, r.getRejectReason());
        assertEquals(0, orderService.getSnapshot(orderId).orElseThrow().getFilledQty());
    }
}
