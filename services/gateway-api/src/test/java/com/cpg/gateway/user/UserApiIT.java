package com.cpg.gateway.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cpg.gateway.IntegrationTest;
import com.cpg.gateway.TestMerchants;
import com.cpg.gateway.ledger.AccountKind;
import com.cpg.gateway.ledger.AccountRef;
import com.cpg.gateway.ledger.LedgerService;
import com.cpg.gateway.ledger.Posting;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class UserApiIT {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired LedgerService ledger;
    @Autowired JdbcClient jdbc;

    private TestMerchants.Onboarded merchant;

    @BeforeEach
    void onboard() throws Exception {
        merchant = TestMerchants.onboard(mvc, json);
    }

    private String createUser(String externalRef) throws Exception {
        String body = mvc.perform(merchant.post("/v1/users",
                        "{\"external_ref\":\"%s\",\"metadata\":{\"tier\":\"gold\"}}".formatted(externalRef)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.external_ref").value(externalRef))
                .andExpect(jsonPath("$.merchant_id").value(merchant.merchantId()))
                .andExpect(jsonPath("$.metadata.tier").value("gold"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    @Test
    void createsAndFetchesUser() throws Exception {
        String id = createUser("player-1");
        mvc.perform(merchant.authorize(get("/v1/users/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
        mvc.perform(merchant.authorize(get("/v1/users").param("external_ref", "player-1")))
                .andExpect(jsonPath("$.data[0].id").value(id));
    }

    @Test
    void externalRefIsUniquePerMerchant() throws Exception {
        createUser("dup");
        mvc.perform(merchant.post("/v1/users", "{\"external_ref\":\"dup\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user_exists"));

        // A different merchant may use the same reference for its own user.
        TestMerchants.Onboarded other = TestMerchants.onboard(mvc, json);
        mvc.perform(other.post("/v1/users", "{\"external_ref\":\"dup\"}")).andExpect(status().isCreated());
    }

    @Test
    void merchantsCannotSeeEachOthersUsers() throws Exception {
        String id = createUser("private");
        TestMerchants.Onboarded other = TestMerchants.onboard(mvc, json);
        mvc.perform(other.authorize(get("/v1/users/" + id))).andExpect(status().isNotFound());
        mvc.perform(other.authorize(get("/v1/users/" + id + "/balances"))).andExpect(status().isNotFound());
    }

    @Test
    void paginatesNewestFirst() throws Exception {
        String first = createUser("p-a");
        String second = createUser("p-b");
        String third = createUser("p-c");
        mvc.perform(merchant.authorize(get("/v1/users").param("limit", "2")))
                .andExpect(jsonPath("$.data[0].id").value(third))
                .andExpect(jsonPath("$.data[1].id").value(second))
                .andExpect(jsonPath("$.has_more").value(true));
        mvc.perform(merchant.authorize(get("/v1/users").param("limit", "2").param("starting_after", second)))
                .andExpect(jsonPath("$.data[0].id").value(first))
                .andExpect(jsonPath("$.has_more").value(false));
    }

    @Test
    void userBalancesComeFromTheLedger() throws Exception {
        String id = createUser("rich");
        ledger.post(new Posting("deposit", "user-dep-" + id, null, List.of(
                Posting.Entry.debit(AccountRef.platform(AccountKind.PLATFORM_DEPOSIT_WALLETS, "USDT_TRON"),
                        BigInteger.valueOf(25_000_000)),
                Posting.Entry.credit(AccountRef.user(AccountKind.USER_AVAILABLE, id, "USDT_TRON"),
                        BigInteger.valueOf(25_000_000)))));

        mvc.perform(merchant.authorize(get("/v1/users/" + id + "/balances")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].asset").value("USDT_TRON"))
                .andExpect(jsonPath("$.data[0].available").value("25"))
                .andExpect(jsonPath("$.data[0].reserved").value("0"));
    }

    @Test
    void userBalanceCannotGoNegative() throws Exception {
        String id = createUser("broke");
        assertThatThrownBy(() -> ledger.post(new Posting("cashout", "neg-" + id, null, List.of(
                Posting.Entry.debit(AccountRef.user(AccountKind.USER_AVAILABLE, id, "USDT_TRON"), BigInteger.ONE),
                Posting.Entry.credit(AccountRef.platform(AccountKind.PLATFORM_HOT_WALLET, "USDT_TRON"), BigInteger.ONE)))))
                .hasMessageContaining("negative");
    }

    @Test
    void userCreationIsAuditedAndEmitsAnEvent() throws Exception {
        String id = createUser("audited");
        assertThat(jdbc.sql("SELECT actor_type || ':' || action FROM audit_log WHERE target_id = :id")
                .param("id", id).query(String.class).single()).isEqualTo("merchant:user.created");
        assertThat(jdbc.sql("SELECT event_type FROM outbox_events WHERE aggregate_id = :id AND merchant_id = :m")
                .param("id", id).param("m", merchant.merchantId()).query(String.class).single())
                .isEqualTo("user.created");
    }

    @Test
    void auditLogIsAppendOnly() {
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_log").update()).hasMessageContaining("append-only");
    }

    @Test
    void rejectsInvalidExternalRef() throws Exception {
        mvc.perform(merchant.post("/v1/users", "{\"external_ref\":\"has space\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }
}
