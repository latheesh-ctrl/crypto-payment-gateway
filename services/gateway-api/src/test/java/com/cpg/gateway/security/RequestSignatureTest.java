package com.cpg.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Fixed vector published in the integration guide so merchants can check their signing code. */
class RequestSignatureTest {

    static final String SECRET = "ss_test_vector_secret";
    static final byte[] BODY = "{\"player_ref\":\"p1\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void canonicalStringAndSignatureMatchThePublishedVector() {
        assertThat(RequestSignature.canonical(1760464800L, "nonce-0000000000001", "post", "/v1/users?x=1", BODY))
                .isEqualTo("1760464800\nnonce-0000000000001\nPOST\n/v1/users?x=1\n"
                        + RequestSignature.sha256Hex(BODY));
        assertThat(RequestSignature.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        String header = RequestSignature.sign(SECRET, 1760464800L, "nonce-0000000000001", "POST", "/v1/users?x=1", BODY);
        // Independently computed with Python's hmac module.
        assertThat(header).isEqualTo("v1=30222a073cfafff095d00d1a19f9e4a8cb9db12fb2c6525ceee2753d397c461a");
        assertThat(RequestSignature.matches(" " + header + " ", header)).isTrue();
        assertThat(RequestSignature.matches(header.substring(0, header.length() - 1) + "0", header)).isFalse();
    }
}
