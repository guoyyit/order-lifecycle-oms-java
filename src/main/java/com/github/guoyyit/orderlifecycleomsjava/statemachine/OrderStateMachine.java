package com.github.guoyyit.orderlifecycleomsjava.statemachine;

import com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus;
import com.github.guoyyit.orderlifecycleomsjava.domain.RejectReason;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机：定义合法的状态迁移表，非法迁移返回可区分原因。
 *
 * 合法迁移：
 *   ACCEPTED          -> REPORTED, CANCELLED, REJECTED
 *   REPORTED          -> PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED
 *   PARTIALLY_FILLED  -> PARTIALLY_FILLED(自迁移，继续成交), FILLED, CANCELLED
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        TRANSITIONS.put(OrderStatus.ACCEPTED,
                EnumSet.of(OrderStatus.REPORTED, OrderStatus.CANCELLED, OrderStatus.REJECTED));
        TRANSITIONS.put(OrderStatus.REPORTED,
                EnumSet.of(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED,
                        OrderStatus.CANCELLED, OrderStatus.REJECTED));
        TRANSITIONS.put(OrderStatus.PARTIALLY_FILLED,
                EnumSet.of(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.CANCELLED));
        TRANSITIONS.put(OrderStatus.FILLED, EnumSet.noneOf(OrderStatus.class));
        TRANSITIONS.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
        TRANSITIONS.put(OrderStatus.REJECTED, EnumSet.noneOf(OrderStatus.class));
    }

    private OrderStateMachine() {
    }

    /**
     * 校验从 from 到 to 的迁移是否合法。
     *
     * @return 合法返回 ok(to)，非法返回带原因的 rejected
     */
    public static TransitionResult check(OrderStatus from, OrderStatus to) {
        if (from.isTerminal()) {
            return TransitionResult.rejected(RejectReason.ORDER_ALREADY_TERMINAL,
                    "order is in terminal state " + from + ", cannot transit to " + to);
        }
        if (!TRANSITIONS.get(from).contains(to)) {
            return TransitionResult.rejected(RejectReason.ILLEGAL_STATE_TRANSITION,
                    "transition " + from + " -> " + to + " is not allowed");
        }
        return TransitionResult.ok(to);
    }

    public static boolean canTransit(OrderStatus from, OrderStatus to) {
        return !from.isTerminal() && TRANSITIONS.get(from).contains(to);
    }
}
