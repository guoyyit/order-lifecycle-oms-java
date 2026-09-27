package com.github.guoyyit.orderlifecycleomsjava.api;

import com.github.guoyyit.orderlifecycleomsjava.domain.ExecutionReport;
import com.github.guoyyit.orderlifecycleomsjava.domain.OrderSnapshot;
import com.github.guoyyit.orderlifecycleomsjava.service.OrderService;
import com.github.guoyyit.orderlifecycleomsjava.service.QuarantineStore;
import com.github.guoyyit.orderlifecycleomsjava.service.QuarantinedReport;
import com.github.guoyyit.orderlifecycleomsjava.service.ReconcileReport;
import com.github.guoyyit.orderlifecycleomsjava.service.ReconcileService;
import com.github.guoyyit.orderlifecycleomsjava.statemachine.TransitionResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 订单生命周期与对账 REST API。
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;
    private final ReconcileService reconcileService;
    private final QuarantineStore quarantineStore;

    public OrderController(OrderService orderService, ReconcileService reconcileService,
                           QuarantineStore quarantineStore) {
        this.orderService = orderService;
        this.reconcileService = reconcileService;
        this.quarantineStore = quarantineStore;
    }

    public record AcceptOrderRequest(String orderId, long orderQty) {
    }

    public record ExecutionRequest(String execId, String orderId, long execQty, long execPrice,
                                   String execTime) {
    }

    public record AmendRequest(long newOrderQty) {
    }

    /** 受理订单。 */
    @PostMapping("/orders")
    public ResponseEntity<TransitionResult> accept(@RequestBody AcceptOrderRequest request) {
        return toResponse(orderService.acceptOrder(request.orderId(), request.orderQty()));
    }

    /** 标记已报。 */
    @PostMapping("/orders/{orderId}/reported")
    public ResponseEntity<TransitionResult> markReported(@PathVariable String orderId) {
        return toResponse(orderService.markReported(orderId));
    }

    /** 接收成交回报。 */
    @PostMapping("/executions")
    public ResponseEntity<TransitionResult> onExecution(@RequestBody ExecutionRequest request) {
        ExecutionReport report = new ExecutionReport(request.execId(), request.orderId(),
                request.execQty(), request.execPrice(),
                request.execTime() != null ? Instant.parse(request.execTime()) : Instant.now());
        return toResponse(orderService.onExecutionReport(report));
    }

    /** 撤销订单。 */
    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<TransitionResult> cancel(@PathVariable String orderId) {
        return toResponse(orderService.cancel(orderId));
    }

    /** 改单。 */
    @PostMapping("/orders/{orderId}/amend")
    public ResponseEntity<TransitionResult> amend(@PathVariable String orderId,
                                                  @RequestBody AmendRequest request) {
        return toResponse(orderService.amend(orderId, request.newOrderQty()));
    }

    /** 查询订单快照。 */
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderSnapshot> get(@PathVariable String orderId) {
        return orderService.getSnapshot(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 对账。 */
    @GetMapping("/orders/{orderId}/reconcile")
    public ResponseEntity<ReconcileReport> reconcile(@PathVariable String orderId) {
        return reconcileService.reconcile(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 重放该订单已接受回报（幂等性验证）。 */
    @PostMapping("/orders/{orderId}/replay")
    public List<TransitionResult> replay(@PathVariable String orderId) {
        return orderService.replayAcceptedExecutions(orderId);
    }

    /** 隔离区列表。 */
    @GetMapping("/quarantine")
    public List<QuarantinedReport> quarantine() {
        return quarantineStore.listAll();
    }

    private ResponseEntity<TransitionResult> toResponse(TransitionResult result) {
        return result.isAccepted()
                ? ResponseEntity.ok(result)
                : ResponseEntity.unprocessableEntity().body(result);
    }
}
