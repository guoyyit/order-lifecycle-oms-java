package com.github.guoyyit.orderlifecycleomsjava.domain;

import java.time.Instant;

/**
 * 被隔离的异常回报（不参与聚合，可重放）。
 */
public record QuarantineEntry(
        ExecReport report,
        FillRejectReason reason,
        String detail,
        Instant quarantinedAt) {
}
