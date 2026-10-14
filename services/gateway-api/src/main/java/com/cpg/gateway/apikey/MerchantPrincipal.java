package com.cpg.gateway.apikey;

import java.util.Set;

/** The authenticated caller of a merchant API request. */
public record MerchantPrincipal(String merchantId, String apiKeyId, String mode, Set<String> scopes) {
}
