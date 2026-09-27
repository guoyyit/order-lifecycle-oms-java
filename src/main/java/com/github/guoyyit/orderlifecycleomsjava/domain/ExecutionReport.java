package com.github.guoyyit.orderlifecycleomsjava.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 成交回报（Execution Report）。
 *
 * 不可变值对象。execId 为交易所侧唯一标识，用于重复回报去重；
 * 回报可能乱序、重复、迟到到达。
 */
public final class ExecutionReport {

    private final String execId;
    private final String orderId;
    private final long execQty;
    private final long execPrice;
    private final Instant execTime;

    public ExecutionReport(String execId, String orderId, long execQty, long execPrice, Instant execTime) {
        this.execId = Objects.requireNonNull(execId, "execId");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.execQty = execQty;
        this.execPrice = execPrice;
        this.execTime = Objects.requireNonNull(execTime, "execTime");
    }

    public String getExecId() {
        return execId;
    }

    public String getOrderId() {
        return orderId;
    }

    public long getExecQty() {
        return execQty;
    }

    public long getExecPrice() {
        return execPrice;
    }

    public Instant getExecTime() {
        return execTime;
    }

    @Override
    public String toString() {
        return "ExecutionReport{execId=" + execId + ", orderId=" + orderId
                + ", execQty=" + execQty + ", execPrice=" + execPrice + ", execTime=" + execTime + '}';
    }
}
