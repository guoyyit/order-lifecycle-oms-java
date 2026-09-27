package com.github.guoyyit.orderlifecycleomsjava.service;

import com.github.guoyyit.orderlifecycleomsjava.domain.ExecutionReport;
import com.github.guoyyit.orderlifecycleomsjava.domain.RejectReason;

import java.time.Instant;

/**
 * 被隔离的异常回报记录：保留原始回报、隔离原因与时间，供诊断与重放。
 */
public final class QuarantinedReport {

    private final ExecutionReport report;
    private final RejectReason reason;
    private final String detail;
    private final Instant quarantinedAt;

    public QuarantinedReport(ExecutionReport report, RejectReason reason, String detail) {
        this.report = report;
        this.reason = reason;
        this.detail = detail;
        this.quarantinedAt = Instant.now();
    }

    public ExecutionReport getReport() {
        return report;
    }

    public RejectReason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getQuarantinedAt() {
        return quarantinedAt;
    }

    @Override
    public String toString() {
        return "QuarantinedReport{execId=" + report.getExecId() + ", orderId=" + report.getOrderId()
                + ", reason=" + reason + ", detail=" + detail + '}';
    }
}
