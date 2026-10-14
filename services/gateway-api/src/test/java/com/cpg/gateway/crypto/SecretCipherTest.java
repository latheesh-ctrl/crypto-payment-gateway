package com.cpg.gateway.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cpg.gateway.config.GatewayProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static SecretCipher cipher(byte[] key) {
        return new SecretCipher(new GatewayProperties(GatewayProperties.Mode.test,
                new GatewayProperties.Security("pepper", null, Base64.getEncoder().encodeToString(key)),
                new GatewayProperties.Webhooks(16), new GatewayProperties.Jobs(false)));
    }

    @Test
    void roundTripsAndUsesFreshIvEachTime() {
        SecretCipher c = cipher(new byte[32]);
        byte[] a = c.encrypt("whsec_abc");
        byte[] b = c.encrypt("whsec_abc");
        assertThat(a).isNotEqualTo(b);
        assertThat(c.decrypt(a)).isEqualTo("whsec_abc");
    }

    @Test
    void detectsTampering() {
        SecretCipher c = cipher(new byte[32]);
        byte[] blob = c.encrypt("whsec_abc");
        blob[blob.length - 1] ^= 1;
        assertThatThrownBy(() -> c.decrypt(blob)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKeyCannotDecrypt() {
        byte[] other = new byte[32];
        other[0] = 1;
        byte[] blob = cipher(new byte[32]).encrypt("whsec_abc");
        assertThatThrownBy(() -> cipher(other).decrypt(blob)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsShortKeys() {
        assertThatThrownBy(() -> cipher(new byte[16])).isInstanceOf(IllegalStateException.class);
    }
}
