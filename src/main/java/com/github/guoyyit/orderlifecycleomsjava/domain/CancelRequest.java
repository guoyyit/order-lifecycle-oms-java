package com.github.guoyyit.orderlifecycleomsjava.domain;

/**
 * 撤销请求。
 *
 * @param effectiveSeq 撤销生效点：seq &lt;= effectiveSeq 的成交在撤销前发生，必须接受；之后到达的成交拒绝
 */
public record CancelRequest(String orderId, String requestId, long effectiveSeq) {
}
