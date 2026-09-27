package com.github.guoyyit.orderlifecycleomsjava.service;

import com.github.guoyyit.orderlifecycleomsjava.domain.Order;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderView;
import com.github.guoyyit.orderlifecycleomsjava.store.OrderStore;
import org.springframework.stereotype.Service;

/**
 * 订单命令服务：受理、报出、拒绝、撤销、改单。
 *
 * <p>每个命令先定位订单，再进入订单自身锁执行；与成交回报并发时由订单锁保证最终状态确定。
 */
@Service
public class OrderCommandService {

    private final OrderStore store;

    public OrderCommandService(OrderStore store) {
        this.store = store;
    }

    public OrderView accept(String orderId, long orderQty) {
        return store.create(orderId, orderQty).snapshot();
    }

    public OrderView markNew(String orderId) {
        return store.getRequired(orderId).markNew();
    }

    public OrderView reject(String orderId, String detail) {
        return store.getRequired(orderId).reject(detail);
    }

    public OrderView cancel(String orderId, String requestId, long effectiveSeq) {
        Order order = store.getRequired(orderId);
        return order.cancel(requestId, effectiveSeq);
    }

    public OrderView replace(String orderId, String requestId, long effectiveSeq, long newTotalQty) {
        Order order = store.getRequired(orderId);
        return order.replace(requestId, effectiveSeq, newTotalQty);
    }
}
