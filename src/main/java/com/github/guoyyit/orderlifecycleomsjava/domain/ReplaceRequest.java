package com.github.guoyyit.orderlifecycleomsjava.domain;

/**
 * 改单请求（仅改数量，支持部分成交后改单）。
 *
 * @param effectiveSeq 改单生效点，语义同撤销生效点
 * @param newTotalQty  改单后订单的新总数量
 */
public record ReplaceRequest(String orderId, String requestId, long effectiveSeq, long newTotalQty) {
}
