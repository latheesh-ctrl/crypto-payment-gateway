package com.cpg.gateway.deposit;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test stand-in for the independent chain verifier. */
public class ControllableVerifier implements TransferVerifier {

    public final AtomicReference<Result> next = new AtomicReference<>(Result.CONFIRMED);

    @Override
    public Result verify(Deposit deposit) {
        return next.get();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        ControllableVerifier controllableVerifier() {
            return new ControllableVerifier();
        }
    }
}
