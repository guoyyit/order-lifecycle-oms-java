package com.github.guoyyit.orderlifecycleomsjava.service;

import com.github.guoyyit.orderlifecycleomsjava.domain.FillRecord;
import com.github.guoyyit.orderlifecycleomsjava.domain.Order;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderView;
import com.github.guoyyit.orderlifecycleomsjava.domain.ReconcileReport;
import com.github.guoyyit.orderlifecycleomsjava.store.OrderStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 对账服务。
 *
 * <p>基于订单同一版本快照，核对以下不变量：
 * <ol>
 *   <li>聚合口径一致：sum(已接受回报.lastQty) == 订单 filledQty；</li>
 *   <li>数量边界：filledQty &lt;= orderQty，remainingQty == orderQty - filledQty；</li>
 *   <li>状态一致：FILLED 必须 filledQty == orderQty；PARTIALLY_FILLED 必须 0 &lt; filledQty &lt; orderQty；
 *       CANCELLED 必须 filledQty &lt; orderQty；NEW/ACCEPTED 必须 filledQty == 0；
 *       REJECTED 必须 filledQty == 0（拒绝后不可能有成交）；</li>
 *   <li>明细唯一：成交明细 execId 无重复。</li>
 * </ol>
 * 任一不满足都会在 diagnostics 中给出可定位到订单与具体数值的信息。
 */
@Service
public class ReconciliationService {

    private final OrderStore store;

    public ReconciliationService(OrderStore store) {
        this.store = store;
    }

    public ReconcileReport reconcile(String orderId) {
        return reconcile(store.getRequired(orderId));
    }

    public List<ReconcileReport> reconcileAll() {
        List<ReconcileReport> reports = new ArrayList<>();
        for (Order order : store.all()) {
            reports.add(reconcile(order));
        }
        return reports;
    }

    private ReconcileReport reconcile(Order order) {
        OrderView v = order.snapshot();
        List<String> diagnostics = new ArrayList<>();

        long sumLastQty = v.fills().stream().mapToLong(FillRecord::lastQty).sum();
        long distinctExecIds = v.fills().stream().map(FillRecord::execId).distinct().count();
        if (distinctExecIds != v.fills().size()) {
            diagnostics.add("[" + v.orderId() + "] duplicate execId detected among "
                    + v.fills().size() + " accepted fills");
        }
        if (sumLastQty != v.filledQty()) {
            diagnostics.add("[" + v.orderId() + "] aggregate mismatch: sum(accepted lastQty)="
                    + sumLastQty + " but filledQty=" + v.filledQty());
        }
        if (v.filledQty() > v.orderQty()) {
            diagnostics.add("[" + v.orderId() + "] quantity exceeded: filledQty="
                    + v.filledQty() + " > orderQty=" + v.orderQty());
        }
        long expectedRemaining = v.orderQty() - v.filledQty();
        if (v.remainingQty() != expectedRemaining) {
            diagnostics.add("[" + v.orderId() + "] remaining mismatch: stored="
                    + v.remainingQty() + " expected=" + expectedRemaining);
        }

        OrderStatus s = v.status();
        if (s == OrderStatus.FILLED && v.filledQty() != v.orderQty()) {
            diagnostics.add("[" + v.orderId() + "] status FILLED but filledQty="
                    + v.filledQty() + " != orderQty=" + v.orderQty());
        }
        if (s == OrderStatus.PARTIALLY_FILLED
                && !(v.filledQty() > 0 && v.filledQty() < v.orderQty())) {
            diagnostics.add("[" + v.orderId() + "] status PARTIALLY_FILLED but filledQty="
                    + v.filledQty() + ", orderQty=" + v.orderQty());
        }
        if (s == OrderStatus.CANCELLED && v.filledQty() >= v.orderQty()) {
            diagnostics.add("[" + v.orderId() + "] status CANCELLED but filledQty="
                    + v.filledQty() + " >= orderQty=" + v.orderQty() + " (should be FILLED)");
        }
        if ((s == OrderStatus.NEW || s == OrderStatus.ACCEPTED) && v.filledQty() != 0) {
            diagnostics.add("[" + v.orderId() + "] status " + s + " but filledQty="
                    + v.filledQty() + " != 0");
        }
        if (s == OrderStatus.REJECTED && v.filledQty() != 0) {
            diagnostics.add("[" + v.orderId() + "] status REJECTED but filledQty="
                    + v.filledQty() + " != 0");
        }

        return new ReconcileReport(v.orderId(), diagnostics.isEmpty(), s, v.orderQty(),
                sumLastQty, v.filledQty(), v.remainingQty(), v.cancelEffectiveSeq(), diagnostics);
    }
}
