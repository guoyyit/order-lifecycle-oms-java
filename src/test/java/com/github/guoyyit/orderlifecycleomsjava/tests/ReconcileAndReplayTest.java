package com.github.guoyyit.orderlifecycleomsjava.tests;

import com.github.guoyyit.orderlifecycleomsjava.domain.ExecutionReport;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderSnapshot;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus;
import com.github.guoyyit.orderlifecycleomsjava.domain.RejectReason;
import com.github.guoyyit.orderlifecycleomsjava.service.OrderService;
import com.github.guoyyit.orderlifecycleomsjava.service.ReconcileReport;
import com.github.guoyyit.orderlifecycleomsjava.service.ReconcileService;
import com.github.guoyyit.orderlifecycleomsjava.statemachine.TransitionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对账与重放测试：聚合结果与已接受回报一致；重放不改变最终结果。
 */
@SpringBootTest
class ReconcileAndReplayTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ReconcileService reconcileService;

    private static ExecutionReport exec(String execId, String orderId, long qty) {
        return new ExecutionReport(execId, orderId, qty, 1000L, Instant.now());
    }

    @Test
    void reconcileIsConsistentAfterMixedExecutions() {
        String orderId = "R-consistent";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e1", orderId, 10));
        orderService.onExecutionReport(exec("e2", orderId, 20));
        orderService.onExecutionReport(exec("e2", orderId, 20)); // 重复，不计
        orderService.onExecutionReport(exec("e3", orderId, 15));

        ReconcileReport report = reconcileService.reconcile(orderId).orElseThrow();
        assertTrue(report.isConsistent(), "diagnostics: " + report.getDiagnostics());
        assertEquals(100, report.getOrderQty());
        assertEquals(45, report.getFilledQty());
        assertEquals(55, report.getLeavesQty());
        assertEquals(45, report.getSumOfFills());
        assertEquals(3, report.getFillCount());
        assertTrue(report.getDiagnostics().isEmpty());
    }

    @Test
    void reconcileIsConsistentAtEveryStep() {
        String orderId = "R-every-step";
        orderService.acceptOrder(orderId, 50);
        orderService.markReported(orderId);
        for (int i = 0; i < 50; i++) {
            orderService.onExecutionReport(exec("s-" + i, orderId, 1));
            ReconcileReport report = reconcileService.reconcile(orderId).orElseThrow();
            assertTrue(report.isConsistent(), "inconsistent at step " + i + ": " + report.getDiagnostics());
        }
        assertEquals(OrderStatus.FILLED, orderService.getSnapshot(orderId).orElseThrow().getStatus());
    }

    @Test
    void reconcileCancelledOrderKeepsPartialFills() {
        String orderId = "R-cancelled";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e1", orderId, 30));
        orderService.cancel(orderId);

        ReconcileReport report = reconcileService.reconcile(orderId).orElseThrow();
        assertTrue(report.isConsistent(), "diagnostics: " + report.getDiagnostics());
        assertEquals(30, report.getFilledQty());
        assertEquals(70, report.getLeavesQty());
    }

    @Test
    void reconcileMissingOrderIsEmpty() {
        assertTrue(reconcileService.reconcile("no-such-order").isEmpty());
    }

    @Test
    void replayAcceptedExecutionsDoesNotChangeFinalResult() {
        String orderId = "R-replay";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e1", orderId, 25));
        orderService.onExecutionReport(exec("e2", orderId, 35));
        orderService.onExecutionReport(exec("e3", orderId, 40));

        OrderSnapshot before = orderService.getSnapshot(orderId).orElseThrow();
        assertEquals(OrderStatus.FILLED, before.getStatus());

        List<TransitionResult> replayResults = orderService.replayAcceptedExecutions(orderId);
        assertEquals(3, replayResults.size());
        for (TransitionResult r : replayResults) {
            assertFalse(r.isAccepted());
            assertEquals(RejectReason.DUPLICATE_EXECUTION, r.getRejectReason(),
                    "replayed execution must be idempotently rejected");
        }

        OrderSnapshot after = orderService.getSnapshot(orderId).orElseThrow();
        assertEquals(before.getStatus(), after.getStatus());
        assertEquals(before.getFilledQty(), after.getFilledQty());
        assertEquals(before.getLeavesQty(), after.getLeavesQty());
        assertEquals(before.getFills().size(), after.getFills().size());
        assertEquals(before.getVersion(), after.getVersion(), "replay must not bump version");

        ReconcileReport report = reconcileService.reconcile(orderId).orElseThrow();
        assertTrue(report.isConsistent());
    }

    @Test
    void replayOnPartiallyFilledOrderIsIdempotent() {
        String orderId = "R-replay-partial";
        orderService.acceptOrder(orderId, 100);
        orderService.markReported(orderId);
        orderService.onExecutionReport(exec("e1", orderId, 10));

        OrderSnapshot before = orderService.getSnapshot(orderId).orElseThrow();
        List<TransitionResult> results = orderService.replayAcceptedExecutions(orderId);
        assertEquals(1, results.size());
        assertEquals(RejectReason.DUPLICATE_EXECUTION, results.get(0).getRejectReason());

        OrderSnapshot after = orderService.getSnapshot(orderId).orElseThrow();
        assertEquals(before.getVersion(), after.getVersion());
        assertEquals(10, after.getFilledQty());
    }
}
