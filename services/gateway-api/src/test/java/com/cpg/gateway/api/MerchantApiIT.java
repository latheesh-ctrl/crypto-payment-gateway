package com.cpg.gateway.api;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cpg.gateway.IntegrationTest;
import com.cpg.gateway.ledger.AccountKind;
import com.cpg.gateway.ledger.AccountRef;
import com.cpg.gateway.ledger.LedgerService;
import com.cpg.gateway.ledger.Posting;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class MerchantApiIT {

    private static final String ADMIN = "test-admin-token";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired LedgerService ledger;

    private JsonNode postJson(String path, String body) throws Exception {
        String response = mvc.perform(post(path)
                        .header("X-Admin-Token", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private String[] onboardMerchant() throws Exception {
        JsonNode merchant = postJson("/internal/v1/merchants",
                "{\"name\":\"Acme Ltd\",\"email\":\"%s@example.com\",\"country\":\"AE\"}".formatted(UUID.randomUUID()));
        String merchantId = merchant.get("id").asString();
        JsonNode key = postJson("/internal/v1/merchants/" + merchantId + "/api-keys", "{\"name\":\"server\"}");
        return new String[] {merchantId, key.get("secret").asString()};
    }

    @Test
    void operatorOnboardsMerchantWhoCanThenCallTheApi() throws Exception {
        String secret = onboardMerchant()[1];

        mvc.perform(get("/v1/assets").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", hasItem("USDT_TRON")))
                .andExpect(jsonPath("$.data[*].id", hasItem("BTC_BITCOIN")))
                .andExpect(jsonPath("$.data[*].id", hasItem("USDC_ARBITRUM")));
    }

    @Test
    void issuedSecretHasTestModePrefix() throws Exception {
        org.assertj.core.api.Assertions.assertThat(onboardMerchant()[1]).startsWith("sk_test_").hasSize(51);
    }

    @Test
    void balancesReflectLedgerInDecimalUnits() throws Exception {
        String[] m = onboardMerchant();
        ledger.post(new Posting("deposit", "api-dep-" + m[0], null, List.of(
                Posting.Entry.debit(AccountRef.platform(AccountKind.PLATFORM_DEPOSIT_WALLETS, "USDT_TRON"),
                        BigInteger.valueOf(12_500_000)),
                Posting.Entry.credit(AccountRef.merchant(AccountKind.MERCHANT_AVAILABLE, m[0], "USDT_TRON"),
                        BigInteger.valueOf(12_500_000)))));

        mvc.perform(get("/v1/balances").header("Authorization", "Bearer " + m[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].asset").value("USDT_TRON"))
                .andExpect(jsonPath("$.data[0].available").value("12.5"))
                .andExpect(jsonPath("$.data[0].pending").value("0"));
    }

    @Test
    void missingOrWrongApiKeyIsRejectedAsProblemJson() throws Exception {
        mvc.perform(get("/v1/assets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
        mvc.perform(get("/v1/assets").header("Authorization", "Bearer sk_test_not-a-real-key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/assets").header("Authorization", "Bearer sk_live_wrong-environment"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalRoutesRequireOperatorToken() throws Exception {
        mvc.perform(post("/internal/v1/merchants").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        // Merchant API keys carry no weight on operator routes.
        String secret = onboardMerchant()[1];
        mvc.perform(post("/internal/v1/merchants")
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/merchants")
                        .header("X-Admin-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidInputReturnsValidationProblem() throws Exception {
        mvc.perform(post("/internal/v1/merchants")
                        .header("X-Admin-Token", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"nope\",\"country\":\"uae\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors.length()").value(3));
    }

    @Test
    void duplicateEmailIsAConflict() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        postJson("/internal/v1/merchants", "{\"name\":\"A\",\"email\":\"%s\"}".formatted(email));
        mvc.perform(post("/internal/v1/merchants")
                        .header("X-Admin-Token", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"B\",\"email\":\"%s\"}".formatted(email.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("email_taken"));
    }

    @Test
    void unknownRoutesAreDenied() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().is(startsWith4xx()));
    }

    private static org.hamcrest.Matcher<Integer> startsWith4xx() {
        return org.hamcrest.Matchers.both(org.hamcrest.Matchers.greaterThanOrEqualTo(400))
                .and(org.hamcrest.Matchers.lessThan(500));
    }
}
