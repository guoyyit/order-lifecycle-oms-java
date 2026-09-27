package com.github.guoyyit.orderlifecycleomsjava.store;

import com.github.guoyyit.orderlifecycleomsjava.domain.Order;
import com.github.guoyyit.orderlifecycleomsjava.domain.RejectReason;
import com.github.guoyyit.orderlifecycleomsjava.exception.OrderStateException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存订单存储。
 *
 * <p>{@link ConcurrentHashMap#computeIfAbsent} 保证同一 orderId 只创建一次；
 * 单订单内部变更由 {@link Order} 自身锁串行完成，跨订单互不阻塞。
 */
public class OrderStore {

    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();

    public Order create(String orderId, long orderQty) {
        Order created = new Order(orderId, orderQty);
        Order existing = orders.putIfAbsent(orderId, created);
        if (existing != null) {
            throw new OrderStateException(RejectReason.DUPLICATE_REQUEST,
                    "order already exists: " + orderId);
        }
        return created;
    }

    public Optional<Order> find(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    public Order getRequired(String orderId) {
        return find(orderId).orElseThrow(() -> new OrderStateException(
                RejectReason.UNKNOWN_ORDER, "unknown order: " + orderId));
    }

    public List<Order> all() {
        return List.copyOf(orders.values());
    }
}
