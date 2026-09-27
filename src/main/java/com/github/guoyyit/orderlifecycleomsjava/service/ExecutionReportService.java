package com.github.guoyyit.orderlifecycleomsjava.service;

import com.github.guoyyit.orderlifecycleomsjava.domain.ExecReport;
import com.github.guoyyit.orderlifecycleomsjava.domain.FillDecision;
import com.github.guoyyit.orderlifecycleomsjava.domain.FillOutcome;
import com.github.guoyyit.orderlifecycleomsjava.domain.FillRejectReason;
import com.github.guoyyit.orderlifecycleomsjava.domain.Order;
import com.github.guoyyit.orderlifecycleomsjava.domain.QuarantineEntry;
import com.github.guoyyit.orderlifecycleomsjava.store.OrderStore;
import com.github.guoyyit.orderlifecycleomsjava.store.QuarantineStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 成交回报处理服务。
 *
 * <p>乱序/重复/迟到的全部确定性规则在 {@link Order#applyFill} 内、订单自身锁上完成；
 * 本服务负责报文级校验、未知订单与非法回报的隔离，以及隔离区的重放。
 *
 * <p>被隔离的异常回报绝不进入订单聚合；重放时重新走同一套规则：
 * 已接受回报通过 execId 去重保证幂等，重放不会改变最终结果。
 */
@Service
public class ExecutionReportService {

    private final OrderStore orders;
    private final QuarantineStore quarantine;

    public ExecutionReportService(OrderStore orders, QuarantineStore quarantine) {
        this.orders = orders;
        this.quarantine = quarantine;
    }

    public FillDecision process(ExecReport report) {
        if (report == null) {
            throw new IllegalArgumentException("exec report must not be null");
        }
        // 报文级非法：无 execId 无法去重、负 seq 无法判定先后，隔离且不触碰订单
        if (isBlank(report.execId()) || report.seq() < 0) {
            return doQuarantine(report, FillRejectReason.INVALID_QUANTITY,
                    "missing execId or negative seq");
        }
        Optional<Order> orderOpt = orders.find(report.orderId());
        if (orderOpt.isEmpty()) {
            // 未知订单先隔离，订单后续出现后可重放
            return doQuarantine(report, FillRejectReason.UNKNOWN_ORDER,
                    "unknown order: " + report.orderId());
        }
        FillDecision decision = orderOpt.get().applyFill(report);
        if (decision.outcome() == FillOutcome.QUARANTINED) {
            quarantine.add(new QuarantineEntry(report, decision.rejectReason(),
                    decision.detail(), Instant.now()));
        }
        return decision;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private FillDecision doQuarantine(ExecReport report, FillRejectReason reason, String detail) {
        quarantine.add(new QuarantineEntry(report, reason, detail, Instant.now()));
        return FillDecision.quarantine(report.orderId(), report.execId(), reason, detail);
    }

    /**
     * 重放某订单的全部隔离回报。仍不合法的继续留在隔离区；被接受或被确定性拒绝的移走。
     *
     * @return 每条隔离回报重放后的决策（顺序为重放时隔离区的快照顺序）
     */
    public List<FillDecision> replayQuarantine(String orderId) {
        List<QuarantineEntry> snapshot = new ArrayList<>(quarantine.byOrder(orderId));
        List<FillDecision> decisions = new ArrayList<>();
        for (QuarantineEntry entry : snapshot) {
            // 先摘除旧条目，避免仍被隔离时产生重复条目；处理后若仍隔离再由 process 重新加入
            quarantine.remove(entry);
            FillDecision decision = process(entry.report());
            decisions.add(decision);
        }
        return decisions;
    }
}
