package com.github.guoyyit.orderlifecycleomsjava.service;

import com.github.guoyyit.orderlifecycleomsjava.domain.OrderView;
import com.github.guoyyit.orderlifecycleomsjava.store.OrderStore;
import org.springframework.stereotype.Service;

/**
 * 订单查询服务。
 *
 * <p>读取直接返回 {@link com.github.guoyyit.orderlifecycleomsjava.domain.Order#snapshot()}：
 * 状态、已成交量与成交明细在同一把锁内一次性拷出，保证来自同一完整版本，读不到中间态。
 */
@Service
public class OrderQueryService {

    private final OrderStore store;

    public OrderQueryService(OrderStore store) {
        this.store = store;
    }

    public OrderView get(String orderId) {
        return store.getRequired(orderId).snapshot();
    }
}
