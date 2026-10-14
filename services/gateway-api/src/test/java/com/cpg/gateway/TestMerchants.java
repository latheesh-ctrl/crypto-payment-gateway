package com.cpg.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Onboards a fresh merchant through the operator API and returns its id and secret key. */
public final class TestMerchants {

    public static final String ADMIN_TOKEN = "test-admin-token";

    public record Onboarded(String merchantId, String secret) {

        public MockHttpServletRequestBuilder authorize(MockHttpServletRequestBuilder request) {
            return request.header("Authorization", "Bearer " + secret);
        }

        /** A merchant POST with JSON body and a fresh idempotency key. */
        public MockHttpServletRequestBuilder post(String path, String body) {
            return authorize(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body);
        }
    }

    private TestMerchants() {
    }

    public static Onboarded onboard(MockMvc mvc, JsonMapper json) throws Exception {
        JsonNode merchant = adminPost(mvc, json, "/internal/v1/merchants",
                "{\"name\":\"Acme Ltd\",\"email\":\"%s@example.com\"}".formatted(UUID.randomUUID()));
        String merchantId = merchant.get("id").asString();
        JsonNode key = adminPost(mvc, json, "/internal/v1/merchants/" + merchantId + "/api-keys", "{\"name\":\"server\"}");
        return new Onboarded(merchantId, key.get("secret").asString());
    }

    private static JsonNode adminPost(MockMvc mvc, JsonMapper json, String path, String body) throws Exception {
        String response = mvc.perform(post(path)
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
