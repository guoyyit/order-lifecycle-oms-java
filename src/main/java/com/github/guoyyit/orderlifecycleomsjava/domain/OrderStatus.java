package com.github.guoyyit.orderlifecycleomsjava.domain;

/**
 * 订单全生命周期状态。
 */
public enum OrderStatus {
    /** 已受理，尚未报出 */
    ACCEPTED,
    /** 已报交易所，尚无成交 */
    NEW,
    /** 部分成交 */
    PARTIALLY_FILLED,
    /** 全部成交 */
    FILLED,
    /** 已撤销（部分成交后撤销也归为此状态） */
    CANCELLED,
    /** 已拒绝 */
    REJECTED
}
