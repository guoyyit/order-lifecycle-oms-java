package com.github.guoyyit.orderlifecycleomsjava.service;

import com.github.guoyyit.orderlifecycleomsjava.domain.ExecutionReport;
import com.github.guoyyit.orderlifecycleomsjava.domain.RejectReason;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 异常回报隔离区。
 *
 * 被判定为异常的回报（数量非法、订单不存在等）不应用到订单，
 * 而是进入隔离区，避免污染订单状态；支持后续查询与重放。
 */
@Repository
public class QuarantineStore {

    private final CopyOnWriteArrayList<QuarantinedReport> quarantined = new CopyOnWriteArrayList<>();

    public void quarantine(ExecutionReport report, RejectReason reason, String detail) {
        quarantined.add(new QuarantinedReport(report, reason, detail));
    }

    public List<QuarantinedReport> listAll() {
        return List.copyOf(quarantined);
    }

    public List<QuarantinedReport> listByOrder(String orderId) {
        return quarantined.stream()
                .filter(q -> q.getReport().getOrderId().equals(orderId))
                .toList();
    }

    public void clear() {
        quarantined.clear();
    }
}
