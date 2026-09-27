package com.github.guoyyit.orderlifecycleomsjava.domain;

import com.github.guoyyit.orderlifecycleomsjava.exception.OrderStateException;
import org.junit.jupiter.api.Test;

import static com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderAggregateTest {

    private ExecReport fr(String oid, long seq, String execId, long last, long cum) {
        return new ExecReport(oid, seq, execId, last, 100, cum);
    }

    @Test
    void happyPathAcceptNewPartialFill() {
        Order o = new Order("O1", 100);
        o.markNew();
        FillDecision d = o.applyFill(fr("O1", 1, "E1", 40, 40));
        assertEquals(FillOutcome.APPLIED, d.outcome());
        assertEquals(PARTIALLY_FILLED, o.snapshot().status());
        assertEquals(40, o.snapshot().filledQty());
        assertEquals(60, o.snapshot().remainingQty());

        FillDecision d2 = o.applyFill(fr("O1", 2, "E2", 60, 100));
        assertEquals(FillOutcome.APPLIED, d2.outcome());
        assertEquals(FILLED, o.snapshot().status());
        assertEquals(100, o.snapshot().filledQty());
        assertEquals(0, o.snapshot().remainingQty());
    }

    @Test
    void duplicateExecIdDoesNotAccumulate() {
        Order o = new Order("O1", 100);
        o.markNew();
        o.applyFill(fr("O1", 1, "E1", 40, 40));
        // 完全相同的回报重复到达
        FillDecision again = o.applyFill(fr("O1", 1, "E1", 40, 40));
        // 篡改 lastQty 的重复回报也必须被去重，绝不能累加
        FillDecision tampered = o.applyFill(fr("O1", 1, "E1", 999, 999));

        assertEquals(FillOutcome.DUPLICATE, again.outcome());
        assertEquals(FillOutcome.DUPLICATE, tampered.outcome());
        assertEquals(40, o.snapshot().filledQty());
        assertEquals(1, o.snapshot().fills().size());
    }

    @Test
    void outOfOrderFillsAreAccepted() {
        Order o = new Order("O1", 100);
        o.markNew();
        // 先到 seq=3 的成交，再到 seq=1/seq=2，cumQty 乱序但总量自洽
        assertEquals(FillOutcome.APPLIED, o.applyFill(fr("O1", 3, "E3", 30, 100)).outcome());
        assertEquals(FillOutcome.APPLIED, o.applyFill(fr("O1", 1, "E1", 40, 40)).outcome());
        assertEquals(FillOutcome.APPLIED, o.applyFill(fr("O1", 2, "E2", 30, 70)).outcome());

        assertEquals(100, o.snapshot().filledQty());
        assertEquals(FILLED, o.snapshot().status());
        assertEquals(3, o.snapshot().fills().size());
    }

    @Test
    void cancelWindowLateFillsDeterministicallyAcceptedOrRejected() {
        Order o = new Order("O1", 100);
        o.markNew();
        o.applyFill(fr("O1", 1, "E1", 30, 30));
        // 撤销在 seq=2 生效：seq<=2 的成交发生在撤销之前
        o.cancel("C1", 2);
        assertEquals(CANCELLED, o.snapshot().status());
        assertEquals(30, o.snapshot().filledQty());

        // seq=2 的迟到成交在窗口内：接受，状态仍为 CANCELLED（未补足）
        FillDecision lateIn = o.applyFill(fr("O1", 2, "E2", 20, 50));
        assertEquals(FillOutcome.LATE_ACCEPTED, lateIn.outcome());
        assertEquals(CANCELLED, o.snapshot().status());
        assertEquals(50, o.snapshot().filledQty());

        // seq=3 在撤销生效点之后：确定性拒绝，成交量不变
        FillDecision lateOut = o.applyFill(fr("O1", 3, "E3", 10, 60));
        assertEquals(FillOutcome.LATE_REJECTED, lateOut.outcome());
        assertEquals(FillRejectReason.AFTER_CANCEL_EFFECTIVE_SEQ, lateOut.rejectReason());
        assertEquals(50, o.snapshot().filledQty());
        assertEquals(2, o.snapshot().fills().size());
    }

    @Test
    void lateFillInsideCancelWindowCanCompleteToFilled() {
        Order o = new Order("O1", 100);
        o.markNew();
        o.cancel("C1", 5);
        // 撤销时交易所实际已全部成交，回报迟到且恰好在窗口内
        FillDecision d = o.applyFill(fr("O1", 5, "E1", 100, 100));
        assertEquals(FillOutcome.LATE_ACCEPTED, d.outcome());
        assertEquals(FILLED, o.snapshot().status());
    }

    @Test
    void fillsOnRejectedOrderAreRejected() {
        Order o = new Order("O1", 100);
        o.markNew();
        o.reject("risk reject");
        FillDecision d = o.applyFill(fr("O1", 1, "E1", 10, 10));
        assertEquals(FillOutcome.LATE_REJECTED, d.outcome());
        assertEquals(FillRejectReason.ORDER_REJECTED, d.rejectReason());
        assertEquals(0, o.snapshot().filledQty());
    }

    @Test
    void overFilledReportIsQuarantinedAndDoesNotPolluteOrder() {
        Order o = new Order("O1", 100);
        o.markNew();
        FillDecision d = o.applyFill(fr("O1", 1, "E1", 120, 120));
        assertEquals(FillOutcome.QUARANTINED, d.outcome());
        assertEquals(FillRejectReason.OVER_FILLED, d.rejectReason());
        assertEquals(NEW, o.snapshot().status());
        assertEquals(0, o.snapshot().filledQty());
        assertTrue(o.snapshot().fills().isEmpty());
    }

    @Test
    void invalidQuantityAndInconsistentCumQtyAreQuarantined() {
        Order o = new Order("O1", 100);
        o.markNew();
        FillDecision badQty = o.applyFill(new ExecReport("O1", 1, "E1", 0, 100, 0));
        FillDecision badCum = o.applyFill(new ExecReport("O1", 2, "E2", 50, 100, 20));
        assertEquals(FillRejectReason.INVALID_QUANTITY, badQty.rejectReason());
        assertEquals(FillRejectReason.INCONSISTENT_CUMULATIVE, badCum.rejectReason());
        assertEquals(0, o.snapshot().filledQty());
    }

    @Test
    void cancelAfterFullFillRejectedAndViceVersa() {
        Order o = new Order("O1", 100);
        o.markNew();
        o.applyFill(fr("O1", 1, "E1", 100, 100));
        OrderStateException ex = assertThrows(OrderStateException.class, () -> o.cancel("C1", 1));
        assertEquals(RejectReason.ORDER_TERMINAL, ex.reason());
    }

    @Test
    void replaceAfterPartialFillRespectsFilledQty() {
        Order o = new Order("O1", 100);
        o.markNew();
        o.applyFill(fr("O1", 1, "E1", 40, 40));

        OrderStateException ex = assertThrows(OrderStateException.class,
                () -> o.replace("R1", 2, 30));
        assertEquals(RejectReason.INVALID_PARAMETER, ex.reason());

        o.replace("R2", 2, 60);
        assertEquals(60, o.snapshot().orderQty());
        assertEquals(40, o.snapshot().filledQty());
        assertEquals(PARTIALLY_FILLED, o.snapshot().status());

        // 改单到恰好等于已成交量 -> 全部成交
        o.replace("R3", 3, 40);
        assertEquals(FILLED, o.snapshot().status());
    }

    @Test
    void duplicateCancelRequestIsIdempotent() {
        Order o = new Order("O1", 100);
        o.markNew();
        o.cancel("C1", 5);
        OrderView v = o.cancel("C1", 5);
        assertEquals(CANCELLED, v.status());
        assertEquals(5L, v.cancelEffectiveSeq());
    }
}
