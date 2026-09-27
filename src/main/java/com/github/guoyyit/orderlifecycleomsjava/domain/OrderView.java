package com.github.guoyyit.orderlifecycleomsjava.domain;

import java.util.List;

/**
 * 订单某一时刻的完整版本快照（状态与成交明细来自同一版本，无中间态）。
 */
public record OrderView(
        String orderId,
        OrderStatus status,
        long orderQty,
        long filledQty,
        long remainingQty,
        long version,
        Long cancelEffectiveSeq,
        Long rejectReasonCode,
        String rejectDetail,
        List<FillRecord> fills) {
}
