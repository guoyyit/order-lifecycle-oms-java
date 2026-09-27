package com.github.guoyyit.orderlifecycleomsjava.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：通过 REST 接口演示乱序/重复/迟到成交、撤销窗口、对账与隔离重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void fullLifecycleOverHttp() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"W1\",\"orderQty\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mvc.perform(post("/api/orders/W1/new")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));

        // 乱序成交：先发 seq=3
        mvc.perform(post("/api/orders/W1/fills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":3,\"execId\":\"E3\",\"lastQty\":30,\"lastPrice\":100,\"cumQty\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"));
        // 再发 seq=1
        mvc.perform(post("/api/orders/W1/fills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":1,\"execId\":\"E1\",\"lastQty\":40,\"lastPrice\":100,\"cumQty\":40}"))
                .andExpect(status().isOk());
        // 重复 seq=1
        mvc.perform(post("/api/orders/W1/fills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":1,\"execId\":\"E1\",\"lastQty\":40,\"lastPrice\":100,\"cumQty\":40}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        // seq=2 补足
        mvc.perform(post("/api/orders/W1/fills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":2,\"execId\":\"E2\",\"lastQty\":30,\"lastPrice\":100,\"cumQty\":70}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultingStatus").value("FILLED"));

        mvc.perform(get("/api/orders/W1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filledQty").value(100))
                .andExpect(jsonPath("$.remainingQty").value(0))
                .andExpect(jsonPath("$.fills.length()").value(3));

        mvc.perform(get("/api/orders/W1/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.sumOfAcceptedLastQty").value(100));

        // 全部成交后撤销 -> 409，原因 ORDER_TERMINAL
        mvc.perform(post("/api/orders/W1/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"C1\",\"effectiveSeq\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ORDER_TERMINAL"));
    }

    @Test
    void cancelWindowQuarantineAndReplayOverHttp() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"W2\",\"orderQty\":100}")).andExpect(status().isOk());
        mvc.perform(post("/api/orders/W2/new")).andExpect(status().isOk());

        mvc.perform(post("/api/orders/W2/fills").contentType(MediaType.APPLICATION_JSON)
                .content("{\"seq\":1,\"execId\":\"E1\",\"lastQty\":30,\"lastPrice\":100,\"cumQty\":30}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/orders/W2/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"C1\",\"effectiveSeq\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 窗口内迟到成交 -> LATE_ACCEPTED，保持 CANCELLED
        mvc.perform(post("/api/orders/W2/fills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":2,\"execId\":\"E2\",\"lastQty\":20,\"lastPrice\":100,\"cumQty\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("LATE_ACCEPTED"))
                .andExpect(jsonPath("$.resultingStatus").value("CANCELLED"));

        // 窗口外迟到成交 -> LATE_REJECTED
        mvc.perform(post("/api/orders/W2/fills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":3,\"execId\":\"E3\",\"lastQty\":10,\"lastPrice\":100,\"cumQty\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("LATE_REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("AFTER_CANCEL_EFFECTIVE_SEQ"));

        // 超量异常回报 -> QUARANTINED
        mvc.perform(post("/api/orders/W2/fills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":2,\"execId\":\"E4\",\"lastQty\":999,\"lastPrice\":100,\"cumQty\":1049}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("QUARANTINED"))
                .andExpect(jsonPath("$.rejectReason").value("OVER_FILLED"));

        mvc.perform(get("/api/quarantine/orders/W2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 已撤销订单不允许改单
        mvc.perform(post("/api/orders/W2/replace").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"R1\",\"effectiveSeq\":9,\"newTotalQty\":200}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ORDER_TERMINAL"));

        // 重放隔离区：仍然超量，继续隔离
        mvc.perform(post("/api/quarantine/orders/W2/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("QUARANTINED"));

        // 成交量不被污染，对账一致
        mvc.perform(get("/api/orders/W2/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.orderFilledQty").value(50))
                .andExpect(jsonPath("$.remainingQty").value(50));
    }

    @Test
    void unknownOrderReturnsDistinguishableReason() throws Exception {
        mvc.perform(get("/api/orders/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_ORDER"));
    }
}
