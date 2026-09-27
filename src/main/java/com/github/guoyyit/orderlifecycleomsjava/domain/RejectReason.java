package com.github.guoyyit.orderlifecycleomsjava.domain;

/**
 * 订单被拒绝（下单受理拒绝 / 状态迁移拒绝）的可区分原因。
 */
public enum RejectReason {
    /** 非法状态迁移 */
    ILLEGAL_TRANSITION,
    /** 订单不存在 */
    UNKNOWN_ORDER,
    /** 订单已终结 */
    ORDER_TERMINAL,
    /** 重复请求 */
    DUPLICATE_REQUEST,
    /** 参数非法 */
    INVALID_PARAMETER
}
