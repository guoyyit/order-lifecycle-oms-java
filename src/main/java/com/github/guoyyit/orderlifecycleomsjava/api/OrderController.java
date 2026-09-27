package com.github.guoyyit.orderlifecycleomsjava.api;

import com.github.guoyyit.orderlifecycleomsjava.domain.ExecReport;
import com.github.guoyyit.orderlifecycleomsjava.domain.FillDecision;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderView;
import com.github.guoyyit.orderlifecycleomsjava.domain.ReconcileReport;
import com.github.guoyyit.orderlifecycleomsjava.service.ExecutionReportService;
import com.github.guoyyit.orderlifecycleomsjava.service.OrderCommandService;
import com.github.guoyyit.orderlifecycleomsjava.service.OrderQueryService;
import com.github.guoyyit.orderlifecycleomsjava.service.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 订单生命周期 REST 接口。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderCommandService commands;
    private final OrderQueryService queries;
    private final ExecutionReportService execReports;
    private final ReconciliationService reconciliation;

    public OrderController(OrderCommandService commands,
                           OrderQueryService queries,
                           ExecutionReportService execReports,
                           ReconciliationService reconciliation) {
        this.commands = commands;
        this.queries = queries;
        this.execReports = execReports;
        this.reconciliation = reconciliation;
    }

    @PostMapping
    public OrderView accept(@RequestBody Map<String, Object> body) {
        return commands.accept(asString(body.get("orderId")), asLong(body.get("orderQty")));
    }

    @PostMapping("/{orderId}/new")
    public OrderView markNew(@PathVariable String orderId) {
        return commands.markNew(orderId);
    }

    @PostMapping("/{orderId}/reject")
    public OrderView reject(@PathVariable String orderId, @RequestBody(required = false) Map<String, String> body) {
        String detail = body == null ? null : body.getOrDefault("detail", "");
        return commands.reject(orderId, detail);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderView cancel(@PathVariable String orderId, @RequestBody Map<String, Object> body) {
        return commands.cancel(orderId, asString(body.get("requestId")),
                asLong(body.get("effectiveSeq")));
    }

    @PostMapping("/{orderId}/replace")
    public OrderView replace(@PathVariable String orderId, @RequestBody Map<String, Object> body) {
        return commands.replace(orderId, asString(body.get("requestId")),
                asLong(body.get("effectiveSeq")), asLong(body.get("newTotalQty")));
    }

    @PostMapping("/{orderId}/fills")
    public FillDecision fill(@PathVariable String orderId, @RequestBody ExecReport report) {
        ExecReport effective = new ExecReport(orderId, report.seq(), report.execId(),
                report.lastQty(), report.lastPrice(), report.cumQty());
        return execReports.process(effective);
    }

    @GetMapping("/{orderId}")
    public OrderView get(@PathVariable String orderId) {
        return queries.get(orderId);
    }

    @GetMapping("/{orderId}/reconcile")
    public ReconcileReport reconcile(@PathVariable String orderId) {
        return reconciliation.reconcile(orderId);
    }

    @GetMapping("/reconcile")
    public List<ReconcileReport> reconcileAll() {
        return reconciliation.reconcileAll();
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(o));
    }
}
