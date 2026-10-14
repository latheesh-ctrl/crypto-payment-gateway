package com.cpg.gateway.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cpg.gateway.IntegrationTest;
import com.cpg.gateway.TestMerchants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class IdempotencyIT {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired IdempotencyStore store;

    private TestMerchants.Onboarded merchant;

    @BeforeEach
    void onboard() throws Exception {
        merchant = TestMerchants.onboard(mvc, json);
    }

    private MockHttpServletRequestBuilder createUser(String key, String externalRef) {
        return merchant.authorize(post("/v1/users"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"external_ref\":\"%s\"}".formatted(externalRef));
    }

    private long usersNamed(String externalRef) {
        return jdbc.sql("SELECT count(*) FROM users WHERE merchant_id = :m AND external_ref = :r")
                .param("m", merchant.merchantId()).param("r", externalRef).query(Long.class).single();
    }

    @Test
    void postWithoutKeyIsRejected() throws Exception {
        mvc.perform(merchant.authorize(post("/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"external_ref\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("idempotency_key_required"));
        assertThat(usersNamed("x")).isZero();
    }

    @Test
    void retryWithSameKeyReplaysTheOriginalResponse() throws Exception {
        String first = mvc.perform(createUser("k-1", "retry"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(createUser("k-1", "retry"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(usersNamed("retry")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        mvc.perform(createUser("k-2", "one")).andExpect(status().isCreated());
        mvc.perform(createUser("k-2", "two"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
        assertThat(usersNamed("two")).isZero();
    }

    @Test
    void clientErrorsAreAlsoReplayed() throws Exception {
        mvc.perform(createUser("k-3", "bad ref")).andExpect(status().isBadRequest());
        mvc.perform(createUser("k-3", "bad ref"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void inFlightKeyReturnsConflict() throws Exception {
        // Simulate a first attempt still running: the key is claimed but has no response yet.
        store.claim(merchant.merchantId(), "k-4", "POST", "/v1/users",
                java.security.MessageDigest.getInstance("SHA-256").digest(
                        ("POST /v1/users\n{\"external_ref\":\"busy\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        mvc.perform(createUser("k-4", "busy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_request_in_progress"));
        assertThat(usersNamed("busy")).isZero();
    }

    @Test
    void keysAreScopedPerMerchant() throws Exception {
        mvc.perform(createUser("shared-key", "a")).andExpect(status().isCreated());
        TestMerchants.Onboarded other = TestMerchants.onboard(mvc, json);
        mvc.perform(other.authorize(post("/v1/users"))
                        .header("Idempotency-Key", "shared-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"external_ref\":\"a\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    void unauthenticatedPostGets401NotIdempotencyError() throws Exception {
        mvc.perform(post("/v1/users").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
