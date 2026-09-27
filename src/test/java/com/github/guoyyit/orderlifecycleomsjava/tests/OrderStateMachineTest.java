package com.github.guoyyit.orderlifecycleomsjava.tests;

import com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus;
import com.github.guoyyit.orderlifecycleomsjava.domain.RejectReason;
import com.github.guoyyit.orderlifecycleomsjava.statemachine.OrderStateMachine;
import com.github.guoyyit.orderlifecycleomsjava.statemachine.TransitionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态机迁移规则测试：合法迁移放行，非法迁移拒绝并给出可区分原因。
 */
class OrderStateMachineTest {

    @Test
    void legalTransitionsAreAccepted() {
        assertTrue(OrderStateMachine.check(OrderStatus.ACCEPTED, OrderStatus.REPORTED).isAccepted());
        assertTrue(OrderStateMachine.check(OrderStatus.ACCEPTED, OrderStatus.CANCELLED).isAccepted());
        assertTrue(OrderStateMachine.check(OrderStatus.ACCEPTED, OrderStatus.REJECTED).isAccepted());

        assertTrue(OrderStateMachine.check(OrderStatus.REPORTED, OrderStatus.PARTIALLY_FILLED).isAccepted());
        assertTrue(OrderStateMachine.check(OrderStatus.REPORTED, OrderStatus.FILLED).isAccepted());
        assertTrue(OrderStateMachine.check(OrderStatus.REPORTED, OrderStatus.CANCELLED).isAccepted());
        assertTrue(OrderStateMachine.check(OrderStatus.REPORTED, OrderStatus.REJECTED).isAccepted());

        assertTrue(OrderStateMachine.check(OrderStatus.PARTIALLY_FILLED, OrderStatus.PARTIALLY_FILLED).isAccepted());
        assertTrue(OrderStateMachine.check(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED).isAccepted());
        assertTrue(OrderStateMachine.check(OrderStatus.PARTIALLY_FILLED, OrderStatus.CANCELLED).isAccepted());
    }

    @Test
    void illegalTransitionsAreRejectedWithReason() {
        TransitionResult r1 = OrderStateMachine.check(OrderStatus.ACCEPTED, OrderStatus.FILLED);
        assertFalse(r1.isAccepted());
        assertEquals(RejectReason.ILLEGAL_STATE_TRANSITION, r1.getRejectReason());

        TransitionResult r2 = OrderStateMachine.check(OrderStatus.ACCEPTED, OrderStatus.PARTIALLY_FILLED);
        assertFalse(r2.isAccepted());
        assertEquals(RejectReason.ILLEGAL_STATE_TRANSITION, r2.getRejectReason());

        TransitionResult r3 = OrderStateMachine.check(OrderStatus.REPORTED, OrderStatus.ACCEPTED);
        assertFalse(r3.isAccepted());
        assertEquals(RejectReason.ILLEGAL_STATE_TRANSITION, r3.getRejectReason());

        TransitionResult r4 = OrderStateMachine.check(OrderStatus.PARTIALLY_FILLED, OrderStatus.REPORTED);
        assertFalse(r4.isAccepted());
        assertEquals(RejectReason.ILLEGAL_STATE_TRANSITION, r4.getRejectReason());
    }

    @Test
    void terminalStatesRejectEverythingWithTerminalReason() {
        for (OrderStatus terminal : new OrderStatus[]{OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.REJECTED}) {
            for (OrderStatus target : OrderStatus.values()) {
                TransitionResult r = OrderStateMachine.check(terminal, target);
                assertFalse(r.isAccepted());
                assertEquals(RejectReason.ORDER_ALREADY_TERMINAL, r.getRejectReason(),
                        terminal + " -> " + target + " should be rejected as terminal");
            }
        }
    }
}
