package com.github.guoyyit.orderlifecycleomsjava.service;

import java.util.List;

/**
 * 单订单对账结果。
 *
 * consistent=true 表示聚合结果与全部已接受回报一致；
 * 不一致时 diagnostics 给出可定位的诊断信息（哪条回报、期望值、实际值）。
 */
public final class ReconcileReport {

    private final String orderId;
    private final boolean consistent;
    private final long orderQty;
    private final long filledQty;
    private final long leavesQty;
    private final long sumOfFills;
    private final int fillCount;
    private final List<String> diagnostics;

    public ReconcileReport(String orderId, boolean consistent, long orderQty, long filledQty,
                           long leavesQty, long sumOfFills, int fillCount, List<String> diagnostics) {
        this.orderId = orderId;
        this.consistent = consistent;
        this.orderQty = orderQty;
        this.filledQty = filledQty;
        this.leavesQty = leavesQty;
        this.sumOfFills = sumOfFills;
        this.fillCount = fillCount;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public String getOrderId() {
        return orderId;
    }

    public boolean isConsistent() {
        return consistent;
    }

    public long getOrderQty() {
        return orderQty;
    }

    public long getFilledQty() {
        return filledQty;
    }

    public long getLeavesQty() {
        return leavesQty;
    }

    /** 全部已接受回报的成交量之和。 */
    public long getSumOfFills() {
        return sumOfFills;
    }

    public int getFillCount() {
        return fillCount;
    }

    /** 不一致时的可定位诊断信息；一致时为空列表。 */
    public List<String> getDiagnostics() {
        return diagnostics;
    }

    @Override
    public String toString() {
        return "ReconcileReport{orderId=" + orderId + ", consistent=" + consistent
                + ", orderQty=" + orderQty + ", filledQty=" + filledQty
                + ", leavesQty=" + leavesQty + ", sumOfFills=" + sumOfFills
                + ", fillCount=" + fillCount
                + (diagnostics.isEmpty() ? "" : ", diagnostics=" + diagnostics) + '}';
    }
}
