package com.github.guoyyit.orderlifecycleomsjava.service;

import com.github.guoyyit.orderlifecycleomsjava.domain.ExecReport;
import com.github.guoyyit.orderlifecycleomsjava.domain.FillDecision;
import com.github.guoyyit.orderlifecycleomsjava.domain.FillOutcome;
import com.github.guoyyit.orderlifecycleomsjava.domain.QuarantineEntry;
import com.github.guoyyit.orderlifecycleomsjava.domain.ReconcileReport;
import com.github.guoyyit.orderlifecycleomsjava.store.OrderStore;
import com.github.guoyyit.orderlifecycleomsjava.store.QuarantineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationAndReplayTest {

    private OrderStore orders;
    private QuarantineStore quarantine;
    private OrderCommandService commands;
    private ExecutionReportService reports;
    private ReconciliationService reconciliation;

    private ExecReport fr(String oid, long seq, String execId, long last, long cum) {
        return new ExecReport(oid, seq, execId, last, 100, cum);
    }

    @BeforeEach
    void setUp() {
        orders = new OrderStore();
        quarantine = new QuarantineStore();
        commands = new OrderCommandService(orders);
        reports = new ExecutionReportService(orders, quarantine);
        reconciliation = new ReconciliationService(orders);
    }

    @Test
    void aggregateAlwaysEqualsAcceptedReportsAfterOutOfOrderDuplicates() {
        commands.accept("O1", 100);
        commands.markNew("O1");
        reports.process(fr("O1", 3, "E3", 30, 100));
        reports.process(fr("O1", 1, "E1", 40, 40));
        reports.process(fr("O1", 1, "E1", 40, 40)); // 重复
        reports.process(fr("O1", 2, "E2", 30, 70));
        reports.process(fr("O1", 2, "E2", 30, 70)); // 重复

        ReconcileReport r = reconciliation.reconcile("O1");
        assertTrue(r.consistent(), () -> "diagnostics: " + r.diagnostics());
        assertEquals(100, r.sumOfAcceptedLastQty());
        assertEquals(100, r.orderFilledQty());
        assertEquals(0, r.remainingQty());
    }

    @Test
    void quarantinedReportIsIsolatedAndRejectedFromAggregate() {
        commands.accept("O1", 100);
        commands.markNew("O1");
        FillDecision d = reports.process(fr("O1", 1, "E1", 150, 150)); // 超量
        assertEquals(FillOutcome.QUARANTINED, d.outcome());

        assertEquals(1, quarantine.byOrder("O1").size());
        ReconcileReport r = reconciliation.reconcile("O1");
        assertTrue(r.consistent());
        assertEquals(0, r.sumOfAcceptedLastQty());
    }

    @Test
    void unknownOrderFillIsQuarantinedThenAcceptedOnReplay() {
        FillDecision d = reports.process(fr("O404", 1, "E1", 10, 10));
        assertEquals(FillOutcome.QUARANTINED, d.outcome());
        assertEquals(1, quarantine.byOrder("O404").size());

        // 订单随后被创建并报出，重放后接受
        commands.accept("O404", 100);
        commands.markNew("O404");
        List<FillDecision> replayed = reports.replayQuarantine("O404");
        assertEquals(FillOutcome.APPLIED, replayed.get(0).outcome());
        assertTrue(quarantine.byOrder("O404").isEmpty());
        assertEquals(10, reconciliation.reconcile("O404").orderFilledQty());
    }

    @Test
    void replayAcceptedReportDoesNotChangeFinalResult() {
        commands.accept("O1", 100);
        commands.markNew("O1");
        reports.process(fr("O1", 1, "E1", 40, 40));
        reports.process(fr("O1", 2, "E2", 60, 100));
        long filledBefore = reconciliation.reconcile("O1").orderFilledQty();

        // 重放所有已处理回报（模拟重发）：全部应判为 DUPLICATE，结果不变
        for (ExecReport r : List.of(fr("O1", 1, "E1", 40, 40), fr("O1", 2, "E2", 60, 100))) {
            assertEquals(FillOutcome.DUPLICATE, reports.process(r).outcome());
        }
        ReconcileReport after = reconciliation.reconcile("O1");
        assertEquals(filledBefore, after.orderFilledQty());
        assertEquals(100, after.orderFilledQty());
        assertTrue(after.consistent());
    }

    @Test
    void badReportStaysQuarantinedWhenReplayedUntilFixed() {
        commands.accept("O1", 100);
        commands.markNew("O1");
        reports.process(fr("O1", 1, "E1", 120, 120)); // 超量隔离
        List<FillDecision> again = reports.replayQuarantine("O1");
        assertEquals(FillOutcome.QUARANTINED, again.get(0).outcome());
        assertEquals(1, quarantine.byOrder("O1").size());

        // 改单扩容后再重放：撤销窗口外的活动订单，超量条件消失，接受
        commands.replace("O1", "R1", 2, 200);
        List<FillDecision> fixed = reports.replayQuarantine("O1");
        assertEquals(FillOutcome.APPLIED, fixed.get(0).outcome());
        assertTrue(quarantine.byOrder("O1").isEmpty());
    }

    @Test
    void cancelledOrderReconcilesWithLateFills() {
        commands.accept("O1", 100);
        commands.markNew("O1");
        reports.process(fr("O1", 1, "E1", 30, 30));
        commands.cancel("O1", "C1", 2);
        reports.process(fr("O1", 2, "E2", 20, 50)); // 窗口内迟到接受
        reports.process(fr("O1", 3, "E3", 50, 100)); // 窗口外拒绝（不进隔离区，进决策）

        ReconcileReport r = reconciliation.reconcile("O1");
        assertTrue(r.consistent(), () -> r.diagnostics().toString());
        assertEquals(50, r.sumOfAcceptedLastQty());
        assertEquals(50, r.remainingQty());
    }
}
