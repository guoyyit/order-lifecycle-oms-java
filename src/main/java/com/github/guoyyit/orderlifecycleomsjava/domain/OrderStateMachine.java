package com.github.guoyyit.orderlifecycleomsjava.domain;

import com.github.guoyyit.orderlifecycleomsjava.exception.OrderStateException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus.*;

/**
 * 订单状态机：集中声明合法状态迁移，非法迁移被拒绝并给出可区分原因。
 *
 * <pre>
 * ACCEPTED        -> NEW | REJECTED
 * NEW             -> PARTIALLY_FILLED | FILLED | CANCELLED | REJECTED
 * PARTIALLY_FILLED-> PARTIALLY_FILLED | FILLED | CANCELLED
 * CANCELLED       -> CANCELLED | FILLED   （撤销窗口内的迟到成交可补到全部成交）
 * FILLED          -> （终结）
 * REJECTED        -> （终结）
 * </pre>
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(ACCEPTED, EnumSet.of(NEW, REJECTED));
        ALLOWED.put(NEW, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED));
        ALLOWED.put(PARTIALLY_FILLED, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED));
        // 撤销后仍允许 CANCELLED（撤销窗口内迟到成交、但未补足全部数量，状态不变）
        // 以及 FILLED（撤销窗口内的迟到成交恰好补足全部数量，则业务上应判定为全部成交）
        ALLOWED.put(CANCELLED, EnumSet.of(CANCELLED, FILLED));
        ALLOWED.put(FILLED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(REJECTED, EnumSet.noneOf(OrderStatus.class));
    }

    private OrderStateMachine() {
    }

    public static boolean canTransit(OrderStatus from, OrderStatus to) {
        return from == to || ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)).contains(to);
    }

    /**
     * 校验迁移；from == to 视为幂等同态，非法迁移抛出携带 ILLEGAL_TRANSITION 的异常。
     */
    public static void requireTransit(OrderStatus from, OrderStatus to) {
        if (!canTransit(from, to)) {
            throw OrderStateException.illegalTransition(from, to);
        }
    }
}
