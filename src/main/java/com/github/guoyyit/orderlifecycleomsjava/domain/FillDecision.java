package com.github.guoyyit.orderlifecycleomsjava.domain;

/**
 * 一条成交回报的处理决策与可解释原因。
 */
public record FillDecision(
        String orderId,
        String execId,
        FillOutcome outcome,
        OrderStatus resultingStatus,
        long filledQtyAfter,
        FillRejectReason rejectReason,
        String detail) {

    public static FillDecision quarantine(String orderId, String execId, FillRejectReason reason, String detail) {
        return new FillDecision(orderId, execId, FillOutcome.QUARANTINED, null, 0, reason, detail);
    }
}
