package com.github.guoyyit.orderlifecycleomsjava.repository;

import com.github.guoyyit.orderlifecycleomsjava.domain.Order;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 内存订单仓储。
 *
 * 按订单维度提供互斥锁：同一订单的变更串行执行，不同订单之间可并行。
 */
@Repository
public class OrderRepository {

    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 订单不存在时创建（初始状态 ACCEPTED）。 */
    public Order createIfAbsent(String orderId, long orderQty) {
        return orders.computeIfAbsent(orderId, id -> new Order(id, orderQty));
    }

    public Optional<Order> find(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    /**
     * 在订单锁内执行变更/读取，保证同一订单的操作互斥。
     */
    public <T> T withOrderLock(String orderId, Function<Optional<Order>, T> action) {
        ReentrantLock lock = locks.computeIfAbsent(orderId, id -> new ReentrantLock());
        lock.lock();
        try {
            return action.apply(find(orderId));
        } finally {
            lock.unlock();
        }
    }
}
