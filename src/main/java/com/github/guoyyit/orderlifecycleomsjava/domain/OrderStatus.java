package com.github.guoyyit.orderlifecycleomsjava.domain;

/**
 * 订单状态机中的状态。
 *
 * 生命周期：ACCEPTED -> REPORTED -> PARTIALLY_FILLED -> FILLED
 * 任意非终态可迁移到 CANCELLED / REJECTED（终态）。
 */
public enum OrderStatus {

    /** 已受理：订单已被系统接受，尚未报送交易所。 */
    ACCEPTED,

    /** 已报：订单已报送交易所，等待成交。 */
    REPORTED,

    /** 部分成交：已有成交回报但尚未全部成交。 */
    PARTIALLY_FILLED,

    /** 全部成交：累计成交量等于委托数量（终态）。 */
    FILLED,

    /** 已撤销：订单被撤销（终态）。 */
    CANCELLED,

    /** 已拒绝：订单被拒绝（终态）。 */
    REJECTED;

    /**
     * 是否为终态。终态不再接受任何状态迁移。
     */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED;
    }
}
