package com.metricol.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Los precios se ven sin cuenta (los enseña la página de planes a quien aún no
 * se registra) y por lo demás Facturación sigue exigiendo sesión.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlansEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("GET /billing/plans se abre sin sesión y trae los precios de lista")
    void planesSinSesion() throws Exception {
        mvc.perform(get("/api/v1/billing/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.licenseMinor").value(34900))
                .andExpect(jsonPath("$.result.extraMinor").value(24900))
                .andExpect(jsonPath("$.result.graceDays").value(7))
                .andExpect(jsonPath("$.result.taxIncluded").value(true))
                .andExpect(jsonPath("$.result.currency").value("mxn"));
    }

    @Test
    @DisplayName("el resumen y las compras siguen pidiendo sesión")
    void loDemasPideSesion() throws Exception {
        mvc.perform(get("/api/v1/billing/summary")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/billing/portal")).andExpect(status().isUnauthorized());
    }
}
