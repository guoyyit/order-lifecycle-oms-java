package com.github.guoyyit.orderlifecycleomsjava.domain;

import com.github.guoyyit.orderlifecycleomsjava.statemachine.OrderStateMachine;
import com.github.guoyyit.orderlifecycleomsjava.statemachine.TransitionResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 订单聚合根。
 *
 * 所有变更方法必须由外部按订单维度串行调用（由 OrderService 的订单锁保证），
 * 本类内部不做并发控制；读取通过 {@link #snapshot()} 产出不可变快照。
 */
public final class Order {

    private final String orderId;
    private OrderStatus status;
    private long orderQty;
    private long filledQty;
    private long version;
    private final List<ExecutionReport> fills = new ArrayList<>();
    private final Set<String> acceptedExecIds = new LinkedHashSet<>();

    public Order(String orderId, long orderQty) {
        this.orderId = orderId;
        this.orderQty = orderQty;
        this.status = OrderStatus.ACCEPTED;
        this.filledQty = 0;
        this.version = 0;
    }

    // ---- 查询 ----

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

    public long getLeavesQty() {
        return orderQty - filledQty;
    }

    public long getVersion() {
        return version;
    }

    public boolean hasAccepted(String execId) {
        return acceptedExecIds.contains(execId);
    }

    /** 生成当前时刻的不可变快照。 */
    public OrderSnapshot snapshot() {
        return new OrderSnapshot(orderId, status, orderQty, filledQty, version, fills);
    }

    // ---- 变更 ----

    /** 标记订单已报。 */
    public TransitionResult markReported() {
        TransitionResult result = OrderStateMachine.check(status, OrderStatus.REPORTED);
        if (result.isAccepted()) {
            transitionTo(OrderStatus.REPORTED);
        }
        return result;
    }

    /**
     * 应用一条成交回报。
     *
     * 去重：同一 execId 只累计一次；迟到：终态后到达的回报拒绝；
     * 数量：<=0 或超过剩余未成交量的回报拒绝（由服务层隔离）。
     */
    public TransitionResult applyExecution(ExecutionReport report) {
        if (acceptedExecIds.contains(report.getExecId())) {
            return TransitionResult.rejected(RejectReason.DUPLICATE_EXECUTION,
                    "execId " + report.getExecId() + " already accepted");
        }
        if (status.isTerminal()) {
            return TransitionResult.rejected(RejectReason.LATE_EXECUTION_AFTER_TERMINAL,
                    "order " + orderId + " is terminal(" + status + "), late exec "
                            + report.getExecId() + " rejected");
        }
        if (report.getExecQty() <= 0 || report.getExecQty() > getLeavesQty()) {
            return TransitionResult.rejected(RejectReason.INVALID_EXECUTION_QUANTITY,
                    "execQty " + report.getExecQty() + " invalid, leavesQty=" + getLeavesQty());
        }
        long newFilled = filledQty + report.getExecQty();
        OrderStatus target = (newFilled == orderQty) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        TransitionResult result = OrderStateMachine.check(status, target);
        if (!result.isAccepted()) {
            return result;
        }
        filledQty = newFilled;
        fills.add(report);
        acceptedExecIds.add(report.getExecId());
        transitionTo(target);
        return TransitionResult.ok(target);
    }

    /** 撤销订单。部分成交状态下允许撤销，已成交量保留。 */
    public TransitionResult cancel() {
        TransitionResult result = OrderStateMachine.check(status, OrderStatus.CANCELLED);
        if (result.isAccepted()) {
            transitionTo(OrderStatus.CANCELLED);
        }
        return result;
    }

    /**
     * 改单：仅允许改小数量，且新数量不得小于已成交量。
     * 若新数量恰好等于已成交量，订单直接全部成交终结。
     */
    public TransitionResult amend(long newOrderQty) {
        if (status.isTerminal()) {
            return TransitionResult.rejected(RejectReason.ORDER_ALREADY_TERMINAL,
                    "order " + orderId + " is terminal(" + status + "), cannot amend");
        }
        if (newOrderQty <= 0 || newOrderQty < filledQty || newOrderQty >= orderQty) {
            return TransitionResult.rejected(RejectReason.INVALID_AMEND_QUANTITY,
                    "newOrderQty " + newOrderQty + " invalid, filledQty=" + filledQty
                            + ", orderQty=" + orderQty);
        }
        this.orderQty = newOrderQty;
        if (newOrderQty == filledQty) {
            transitionTo(OrderStatus.FILLED);
            return TransitionResult.ok(OrderStatus.FILLED);
        }
        version++;
        return TransitionResult.ok(status);
    }

    private void transitionTo(OrderStatus target) {
        this.status = target;
        this.version++;
    }
}
