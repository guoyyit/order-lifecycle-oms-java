package com.github.guoyyit.orderlifecycleomsjava.domain;

import com.github.guoyyit.orderlifecycleomsjava.exception.OrderStateException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.guoyyit.orderlifecycleomsjava.domain.OrderStatus.*;

/**
 * 订单聚合根。
 *
 * <p>所有状态与成交明细的变更以及 {@link #snapshot()} 读取都在对象内置锁上串行完成，
 * 保证查询看到的状态与成交明细来自同一完整版本，不存在中间态。跨订单之间互不阻塞。
 *
 * <h3>成交回报确定性规则（与到达顺序无关）</h3>
 * <ol>
 *   <li>按 execId 去重：重复回报一律幂等忽略（{@link FillOutcome#DUPLICATE}），不重复累计。</li>
 *   <li>成交量校验：lastQty &gt; 0 且 cumQty &gt;= lastQty，否则隔离（不污染订单）。</li>
 *   <li>超量校验：已接受成交量之和 + lastQty 不得超过当前订单数量，否则隔离（OVER_FILLED）。
 *       交易所正确的单调 cumQty 口径下，乱序回报永远不会超量，只有矛盾数据才会触发。</li>
 *   <li>撤销窗口：订单一旦撤销，记录撤销生效点 cancelEffectiveSeq；
 *       seq &lt;= 生效点的成交发生在撤销生效之前，迟到也必须接受（{@link FillOutcome#LATE_ACCEPTED}），
 *       seq &gt; 生效点的成交确定性拒绝（{@link FillOutcome#LATE_REJECTED}）。</li>
 *   <li>已拒绝订单上的任何成交确定性拒绝（ORDER_REJECTED）。</li>
 * </ol>
 */
public class Order {

    private final String orderId;

    private long orderQty;
    private OrderStatus status = ACCEPTED;
    private long filledQty;
    private long maxAppliedFillSeq;
    private long version;
    private Long cancelEffectiveSeq;
    private String rejectDetail;
    private final Map<String, FillRecord> fillsByExecId = new LinkedHashMap<>();
    private final List<String> decisionLog = new ArrayList<>();
    private final java.util.Set<String> processedRequestIds = new java.util.HashSet<>();

