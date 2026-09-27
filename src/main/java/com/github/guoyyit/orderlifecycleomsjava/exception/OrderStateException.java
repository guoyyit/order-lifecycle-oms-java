package com.github.guoyyit.orderlifecycleomsjava.exception;

import com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus;
import com.github.guoyyit.orderlifecycleomsjava.domain.RejectReason;

/**
 * 非法订单状态迁移等拒绝场景，携带可区分原因。
 */
public class OrderStateException extends RuntimeException {

    private final RejectReason reason;

    public OrderStateException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }

    public static OrderStateException illegalTransition(OrderStatus from, OrderStatus to) {
        return new OrderStateException(RejectReason.ILLEGAL_TRANSITION,
                "illegal transition: " + from + " -> " + to);
    }
}
