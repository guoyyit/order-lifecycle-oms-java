package com.github.guoyyit.orderlifecycleomsjava.domain;

import java.util.List;

/**
 * 订单某一时刻的不可变快照。
 *
 * 并发读取时返回快照，保证读到的状态与成交明细来自同一版本，不会出现中间态。
 */
public final class OrderSnapshot {

    private final String orderId;
    private final OrderStatus status;
    private final long orderQty;
    private final long filledQty;
    private final long version;
    private final List<ExecutionReport> fills;

    public OrderSnapshot(String orderId, OrderStatus status, long orderQty, long filledQty,
                         long version, List<ExecutionReport> fills) {
        this.orderId = orderId;
        this.status = status;
        this.orderQty = orderQty;
        this.filledQty = filledQty;
        this.version = version;
        this.fills = List.copyOf(fills);
    }

    public String getOrderId() {
        return orderId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getOrderQty() {
        return orderQty;
    }

    public long getFilledQty() {
        return filledQty;
    }

    /** 未成交量 = 委托数量 - 累计成交量。 */
    public long getLeavesQty() {
        return orderQty - filledQty;
    }

    /** 单调递增的版本号，每次状态/成交变更 +1。 */
    public long getVersion() {
        return version;
    }

    /** 已接受的成交明细（不可变列表）。 */
    public List<ExecutionReport> getFills() {
        return fills;
    }

    @Override
    public String toString() {
        return "OrderSnapshot{orderId=" + orderId + ", status=" + status
                + ", orderQty=" + orderQty + ", filledQty=" + filledQty
                + ", leavesQty=" + getLeavesQty() + ", version=" + version
                + ", fills=" + fills.size() + '}';
    }
}
