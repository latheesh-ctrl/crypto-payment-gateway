package com.cpg.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** Guards against the main application.yml not being on the classpath root. */
@IntegrationTest
class ApplicationConfigIT {

    @Autowired Environment env;

    @Test
    void loadsMainApplicationConfig() {
        assertThat(env.getProperty("spring.application.name")).isEqualTo("gateway-api");
        assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(env.getProperty("spring.threads.virtual.enabled")).isEqualTo("true");
    }
}
