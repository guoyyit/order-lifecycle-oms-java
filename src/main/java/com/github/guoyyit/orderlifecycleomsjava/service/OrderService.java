package com.github.guoyyit.orderlifecycleomsjava.service;

import com.github.guoyyit.orderlifecycleomsjava.domain.ExecutionReport;
import com.github.guoyyit.orderlifecycleomsjava.domain.Order;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderSnapshot;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus;
import com.github.guoyyit.orderlifecycleomsjava.domain.RejectReason;
import com.github.guoyyit.orderlifecycleomsjava.repository.OrderRepository;
import com.github.guoyyit.orderlifecycleomsjava.statemachine.TransitionResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 订单服务：受理订单、处理成交回报、撤销、改单、查询与重放。
 *
 * 所有订单变更在订单维度锁内串行执行；查询返回不可变快照。
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final QuarantineStore quarantineStore;

    public OrderService(OrderRepository orderRepository, QuarantineStore quarantineStore) {
        this.orderRepository = orderRepository;
        this.quarantineStore = quarantineStore;
    }

    /** 受理新订单（初始状态 ACCEPTED）。 */
    public TransitionResult acceptOrder(String orderId, long orderQty) {
        if (orderQty <= 0) {
            return TransitionResult.rejected(RejectReason.INVALID_AMEND_QUANTITY,
                    "orderQty must be positive, got " + orderQty);
        }
        return orderRepository.withOrderLock(orderId, existing -> {
            if (existing.isPresent()) {
                return TransitionResult.rejected(RejectReason.STATE_CONFLICT,
                        "order " + orderId + " already exists");
            }
            orderRepository.createIfAbsent(orderId, orderQty);
            return TransitionResult.ok(OrderStatus.ACCEPTED);
        });
    }

    /** 标记订单已报。 */
    public TransitionResult markReported(String orderId) {
        return orderRepository.withOrderLock(orderId, existing -> existing
                .map(Order::markReported)
                .orElseGet(() -> TransitionResult.rejected(RejectReason.ORDER_NOT_FOUND,
                        "order " + orderId + " not found")));
    }

    /**
     * 处理一条成交回报。
     *
     * 重复回报（execId 已接受）幂等拒绝；终态后迟到的回报按规则拒绝；
     * 数量非法的回报进入隔离区，不污染订单状态。
     */
    public TransitionResult onExecutionReport(ExecutionReport report) {
        return orderRepository.withOrderLock(report.getOrderId(), existing -> {
            if (existing.isEmpty()) {
                quarantineStore.quarantine(report, RejectReason.ORDER_NOT_FOUND,
                        "execution for unknown order " + report.getOrderId());
                return TransitionResult.rejected(RejectReason.ORDER_NOT_FOUND,
                        "order " + report.getOrderId() + " not found, report quarantined");
            }
            TransitionResult result = existing.get().applyExecution(report);
            if (!result.isAccepted() && result.getRejectReason() == RejectReason.INVALID_EXECUTION_QUANTITY) {
                quarantineStore.quarantine(report, result.getRejectReason(), result.getDetail());
                return TransitionResult.rejected(RejectReason.QUARANTINED, result.getDetail());
            }
            return result;
        });
    }

    /** 撤销订单（部分成交状态下也可撤销）。 */
    public TransitionResult cancel(String orderId) {
        return orderRepository.withOrderLock(orderId, existing -> existing
                .map(Order::cancel)
                .orElseGet(() -> TransitionResult.rejected(RejectReason.ORDER_NOT_FOUND,
                        "order " + orderId + " not found")));
    }

    /** 改单：仅允许改小数量，且不得小于已成交量。 */
    public TransitionResult amend(String orderId, long newOrderQty) {
        return orderRepository.withOrderLock(orderId, existing -> existing
                .map(o -> o.amend(newOrderQty))
                .orElseGet(() -> TransitionResult.rejected(RejectReason.ORDER_NOT_FOUND,
                        "order " + orderId + " not found")));
    }

    /** 查询订单当前快照（与成交明细来自同一版本）。 */
    public Optional<OrderSnapshot> getSnapshot(String orderId) {
        return orderRepository.withOrderLock(orderId, existing -> existing.map(Order::snapshot));
    }

    /**
     * 重放该订单全部已接受回报，验证幂等性：重放不得改变最终结果。
     *
     * @return 每条回报重放的结果（应全部为 DUPLICATE_EXECUTION 拒绝）
     */
    public List<TransitionResult> replayAcceptedExecutions(String orderId) {
        return orderRepository.withOrderLock(orderId, existing -> {
            if (existing.isEmpty()) {
                return List.of(TransitionResult.rejected(RejectReason.ORDER_NOT_FOUND,
                        "order " + orderId + " not found"));
            }
            Order order = existing.get();
            List<ExecutionReport> fills = order.snapshot().getFills();
            List<TransitionResult> results = new ArrayList<>(fills.size());
            for (ExecutionReport fill : fills) {
                results.add(order.applyExecution(fill));
            }
            return results;
        });
    }
}
