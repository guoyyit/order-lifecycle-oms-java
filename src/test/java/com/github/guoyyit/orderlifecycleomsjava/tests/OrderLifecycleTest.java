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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单全生命周期测试：受理 -> 已报 -> 部分成交 -> 全部成交，以及撤销与改单。
 */
@SpringBootTest
class OrderLifecycleTest {

    @Autowired
    private OrderService orderService;

    private static ExecutionReport exec(String execId, String orderId, long qty) {
        return new ExecutionReport(execId, orderId, qty, 1000L, Instant.now());
    }

    @Test
    void fullLifecycleAcceptedToFilled() {
        String orderId = "L-full-1";
        assertTrue(orderService.acceptOrder(orderId, 100).isAccepted());
        assertTrue(orderService.markReported(orderId).isAccepted());

        TransitionResult r1 = orderService.onExecutionReport(exec("e1", orderId, 30));
        assertTrue(r1.isAccepted());
        assertEquals(OrderStatus.PARTIALLY_FILLED, r1.getNewStatus());

        TransitionResult r2 = orderService.onExecutionReport(exec("e2", orderId, 70));
        assertTrue(r2.isAccepted());
        assertEquals(OrderStatus.FILLED, r2.getNewStatus());

        OrderSnapshot snapshot = orderService.getSnapshot(orderId).orElseThrow();
        assertEquals(OrderStatus.FILLED, snapshot.getStatus());
        assertEquals(100, snapshot.getFilledQty());
        assertEquals(0, snapshot.getLeavesQty());
        assertEquals(2, snapshot.getFills().size());
    }

    @Test
    void executionBeforeReportedIsRejected() {
        String orderId = "L-not-reported";
        orderService.acceptOrder(orderId, 100);
        TransitionResult r = orderService.onExecutionReport(exec("e1", orderId, 10));
        assertFalse(r.isAccepted());
        assertEquals(RejectReason.ILLEGAL_STATE_TRANSITION, r.getRejectReason());
    }

    @Test
    void cancelInPartiallyFilledStateKeepsFills() {
        String orderId = "L-cancel-partial";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e1", orderId, 40));

        TransitionResult cancel = orderService.cancel(orderId);
        assertTrue(cancel.isAccepted());
        assertEquals(OrderStatus.CANCELLED, cancel.getNewStatus());

        OrderSnapshot snapshot = orderService.getSnapshot(orderId).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, snapshot.getStatus());
        assertEquals(40, snapshot.getFilledQty());
        assertEquals(60, snapshot.getLeavesQty());
        assertEquals(1, snapshot.getFills().size());
    }

    @Test
    void cancelOnTerminalOrderIsRejected() {
        String orderId = "L-cancel-terminal";
        orderService.acceptOrder(orderId, 50);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e1", orderId, 50));

        TransitionResult cancel = orderService.cancel(orderId);
        assertFalse(cancel.isAccepted());
        assertEquals(RejectReason.ORDER_ALREADY_TERMINAL, cancel.getRejectReason());
    }

    @Test
    void amendInPartiallyFilledState() {
        String orderId = "L-amend-partial";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e1", orderId, 40));

        // 改小到 60（>= 已成交 40）：允许
        TransitionResult amend = orderService.amend(orderId, 60);
        assertTrue(amend.isAccepted());
        OrderSnapshot snapshot = orderService.getSnapshot(orderId).orElseThrow();
        assertEquals(60, snapshot.getOrderQty());
        assertEquals(40, snapshot.getFilledQty());
        assertEquals(20, snapshot.getLeavesQty());
        assertEquals(OrderStatus.PARTIALLY_FILLED, snapshot.getStatus());
    }

    @Test
    void amendBelowFilledQtyIsRejected() {
        String orderId = "L-amend-below-filled";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e1", orderId, 40));

        TransitionResult amend = orderService.amend(orderId, 30);
        assertFalse(amend.isAccepted());
        assertEquals(RejectReason.INVALID_AMEND_QUANTITY, amend.getRejectReason());
    }

    @Test
    void amendToFilledQtyTerminatesOrder() {
        String orderId = "L-amend-to-filled";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e1", orderId, 40));

        TransitionResult amend = orderService.amend(orderId, 40);
        assertTrue(amend.isAccepted());
        assertEquals(OrderStatus.FILLED, amend.getNewStatus());
    }

    @Test
    void amendUpIsRejected() {
        String orderId = "L-amend-up";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);

        TransitionResult amend = orderService.amend(orderId, 200);
        assertFalse(amend.isAccepted());
        assertEquals(RejectReason.INVALID_AMEND_QUANTITY, amend.getRejectReason());
    }

    @Test
    void operationsOnMissingOrderReturnNotFound() {
        assertEquals(RejectReason.ORDER_NOT_FOUND,
                orderService.markReported("missing-1").getRejectReason());
        assertEquals(RejectReason.ORDER_NOT_FOUND,
                orderService.cancel("missing-2").getRejectReason());
        assertEquals(RejectReason.ORDER_NOT_FOUND,
                orderService.amend("missing-3", 10).getRejectReason());
        assertTrue(orderService.getSnapshot("missing-4").isEmpty());
    }

    @Test
    void duplicateAcceptOrderIsRejected() {
        String orderId = "L-dup-accept";
        assertTrue(orderService.acceptOrder(orderId, 100).isAccepted());
        TransitionResult again = orderService.acceptOrder(orderId, 100);
        assertFalse(again.isAccepted());
        assertEquals(RejectReason.STATE_CONFLICT, again.getRejectReason());
    }
}
