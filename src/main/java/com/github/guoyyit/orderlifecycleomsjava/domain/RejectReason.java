package com.github.guoyyit.orderlifecycleomsjava.domain;

/**
 * 状态迁移或回报处理被拒绝时的可区分原因。
 */
public enum RejectReason {

    /** 订单不存在。 */
    ORDER_NOT_FOUND,

    /** 当前状态不允许该迁移（非法状态迁移）。 */
    ILLEGAL_STATE_TRANSITION,

    /** 订单已处于终态，不再接受任何变更。 */
    ORDER_ALREADY_TERMINAL,

    /** 重复的成交回报（同一 execId 已被接受过）。 */
    DUPLICATE_EXECUTION,

    /** 迟到的成交回报：订单已终结，按业务规则拒绝。 */
    LATE_EXECUTION_AFTER_TERMINAL,

    /** 成交数量非法（<=0 或超过剩余未成交量）。 */
    INVALID_EXECUTION_QUANTITY,

    /** 改单数量非法（小于已成交量或 <=0）。 */
    INVALID_AMEND_QUANTITY,

    /** 撤销/改单请求与订单当前状态冲突。 */
    STATE_CONFLICT,

    /** 回报被隔离（异常回报），未应用到订单。 */
    QUARANTINED
}
