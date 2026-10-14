package com.cpg.gateway.apikey;

/** The authenticated caller of a merchant API request. */
public record MerchantPrincipal(String merchantId, String apiKeyId, String mode) {
}
