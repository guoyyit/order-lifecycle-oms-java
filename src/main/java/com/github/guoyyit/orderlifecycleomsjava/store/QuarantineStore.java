package com.github.guoyyit.orderlifecycleomsjava.store;

import com.github.guoyyit.orderlifecycleomsjava.domain.QuarantineEntry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 异常回报隔离区：被隔离的回报不参与任何成交聚合，可在修复数据后重放。
 */
public class QuarantineStore {

    private final CopyOnWriteArrayList<QuarantineEntry> entries = new CopyOnWriteArrayList<>();

    public void add(QuarantineEntry entry) {
        entries.add(entry);
    }

    public void remove(QuarantineEntry entry) {
        entries.remove(entry);
    }

    public List<QuarantineEntry> all() {
        return List.copyOf(entries);
    }

    public List<QuarantineEntry> byOrder(String orderId) {
        return entries.stream()
                .filter(e -> java.util.Objects.equals(e.report().orderId(), orderId))
                .collect(Collectors.toList());
    }
}
