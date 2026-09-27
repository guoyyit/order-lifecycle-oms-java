# Order Lifecycle OMS

订单全生命周期状态管理与成交回报对账能力。基于 Spring Boot 3 / Java 17。

## 构建与测试

```bash
mvn compile   # 编译
mvn test      # 运行全部自动化测试（31 个用例）
```

## 订单状态机

### 状态定义

| 状态 | 含义 | 终态 |
|---|---|---|
| `ACCEPTED` | 已受理，尚未报送交易所 | 否 |
| `REPORTED` | 已报，等待成交 | 否 |
| `PARTIALLY_FILLED` | 部分成交 | 否 |
| `FILLED` | 全部成交 | 是 |
| `CANCELLED` | 已撤销 | 是 |
| `REJECTED` | 已拒绝 | 是 |

### 合法迁移表

```
ACCEPTED          -> REPORTED | CANCELLED | REJECTED
REPORTED          -> PARTIALLY_FILLED | FILLED | CANCELLED | REJECTED
PARTIALLY_FILLED  -> PARTIALLY_FILLED | FILLED | CANCELLED
FILLED/CANCELLED/REJECTED -> (终态，拒绝一切迁移)
```

非法迁移被拒绝并返回**可区分的原因**（`RejectReason`）：

| 原因 | 语义 |
|---|---|
| `ORDER_NOT_FOUND` | 订单不存在 |
| `ILLEGAL_STATE_TRANSITION` | 当前状态不允许该迁移 |
| `ORDER_ALREADY_TERMINAL` | 订单已终结，拒绝一切变更 |
| `DUPLICATE_EXECUTION` | 重复成交回报（execId 已接受） |
| `LATE_EXECUTION_AFTER_TERMINAL` | 终态后迟到的成交回报 |
| `INVALID_EXECUTION_QUANTITY` | 成交量 ≤0 或超过剩余未成交量 |
| `INVALID_AMEND_QUANTITY` | 改单数量非法（≤0、≥原数量或 < 已成交量） |
| `STATE_CONFLICT` | 请求与订单当前状态冲突（如重复受理） |
| `QUARANTINED` | 异常回报已隔离，未应用到订单 |

## 成交回报处理语义

- **乱序**：回报按 execId 独立累计，到达顺序不影响最终聚合结果。
- **重复**：同一 `execId` 只累计一次；重复到达幂等拒绝（`DUPLICATE_EXECUTION`），不重复累计成交量。
- **迟到**：订单进入终态（FILLED/CANCELLED/REJECTED）后到达的回报，确定性地拒绝（`LATE_EXECUTION_AFTER_TERMINAL`）。
- **异常隔离**：数量非法（≤0 或超过剩余未成交量）或订单不存在的回报进入隔离区（`QuarantineStore`），**不污染订单状态**，可通过 `/api/quarantine` 查询诊断。
- **重放**：`POST /api/orders/{orderId}/replay` 重放该订单全部已接受回报；每条回报被幂等拒绝（`DUPLICATE_EXECUTION`），订单状态、成交量、版本号均不变。

## 并发语义

- **订单维度串行化**：同一订单的所有变更（成交、撤销、改单）在订单锁内串行执行；不同订单之间并行。
- **撤销 vs 成交并发**：谁先获得锁谁生效——成交先到则订单 FILLED、撤销被 `ORDER_ALREADY_TERMINAL` 拒绝；撤销先到则订单 CANCELLED、迟到成交被 `LATE_EXECUTION_AFTER_TERMINAL` 拒绝。最终状态确定且可通过拒绝原因解释。
- **部分成交下撤销**：允许，已成交量保留，订单迁移到 CANCELLED。
- **部分成交下改单**：仅允许改小数量且不得小于已成交量；改到等于已成交量时订单直接 FILLED 终结。
- **一致性读**：查询返回不可变快照（`OrderSnapshot`），状态与成交明细来自同一版本，不会出现中间态；快照带单调递增版本号。

## 对账语义

`GET /api/orders/{orderId}/reconcile` 按订单聚合：

- `filledQty`（累计成交量）必须等于全部已接受回报的成交量之和；
- `leavesQty`（未成交量）必须等于 `orderQty - filledQty`；
- 已接受回报的 execId 不得重复；
- 状态与成交量一致（满量必为 FILLED；有量且非终态必为 PARTIALLY_FILLED）。

任何一项不满足时 `consistent=false`，并在 `diagnostics` 中给出**可定位的诊断信息**（涉及哪条回报、期望值与实际值）。

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders` | 受理订单 `{orderId, orderQty}` |
| POST | `/api/orders/{orderId}/reported` | 标记已报 |
| POST | `/api/executions` | 接收成交回报 `{execId, orderId, execQty, execPrice, execTime}` |
| POST | `/api/orders/{orderId}/cancel` | 撤销 |
| POST | `/api/orders/{orderId}/amend` | 改单 `{newOrderQty}` |
| GET | `/api/orders/{orderId}` | 查询订单快照 |
| GET | `/api/orders/{orderId}/reconcile` | 对账 |
| POST | `/api/orders/{orderId}/replay` | 重放已接受回报（幂等性验证） |
| GET | `/api/quarantine` | 隔离区列表 |

处理结果统一为 `TransitionResult`：成功携带迁移后状态（HTTP 200），失败携带 `RejectReason` 与明细（HTTP 422）。

## 代码结构

```
domain/        Order（聚合根）、OrderStatus、RejectReason、ExecutionReport、OrderSnapshot
statemachine/  OrderStateMachine（迁移表）、TransitionResult
repository/    OrderRepository（内存存储 + 订单维度锁）
service/       OrderService、ReconcileService、QuarantineStore、QuarantinedReport、ReconcileReport
api/           OrderController（REST）
```

## 测试覆盖

| 测试类 | 覆盖场景 |
|---|---|
| `OrderStateMachineTest` | 合法/非法迁移、终态拒绝及可区分原因 |
| `OrderLifecycleTest` | 全生命周期、部分成交下撤销/改单、非法改单、订单不存在 |
| `ExecutionReportHandlingTest` | 乱序、重复、迟到、超量隔离、未知订单隔离、零量隔离 |
| `ConcurrencyTest` | 并发成完全不丢不重、撤销与成交并发确定性、快照一致性、改单与成交串行化 |
| `ReconcileAndReplayTest` | 逐步对账一致性、撤销后对账、重放幂等 |
