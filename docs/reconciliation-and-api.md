# 成交对账语义与 HTTP API

## 1. 对账不变量

对账基于订单的**同一版本快照**进行（状态与成交明细一起拷出，不存在半新半旧的问题），逐单核对：

1. **聚合一致**：`sum(已接受回报.lastQty) == order.filledQty`。任何时刻该等式都必须成立；不一致诊断形如
   `[O1] aggregate mismatch: sum(accepted lastQty)=90 but filledQty=100`。
2. **明细唯一**：已接受成交明细中的 `execId` 无重复。
3. **数量边界**：`filledQty <= orderQty`。
4. **未成交量**：`remainingQty == orderQty - filledQty`。
5. **状态一致**：
   - `FILLED` 必须 `filledQty == orderQty`；
   - `PARTIALLY_FILLED` 必须 `0 < filledQty < orderQty`；
   - `CANCELLED` 必须 `filledQty < orderQty`；
   - `NEW/ACCEPTED` 必须 `filledQty == 0`；
   - `REJECTED` 必须 `filledQty == 0`。

任一不满足，`ReconcileReport.consistent=false`，`diagnostics` 给出可直接定位到订单号与具体数值的条目列表。
被隔离的异常回报不参与聚合，因此“聚合结果与**全部已接受回报**一致”由去重与单锁变更天然保证，对账是对该保证的独立校验。

## 2. HTTP API

### 订单命令

| 方法 | 路径 | 请求体 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/orders` | `{"orderId","orderQty"}` | 受理，返回 ACCEPTED 快照 |
| POST | `/api/orders/{id}/new` | - | 报出 -> NEW |
| POST | `/api/orders/{id}/reject` | `{"detail"}` | 拒绝 -> REJECTED |
| POST | `/api/orders/{id}/cancel` | `{"requestId","effectiveSeq"}` | 撤销 |
| POST | `/api/orders/{id}/replace` | `{"requestId","effectiveSeq","newTotalQty"}` | 改单（改数量） |
| POST | `/api/orders/{id}/fills` | `{"seq","execId","lastQty","lastPrice","cumQty"}` | 成交回报 |
| GET | `/api/orders/{id}` | - | 完整版本快照 |
| GET | `/api/orders/{id}/reconcile` | - | 单订单对账 |
| GET | `/api/orders/reconcile` | - | 全部订单对账 |

### 隔离区

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/quarantine` | 全部隔离条目 |
| GET | `/api/quarantine/orders/{id}` | 指定订单的隔离条目 |
| POST | `/api/quarantine/orders/{id}/replay` | 重放指定订单的隔离回报 |

### 错误响应

```json
{ "reason": "ORDER_TERMINAL", "message": "order W1 is FILLED, cancel is not allowed" }
```

| reason | HTTP |
| --- | --- |
| `UNKNOWN_ORDER` | 404 |
| `ILLEGAL_TRANSITION` / `ORDER_TERMINAL` / `DUPLICATE_REQUEST` | 409 |
| `INVALID_PARAMETER` | 400 |

注意：成交回报被确定性拒绝（`LATE_REJECTED`）或隔离（`QUARANTINED`）属于正常业务结果，返回 200 并在响应体中给出 `outcome`/`rejectReason`，不使用错误状态码。

## 3. 典型示例（curl）

```bash
curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"orderId":"O1","orderQty":100}'
curl -X POST localhost:8080/api/orders/O1/new
# 乱序成交
curl -X POST localhost:8080/api/orders/O1/fills -H 'Content-Type: application/json' \
  -d '{"seq":3,"execId":"E3","lastQty":30,"lastPrice":100,"cumQty":100}'
curl -X POST localhost:8080/api/orders/O1/fills -H 'Content-Type: application/json' \
  -d '{"seq":1,"execId":"E1","lastQty":40,"lastPrice":100,"cumQty":40}'
# 部分成交后撤销，生效点 seq=2
curl -X POST localhost:8080/api/orders/O1/cancel -H 'Content-Type: application/json' \
  -d '{"requestId":"C1","effectiveSeq":2}'
# 对账
curl localhost:8080/api/orders/O1/reconcile
```

## 4. 测试覆盖

| 测试类 | 覆盖点 |
| --- | --- |
| `OrderStateMachineTest` | 合法/非法迁移、同态幂等、可区分原因码 |
| `OrderAggregateTest` | 乱序、重复（含篡改内容）、迟到接受/拒绝、撤销窗口补足、拒绝单成交、超量/非法隔离、部分成交改单、撤销幂等 |
| `ReconciliationAndReplayTest` | 乱序+重复后聚合一致、异常隔离不污染、未知订单隔离后重放、重放已接受回报结果不变、条件变化后重放接受、撤销单对账 |
| `OrderConcurrencyTest` | 撤销与成交并发 20 轮重复、读写并发快照自洽与多版本观察、乱序+重复多线程下恰好累计一次 |
| `OrderControllerIntegrationTest` | REST 全生命周期、原因码映射、撤销窗口、隔离与重放端到端 |
