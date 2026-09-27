package com.github.guoyyit.orderlifecycleomsjava.api;

import com.github.guoyyit.orderlifecycleomsjava.domain.FillDecision;
import com.github.guoyyit.orderlifecycleomsjava.domain.QuarantineEntry;
import com.github.guoyyit.orderlifecycleomsjava.service.ExecutionReportService;
import com.github.guoyyit.orderlifecycleomsjava.store.QuarantineStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 异常回报隔离与重放接口。
 */
@RestController
@RequestMapping("/api/quarantine")
public class QuarantineController {

    private final QuarantineStore quarantine;
    private final ExecutionReportService execReports;

    public QuarantineController(QuarantineStore quarantine, ExecutionReportService execReports) {
        this.quarantine = quarantine;
        this.execReports = execReports;
    }

    @GetMapping
    public List<QuarantineEntry> all() {
        return quarantine.all();
    }

    @GetMapping("/orders/{orderId}")
    public List<QuarantineEntry> byOrder(@PathVariable String orderId) {
        return quarantine.byOrder(orderId);
    }

    @PostMapping("/orders/{orderId}/replay")
    public List<FillDecision> replay(@PathVariable String orderId) {
        return execReports.replayQuarantine(orderId);
    }
}
