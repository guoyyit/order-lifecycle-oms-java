package com.github.guoyyit.orderlifecycleomsjava.domain;

import com.github.guoyyit.orderlifecycleomsjava.exception.OrderStateException;
import org.junit.jupiter.api.Test;

import static com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderStateMachineTest {

    @Test
    void allowsDeclaredTransitions() {
        assertTrue(OrderStateMachine.canTransit(ACCEPTED, NEW));
        assertTrue(OrderStateMachine.canTransit(ACCEPTED, REJECTED));
        assertTrue(OrderStateMachine.canTransit(NEW, PARTIALLY_FILLED));
        assertTrue(OrderStateMachine.canTransit(NEW, FILLED));
        assertTrue(OrderStateMachine.canTransit(NEW, CANCELLED));
        assertTrue(OrderStateMachine.canTransit(NEW, REJECTED));
        assertTrue(OrderStateMachine.canTransit(PARTIALLY_FILLED, PARTIALLY_FILLED));
        assertTrue(OrderStateMachine.canTransit(PARTIALLY_FILLED, FILLED));
        assertTrue(OrderStateMachine.canTransit(PARTIALLY_FILLED, CANCELLED));
        assertTrue(OrderStateMachine.canTransit(CANCELLED, FILLED));
    }

    @Test
    void sameStateIsIdempotent() {
        assertTrue(OrderStateMachine.canTransit(FILLED, FILLED));
        assertDoesNotThrow(() -> OrderStateMachine.requireTransit(FILLED, FILLED));
    }

    @Test
    void rejectsIllegalTransitionsWithDistinguishableReason() {
        assertIllegal(ACCEPTED, CANCELLED);
        assertIllegal(FILLED, CANCELLED);
        assertIllegal(FILLED, NEW);
        assertIllegal(REJECTED, NEW);
        assertIllegal(REJECTED, FILLED);
        assertIllegal(CANCELLED, NEW);
        assertIllegal(CANCELLED, REJECTED);
    }

    private void assertIllegal(OrderStatus from, OrderStatus to) {
        OrderStateException ex = assertThrows(OrderStateException.class,
                () -> OrderStateMachine.requireTransit(from, to));
        assertEquals(RejectReason.ILLEGAL_TRANSITION, ex.reason());
        assertTrue(ex.getMessage().contains(from.name()));
        assertTrue(ex.getMessage().contains(to.name()));
    }
}
