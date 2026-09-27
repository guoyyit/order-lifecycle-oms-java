package com.github.guoyyit.orderlifecycleomsjava.domain;

/**
 * 交易所成交回报（入站，可乱序/重复/迟到）。
 *
 * @param seq     交易所事件序列号，单调递增，用于判定迟到与撤销窗口
 * @param execId  成交编号，唯一，用于去重
 * @param lastQty 本次成交量
 * @param cumQty  交易所口径的累计成交量（可为乱序值）
 */
public record ExecReport(String orderId, long seq, String execId, long lastQty, long lastPrice, long cumQty) {
}