    public Order(String orderId, long orderQty) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (orderQty <= 0) {
            throw new IllegalArgumentException("orderQty must be positive: " + orderQty);
        }
        this.orderId = orderId;
        this.orderQty = orderQty;
        log("order accepted qty=" + orderQty);
    }

    public String orderId() {
        return orderId;
    }

    /** 返回同一时刻的完整版本快照。 */
    public synchronized OrderView snapshot() {
        List<FillRecord> copy = List.copyOf(fillsByExecId.values());
        return new OrderView(orderId, status, orderQty, filledQty, orderQty - filledQty,
                version, cancelEffectiveSeq, null, rejectDetail, copy);
    }

    public synchronized OrderView markNew() {
        OrderStateMachine.requireTransit(status, NEW);
        status = NEW;
        bump("status -> NEW");
        return snapshot();
    }

    public synchronized OrderView reject(String detail) {
        OrderStateMachine.requireTransit(status, REJECTED);
        status = REJECTED;
        rejectDetail = detail;
        bump("order rejected: " + detail);
        return snapshot();
    }

    /**
     * 撤销。同一 requestId 重放为幂等；终结订单拒绝撤销。
     */
    public synchronized OrderView cancel(String requestId, long effectiveSeq) {
        if (requestId != null && !processedRequestIds.add(requestId)) {
            // 同一请求重放：幂等返回当前版本
            log("cancel request " + requestId + " replayed, idempotent");
            return snapshot();
        }
        if (status == FILLED || status == REJECTED) {
            throw new OrderStateException(RejectReason.ORDER_TERMINAL,
                    "order " + orderId + " is " + status + ", cancel is not allowed");
        }
        if (status == CANCELLED) {
            throw new OrderStateException(RejectReason.DUPLICATE_REQUEST,
                    "order " + orderId + " already cancelled at effectiveSeq=" + cancelEffectiveSeq);
        }
        OrderStateMachine.requireTransit(status, CANCELLED);
        // 乱序消息修正：撤销请求的生效点若早于已确认成交的最大 seq（例如高 seq 成交先于
        // 撤销到达），生效点必须推进到已确认成交的最大 seq，保证“已接受成交恒在撤销窗口内”
        // 这一不变量，并保证并发竞争下最终结果确定。
        long effective = Math.max(effectiveSeq, maxAppliedFillSeq);
        cancelEffectiveSeq = effective;
        status = CANCELLED;
        bump("cancel effectiveSeq=" + effective
                + (effective != effectiveSeq ? " (advanced from requested " + effectiveSeq + ")" : "")
                + ", filledAtCancel=" + filledQty);
        return snapshot();
    }

    /**
     * 改单（改数量）。newTotalQty 不得小于已成交量；改单后订单继续有效。
     */
    public synchronized OrderView replace(String requestId, long effectiveSeq, long newTotalQty) {
        if (requestId != null && !processedRequestIds.add(requestId)) {
            log("replace request " + requestId + " replayed, idempotent");
            return snapshot();
        }
        if (status == FILLED || status == REJECTED) {
            throw new OrderStateException(RejectReason.ORDER_TERMINAL,
                    "order " + orderId + " is " + status + ", replace is not allowed");
        }
        if (status == CANCELLED) {
            throw new OrderStateException(RejectReason.ORDER_TERMINAL,
                    "order " + orderId + " already cancelled, replace is not allowed");
        }
        if (newTotalQty <= 0) {
            throw new OrderStateException(RejectReason.INVALID_PARAMETER,
                    "newTotalQty must be positive: " + newTotalQty);
        }
        if (newTotalQty < filledQty) {
            throw new OrderStateException(RejectReason.INVALID_PARAMETER,
                    "newTotalQty " + newTotalQty + " < filledQty " + filledQty);
        }
        long oldQty = orderQty;
        orderQty = newTotalQty;
        if (filledQty == newTotalQty) {
            OrderStateMachine.requireTransit(status, FILLED);
            status = FILLED;
        } else if (filledQty > 0) {
            OrderStateMachine.requireTransit(status, PARTIALLY_FILLED);
            status = PARTIALLY_FILLED;
        } else {
            OrderStateMachine.requireTransit(status, NEW);
            status = NEW;
        }
        bump("replace effectiveSeq=" + effectiveSeq + " qty " + oldQty + " -> " + newTotalQty);
        return snapshot();
    }

    /**
     * 处理一条成交回报，返回确定性决策。整个决策在单把锁内完成。
     */
    public synchronized FillDecision applyFill(ExecReport r) {
        // 1. 重复：任何状态下都幂等
        FillRecord existing = fillsByExecId.get(r.execId());
        if (existing != null) {
            log("execId=" + r.execId() + " duplicate, ignored");
            return new FillDecision(orderId, r.execId(), FillOutcome.DUPLICATE,
                    status, filledQty, null, "duplicate execId, no quantity accumulated");
        }

        // 2. 报文合法性
        if (r.lastQty() <= 0) {
            return quarantine(r, FillRejectReason.INVALID_QUANTITY,
                    "lastQty must be positive: " + r.lastQty());
        }
        if (r.cumQty() < r.lastQty()) {
            return quarantine(r, FillRejectReason.INCONSISTENT_CUMULATIVE,
                    "cumQty " + r.cumQty() + " < lastQty " + r.lastQty());
        }

        // 3. 已拒绝订单：成交确定性迟到拒绝
        if (status == REJECTED) {
            log("execId=" + r.execId() + " rejected because order is REJECTED");
            return new FillDecision(orderId, r.execId(), FillOutcome.LATE_REJECTED,
                    status, filledQty, FillRejectReason.ORDER_REJECTED,
                    "order was rejected, fills can never be valid");
        }

        boolean lateWindow = status == CANCELLED;
        if (lateWindow && r.seq() > cancelEffectiveSeq) {
            // 4. 撤销生效点之后的迟到成交：确定性拒绝
            log("execId=" + r.execId() + " seq=" + r.seq()
                    + " after cancelEffectiveSeq=" + cancelEffectiveSeq + ", late-rejected");
            return new FillDecision(orderId, r.execId(), FillOutcome.LATE_REJECTED,
                    status, filledQty, FillRejectReason.AFTER_CANCEL_EFFECTIVE_SEQ,
                    "fill seq " + r.seq() + " > cancelEffectiveSeq " + cancelEffectiveSeq);
        }

        // 5. 超量校验（对活动订单与撤销窗口内迟到成交统一适用当前订单数量口径）
        long proposed = filledQty + r.lastQty();
        if (proposed > orderQty) {
            return quarantine(r, FillRejectReason.OVER_FILLED,
                    "filledQty " + filledQty + " + lastQty " + r.lastQty()
                            + " > orderQty " + orderQty);
        }

        // 6. 接受：乱序安全——只按 execId 去重后累加，与到达顺序无关
        fillsByExecId.put(r.execId(),
                new FillRecord(r.execId(), r.seq(), r.lastQty(), r.lastPrice(), r.cumQty()));
        filledQty = proposed;
        maxAppliedFillSeq = Math.max(maxAppliedFillSeq, r.seq());

        OrderStatus target;
        if (filledQty == orderQty) {
            target = FILLED;
        } else if (status == CANCELLED) {
            // 撤销窗口内迟到成交但未补足：保持 CANCELLED（部分成交后撤销的语义）
            target = CANCELLED;
        } else {
            target = PARTIALLY_FILLED;
        }
        OrderStateMachine.requireTransit(status, target);
        boolean changed = status != target;
        status = target;

        FillOutcome outcome = lateWindow ? FillOutcome.LATE_ACCEPTED : FillOutcome.APPLIED;
        version++;
        log("execId=" + r.execId() + " seq=" + r.seq() + " " + outcome
                + " lastQty=" + r.lastQty() + " filled=" + filledQty + "/" + orderQty
                + (changed ? " status -> " + status : " status stays " + status));
        return new FillDecision(orderId, r.execId(), outcome, status, filledQty, null,
                lateWindow ? "fill occurred before cancel effective point, accepted though late"
                        : "fill applied");
    }

    private FillDecision quarantine(ExecReport r, FillRejectReason reason, String detail) {
        log("execId=" + r.execId() + " quarantined: " + reason + " " + detail);
        // 隔离条目的落库由 ExecutionReportService 负责，这里只给出决策，订单状态不变
        return FillDecision.quarantine(orderId, r.execId(), reason, detail);
    }

    private void bump(String what) {
        version++;
        log(what);
    }

    private void log(String what) {
        decisionLog.add("v" + version + " " + what);
    }

    public synchronized List<String> decisionLog() {
        return List.copyOf(decisionLog);
    }
}
