package com.github.guoyyit.orderlifecycleomsjava.statemachine;

import com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus;
import com.github.guoyyit.orderlifecycleomsjava.domain.RejectReason;

/**
 * 一次状态迁移/事件处理的结果。
 *
 * 成功时携带迁移后的状态；失败时携带可区分的拒绝原因。
 */
public final class TransitionResult {

    private final boolean accepted;
    private final OrderStatus newStatus;
    private final RejectReason rejectReason;
    private final String detail;

    private TransitionResult(boolean accepted, OrderStatus newStatus, RejectReason rejectReason, String detail) {
        this.accepted = accepted;
        this.newStatus = newStatus;
        this.rejectReason = rejectReason;
        this.detail = detail;
    }

    public static TransitionResult ok(OrderStatus newStatus) {
        return new TransitionResult(true, newStatus, null, null);
    }

    public static TransitionResult rejected(RejectReason reason, String detail) {
        return new TransitionResult(false, null, reason, detail);
    }

    public boolean isAccepted() {
        return accepted;
    }

    public OrderStatus getNewStatus() {
        return newStatus;
    }

    public RejectReason getRejectReason() {
        return rejectReason;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return accepted
                ? "TransitionResult{accepted, newStatus=" + newStatus + '}'
                : "TransitionResult{rejected, reason=" + rejectReason + ", detail=" + detail + '}';
    }
}
