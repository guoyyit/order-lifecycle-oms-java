package com.github.guoyyit.orderlifecycleomsjava.domain;

/**
 * 成交回报被拒绝/隔离的可定位原因。
 */
public enum FillRejectReason {
    /** 未知订单 */
    UNKNOWN_ORDER,
    /** 撤销生效点之后的迟到成交 */
    AFTER_CANCEL_EFFECTIVE_SEQ,
    /** 订单已拒绝 */
    ORDER_REJECTED,
    /** 已全部成交后的超量成交 */
    OVER_FILLED,
    /** 成交量非法（<=0） */
    INVALID_QUANTITY,
    /** 回报 cumQty 与明细不自洽 */
    INCONSISTENT_CUMULATIVE
}
