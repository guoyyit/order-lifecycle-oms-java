package com.github.guoyyit.orderlifecycleomsjava.domain;

import java.util.List;

/**
 * 单个订单的成交对账结论。
 */
public record ReconcileReport(
        String orderId,
        boolean consistent,
        OrderStatus status,
        long orderQty,
        long sumOfAcceptedLastQty,
        long orderFilledQty,
        long remainingQty,
        Long cancelEffectiveSeq,
        List<String> diagnostics) {
}
