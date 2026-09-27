package com.github.guoyyit.orderlifecycleomsjava.service;

import com.github.guoyyit.orderlifecycleomsjava.domain.ExecutionReport;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderSnapshot;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 对账服务：按订单聚合成交量与未成交量，并与全部已接受回报核对。
 *
 * 任何时刻聚合结果必须与已接受回报一致；不一致时给出可定位的诊断信息。
 */
@Service
public class ReconcileService {

    private final OrderService orderService;

    public ReconcileService(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 对单个订单执行对账。
     *
     * 核对项：
     * 1. 累计成交量 == 全部已接受回报成交量之和；
     * 2. 未成交量 == 委托数量 - 累计成交量；
     * 3. 已接受回报的 execId 无重复；
     * 4. 状态与成交量一致（满量必为 FILLED，有量非终态必为 PARTIALLY_FILLED）。
     */
    public Optional<ReconcileReport> reconcile(String orderId) {
        return orderService.getSnapshot(orderId).map(snapshot -> {
            List<String> diagnostics = new ArrayList<>();

            long sumOfFills = 0;
            Set<String> seenExecIds = new HashSet<>();
            for (ExecutionReport fill : snapshot.getFills()) {
                sumOfFills += fill.getExecQty();
                if (!seenExecIds.add(fill.getExecId())) {
                    diagnostics.add("duplicate execId in fills: " + fill.getExecId());
                }
            }

            if (sumOfFills != snapshot.getFilledQty()) {
                diagnostics.add("filledQty=" + snapshot.getFilledQty()
                        + " but sum of accepted fills=" + sumOfFills
                        + ", diff=" + (snapshot.getFilledQty() - sumOfFills));
            }
            if (snapshot.getLeavesQty() != snapshot.getOrderQty() - snapshot.getFilledQty()) {
                diagnostics.add("leavesQty=" + snapshot.getLeavesQty()
                        + " != orderQty(" + snapshot.getOrderQty()
                        + ") - filledQty(" + snapshot.getFilledQty() + ")");
            }
            if (snapshot.getFilledQty() == snapshot.getOrderQty()
                    && snapshot.getStatus() != OrderStatus.FILLED) {
                diagnostics.add("filledQty == orderQty but status=" + snapshot.getStatus()
                        + ", expected FILLED");
            }
            if (snapshot.getFilledQty() > 0
                    && snapshot.getFilledQty() < snapshot.getOrderQty()
                    && !snapshot.getStatus().isTerminal()
                    && snapshot.getStatus() != OrderStatus.PARTIALLY_FILLED) {
                diagnostics.add("0 < filledQty < orderQty but status=" + snapshot.getStatus()
                        + ", expected PARTIALLY_FILLED");
            }

            return new ReconcileReport(orderId, diagnostics.isEmpty(),
                    snapshot.getOrderQty(), snapshot.getFilledQty(), snapshot.getLeavesQty(),
                    sumOfFills, snapshot.getFills().size(), diagnostics);
        });
    }
}
