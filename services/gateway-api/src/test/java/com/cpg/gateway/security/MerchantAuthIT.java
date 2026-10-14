package com.cpg.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cpg.gateway.IntegrationTest;
import com.cpg.gateway.TestMerchants;
import com.cpg.gateway.ledger.AccountKind;
import com.cpg.gateway.ledger.AccountRef;
import com.cpg.gateway.ledger.LedgerService;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class MerchantAuthIT {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired LedgerService ledger;

    private TestMerchants.Onboarded merchant;

    @BeforeEach
    void setUp() throws Exception {
        merchant = TestMerchants.onboard(mvc, json);
    }

    private void settings(String body) throws Exception {
        mvc.perform(patch("/internal/v1/merchants/" + merchant.merchantId() + "/settings")
                        .header("X-Admin-Token", TestMerchants.ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private static String userBody() {
        return "{\"external_ref\":\"p-%s\"}".formatted(UUID.randomUUID());
    }

    /** A POST /v1/users signed over {@code body} but sending {@code sentBody}. */
    private MockHttpServletRequestBuilder signedPost(String signingSecret, String secret, String body, long timestamp,
                                                     String nonce, String sentBody) {
        String signature = RequestSignature.sign(signingSecret, timestamp, nonce, "POST", "/v1/users",
                body.getBytes(StandardCharsets.UTF_8));
        return post("/v1/users")
                .header("Authorization", "Bearer " + secret)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header(RequestSignature.TIMESTAMP_HEADER, Long.toString(timestamp))
                .header(RequestSignature.NONCE_HEADER, nonce)
                .header(RequestSignature.SIGNATURE_HEADER, signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sentBody);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void signedRequestsAreVerifiedAndReplaysRefused() throws Exception {
        settings("{\"require_signed_requests\":true}");
        String body = userBody();
        String nonce = nonce();

        mvc.perform(merchant.post("/v1/users", userBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("signature_required"));

        mvc.perform(signedPost(merchant.signingSecret(), merchant.secret(), body, now(), nonce, body))
                .andExpect(status().isCreated());
        mvc.perform(signedPost(merchant.signingSecret(), merchant.secret(), body, now(), nonce, body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("nonce_reused"));

        String other = userBody();
        mvc.perform(signedPost(merchant.signingSecret(), merchant.secret(), body, now(), nonce(), other))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_signature"));
        mvc.perform(signedPost("ss_wrong", merchant.secret(), body, now(), nonce(), body))
                .andExpect(jsonPath("$.code").value("invalid_signature"));
        mvc.perform(signedPost(merchant.signingSecret(), merchant.secret(), body, now() - 301, nonce(), body))
                .andExpect(jsonPath("$.code").value("timestamp_out_of_range"));
        mvc.perform(signedPost(merchant.signingSecret(), merchant.secret(), body, now(), "short", body))
                .andExpect(jsonPath("$.code").value("invalid_nonce"));
    }

    @Test
    void signedGetCoversTheQueryString() throws Exception {
        long ts = now();
        String nonce = nonce();
        String signature = RequestSignature.sign(merchant.signingSecret(), ts, nonce, "GET", "/v1/users?limit=5", new byte[0]);
        mvc.perform(get("/v1/users").queryParam("limit", "5")
                        .header("Authorization", "Bearer " + merchant.secret())
                        .header(RequestSignature.TIMESTAMP_HEADER, Long.toString(ts))
                        .header(RequestSignature.NONCE_HEADER, nonce)
                        .header(RequestSignature.SIGNATURE_HEADER, signature))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/users").queryParam("limit", "50")
                        .header("Authorization", "Bearer " + merchant.secret())
                        .header(RequestSignature.TIMESTAMP_HEADER, Long.toString(ts))
                        .header(RequestSignature.NONCE_HEADER, nonce())
                        .header(RequestSignature.SIGNATURE_HEADER, signature))
                .andExpect(jsonPath("$.code").value("invalid_signature"));
    }

    @Test
    void scopesLimitWhatAKeyCanDoAndCannotBeEscalated() throws Exception {
        String body = mvc.perform(merchant.post("/v1/api_keys", "{\"name\":\"reporting\",\"scopes\":[\"read\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scopes[0]").value("read"))
                .andExpect(jsonPath("$.signing_secret").exists())
                .andReturn().getResponse().getContentAsString();
        TestMerchants.Onboarded readOnly = new TestMerchants.Onboarded(merchant.merchantId(),
                json.readTree(body).get("secret").asString(), null, null);

        mvc.perform(readOnly.authorize(get("/v1/users"))).andExpect(status().isOk());
        mvc.perform(readOnly.post("/v1/users", userBody()))
                .andExpect(status().isForbidden());
        mvc.perform(readOnly.post("/v1/api_keys", "{\"name\":\"x\"}")).andExpect(status().isForbidden());

        // The default key has no payout scope, so it cannot mint one.
        mvc.perform(merchant.post("/v1/api_keys", "{\"name\":\"payouts\",\"scopes\":[\"payouts:write\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("scope_not_grantable"));
        mvc.perform(merchant.post("/v1/api_keys", "{\"name\":\"bad\",\"scopes\":[\"admin\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_scopes"));
    }

    @Test
    void ipAllowlistIsEnforced() throws Exception {
        String body = mvc.perform(merchant.post("/v1/api_keys", "{\"name\":\"office\",\"ip_allowlist\":[\"10.0.0.0/8\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String secret = json.readTree(body).get("secret").asString();

        mvc.perform(get("/v1/users").header("Authorization", "Bearer " + secret))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ip_not_allowed"));
        mvc.perform(get("/v1/users").header("Authorization", "Bearer " + secret)
                        .with(r -> {
                            r.setRemoteAddr("10.20.30.40");
                            return r;
                        }))
                .andExpect(status().isOk());
        mvc.perform(merchant.post("/v1/api_keys", "{\"name\":\"bad\",\"ip_allowlist\":[\"not-an-ip\"]}"))
                .andExpect(jsonPath("$.code").value("invalid_ip_allowlist"));
    }

    @Test
    void rateLimitAnswers429WithRetryAfter() throws Exception {
        settings("{\"rate_limit_per_second\":1}");
        // Burst capacity is twice the rate.
        mvc.perform(merchant.authorize(get("/v1/users"))).andExpect(status().isOk());
        mvc.perform(merchant.authorize(get("/v1/users"))).andExpect(status().isOk());
        mvc.perform(merchant.authorize(get("/v1/users")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate_limited"));
    }

    @Test
    void rollingAndRevokingKeys() throws Exception {
        String rolled = mvc.perform(merchant.post("/v1/api_keys/" + merchant.keyId() + "/roll", "{\"overlap_hours\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode replacement = json.readTree(rolled);
        TestMerchants.Onboarded next = new TestMerchants.Onboarded(merchant.merchantId(),
                replacement.get("secret").asString(), replacement.get("signing_secret").asString(),
                replacement.get("id").asString());

        // Both work during the overlap.
        mvc.perform(merchant.authorize(get("/v1/users"))).andExpect(status().isOk());
        mvc.perform(next.authorize(get("/v1/users"))).andExpect(status().isOk());
        mvc.perform(next.authorize(get("/v1/api_keys")))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].secret").doesNotExist());

        mvc.perform(next.post("/v1/api_keys/" + merchant.keyId() + "/revoke", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked_at").exists());
        mvc.perform(merchant.authorize(get("/v1/users"))).andExpect(status().isUnauthorized());

        // A roll with no overlap retires the old key at once.
        mvc.perform(next.post("/v1/api_keys/" + next.keyId() + "/roll", "{\"overlap_hours\":0}"))
                .andExpect(status().isCreated());
        mvc.perform(next.authorize(get("/v1/users"))).andExpect(status().isUnauthorized());
    }

    @Test
    void merchantManagedBalancesFundLiquidityWithoutAPlayerBalance() throws Exception {
        settings("{\"player_balances\":\"merchant_managed\"}");
        String userId = json.readTree(mvc.perform(merchant.post("/v1/users", userBody()))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        String address = json.readTree(mvc.perform(merchant.post("/v1/users/" + userId + "/deposit_addresses",
                        "{\"chain\":\"TRON\"}")).andReturn().getResponse().getContentAsString()).get("address").asString();

        mvc.perform(merchant.post("/v1/sandbox/simulate_deposit",
                        "{\"address\":\"%s\",\"asset\":\"USDT_TRON\",\"amount\":\"50\"}".formatted(address)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));

        assertThat(ledger.balance(AccountRef.user(AccountKind.USER_AVAILABLE, userId, "USDT_TRON"))).isZero();
        assertThat(ledger.balance(AccountRef.merchant(AccountKind.MERCHANT_USER_OBLIGATIONS, merchant.merchantId(),
                "USDT_TRON"))).isZero();
        assertThat(ledger.balance(AccountRef.merchant(AccountKind.MERCHANT_LIQUIDITY, merchant.merchantId(),
                "USDT_TRON"))).isEqualTo(BigInteger.valueOf(50_000_000));
    }
}
