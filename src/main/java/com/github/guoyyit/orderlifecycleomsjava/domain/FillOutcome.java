package com.github.guoyyit.orderlifecycleomsjava.domain;

/**
 * 单条成交回报处理结果。
 */
public enum FillOutcome {
    /** 首次接受，推动状态 */
    APPLIED,
    /** 重复回报，幂等忽略，不重复累计 */
    DUPLICATE,
    /** 迟到回报，按业务规则确定性接受 */
    LATE_ACCEPTED,
    /** 迟到回报，按业务规则确定性拒绝（撤销窗口之后 / 订单已拒绝） */
    LATE_REJECTED,
    /** 非法回报（超量等），隔离不污染订单 */
    QUARANTINED
}
