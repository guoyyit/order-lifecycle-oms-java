package com.github.guoyyit.orderlifecycleomsjava.domain;

/**
 * 已接受的成交明细（不可变）。
 */
public record FillRecord(String execId, long seq, long lastQty, long lastPrice, long cumQty) {
}
