# 订单状态机与成交回报语义

## 1. 状态全集

| 状态 | 含义 |
| --- | --- |
| `ACCEPTED` | 已受理，尚未报出 |
| `NEW` | 已报交易所，尚无成交 |
| `PARTIALLY_FILLED` | 部分成交 |
| `FILLED` | 全部成交（终结） |
| `CANCELLED` | 已撤销；部分成交后撤销也归为此状态（终结） |
| `REJECTED` | 已拒绝（终结，拒绝后不可能有成交） |

## 2. 合法迁移

```
ACCEPTED         -> NEW | PARTIALLY_FILLED | FILLED | REJECTED
NEW              -> PARTIALLY_FILLED | FILLED | CANCELLED | REJECTED
PARTIALLY_FILLED -> PARTIALLY_FILLED | FILLED | CANCELLED
CANCELLED        -> CANCELLED | FILLED
FILLED           -> （终结，仅允许同态幂等）
REJECTED         -> （终结，仅允许同态幂等）
```

- `ACCEPTED` 直接收到成交回报时允许迁移到 `PARTIALLY_FILLED/FILLED`，以容忍报出确认丢失。
- 非法迁移抛出 `OrderStateException`，原因码固定为 `ILLEGAL_TRANSITION`，异常消息同时包含源状态与目标状态，调用方可据此区分拒绝原因。
- 其它原因码：`UNKNOWN_ORDER`、`ORDER_TERMINAL`（终结后撤销/改单）、`DUPLICATE_REQUEST`（重复撤销）、`INVALID_PARAMETER`（改单数量非法）。
- 终结状态（`FILLED`/`REJECTED`）上的撤销、改单一律以 `ORDER_TERMINAL` 拒绝；`CANCELLED` 上的改单同样拒绝，但撤销窗口内的迟到成交仍可更新已成交量。

## 3. 成交回报模型

成交回报 `ExecReport` 携带：

- `seq`：交易所事件序列号，单调递增，用于判定“迟到”与撤销窗口；
- `execId`：成交编号，全局唯一，用于去重；
- `lastQty`：本次成交量；
- `cumQty`：交易所口径的累计成交量（乱序到达时该值本身也是乱序的）。

### 3.1 处理规则（确定性，与到达顺序、到达线程无关）

处理在订单聚合的内置锁上一次性完成，依次判定：

1. **重复去重**：`execId` 已接受过 -> `DUPLICATE`，幂等忽略，绝不重复累计（即使回报内容被篡改也只认第一次）。
2. **报文合法**：`lastQty <= 0` -> 隔离 `INVALID_QUANTITY`；`cumQty < lastQty` -> 隔离 `INCONSISTENT_CUMULATIVE`。
3. **已拒绝订单**：任何成交 -> `LATE_REJECTED`，原因 `ORDER_REJECTED`。
4. **撤销窗口**：订单已撤销且 `seq > cancelEffectiveSeq` -> `LATE_REJECTED`，原因 `AFTER_CANCEL_EFFECTIVE_SEQ`；`seq <= cancelEffectiveSeq` 的成交发生在撤销生效之前，迟到也必须接受。
5. **超量**：`已接受成交量之和 + lastQty > orderQty` -> 隔离 `OVER_FILLED`。交易所正确的单调 `cumQty` 口径下，乱序回报永远不会触发，只有矛盾数据才会触发。
6. **接受**：按 `execId` 落明细并累加。乱序安全——去重后的加法与到达顺序无关，总和恒等。
   - 累计量 == 订单量 -> `FILLED`；
   - 否则活动订单 -> `PARTIALLY_FILLED`；撤销订单 -> 保持 `CANCELLED`（部分成交后撤销）。

返回的 `FillOutcome`：`APPLIED` / `DUPLICATE` / `LATE_ACCEPTED` / `LATE_REJECTED` / `QUARANTINED`。

### 3.2 撤销窗口（乱序消息修正）

撤销请求携带 `effectiveSeq`，语义为“撤销在该事件点生效”。若该点早于本订单**已确认成交的最大 seq**（例如高 seq 的成交先于撤销到达），生效点自动推进为

```
cancelEffectiveSeq = max(requestedEffectiveSeq, maxAppliedFillSeq)
```

由此保证不变量：**每一笔已接受成交的 seq 都 <= cancelEffectiveSeq**，成交与撤销并发到达时最终状态确定且可解释（推进动作写入订单决策日志）。

撤销窗口内迟到成交恰好补足订单量时，状态迁移为 `FILLED`（业务真相是撤销生效前已全部成交）。

### 3.3 改单

- 支持 `NEW` / `PARTIALLY_FILLED` 状态改单，改单数量 `newTotalQty` 不得小于已成交量；
- 改单到恰好等于已成交量 -> `FILLED`；大于已成交量且已有成交 -> `PARTIALLY_FILLED`；无成交 -> `NEW`；
- 终结订单（含已撤销）拒绝改单。

### 3.4 请求幂等

撤销/改单携带 `requestId`，同一 `requestId` 重放返回当前快照且不产生新变更。

## 4. 并发模型与快照一致性

- 订单状态、成交明细、版本号全部封装在 `Order` 聚合内，所有变更与读取都在 `Order` 的内置锁上串行完成；跨订单互不阻塞。
- 每次读取返回不可变快照 `OrderView`（含状态、数量、版本号、撤销生效点与成交明细列表的拷贝），状态与明细必然来自同一完整版本，读不到中间态。
- 每次有效变更递增 `version`，调用方可据此判断观察到的版本。
- 存储层使用 `ConcurrentHashMap`，订单创建按 `orderId` 幂等拒绝重复。

## 5. 异常隔离与重放

- 未知订单的回报先进入隔离区（`UNKNOWN_ORDER`），订单后续建立后可重放接受；
- 超量、数量非法、cumQty 矛盾等异常回报进入隔离区，不进入任何成交聚合，不影响订单状态；
- 重放重新走同一套规则：已接受过的 `execId` 一律 `DUPLICATE`，因此**重放已接受回报不会改变最终结果**；仍不合法的继续保留在隔离区，条件改变后（如改单扩容）可变为接受。
