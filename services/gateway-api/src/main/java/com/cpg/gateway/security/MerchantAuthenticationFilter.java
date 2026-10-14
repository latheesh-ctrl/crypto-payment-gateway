package com.cpg.gateway.security;

import com.cpg.gateway.apikey.ApiKeyService;
import com.cpg.gateway.idempotency.CachedBodyRequest;
import com.cpg.gateway.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authenticates merchant API calls, in order: bearer key → source IP allowlist → rate limit → request signature
 * (timestamp window, HMAC, single-use nonce). Signatures are required on live keys and for merchants that opt in;
 * a signature that is present is always verified. A request that fails any step never reaches a controller.
 */
public class MerchantAuthenticationFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final String NONCE_PATTERN = "[A-Za-z0-9_-]{16,128}";

    private final ApiKeyService apiKeys;
    private final RequestNonceStore nonces;
    private final RateLimiter rateLimiter;
    private final JsonMapper json;

    public MerchantAuthenticationFilter(ApiKeyService apiKeys, RequestNonceStore nonces, RateLimiter rateLimiter,
                                        JsonMapper json) {
        this.apiKeys = apiKeys;
        this.nonces = nonces;
        this.rateLimiter = rateLimiter;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        Optional<ApiKeyService.Authenticated> found = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? apiKeys.authenticate(header.substring(7).strip())
                : Optional.empty();
        if (found.isEmpty()) {
            chain.doFilter(request, response); // Spring Security answers 401
            return;
        }
        ApiKeyService.Authenticated key = found.get();
        String keyId = key.principal().apiKeyId();

        if (key.ipAllowlist() != null
                && key.ipAllowlist().stream().noneMatch(cidr -> new IpAddressMatcher(cidr).matches(request))) {
            problem(response, HttpStatus.FORBIDDEN, "ip_not_allowed", "Requests from this address are not allowed for this key");
            return;
        }

        boolean payouts = request.getRequestURI().startsWith("/v1/payouts");
        int rate = payouts ? Math.max(1, key.rateLimitPerSecond() / 5) : key.rateLimitPerSecond();
        long waitMillis = rateLimiter.tryAcquire(keyId + (payouts ? ":payouts" : ":default"), rate);
        if (waitMillis > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, (waitMillis + 999) / 1000)));
            problem(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests for this key");
            return;
        }

        HttpServletRequest forward = request;
        String signature = request.getHeader(RequestSignature.SIGNATURE_HEADER);
        if (key.signatureRequired() || signature != null) {
            if (signature == null) {
                problem(response, HttpStatus.UNAUTHORIZED, "signature_required",
                        "Requests with this key must be signed (CPG-Timestamp, CPG-Nonce, CPG-Signature)");
                return;
            }
            if (key.signingSecretEncrypted() == null) {
                problem(response, HttpStatus.UNAUTHORIZED, "signature_unavailable",
                        "This key was issued before request signing; roll it to get a signing secret");
                return;
            }
            long timestamp;
            try {
                timestamp = Long.parseLong(String.valueOf(request.getHeader(RequestSignature.TIMESTAMP_HEADER)));
            } catch (NumberFormatException e) {
                problem(response, HttpStatus.UNAUTHORIZED, "invalid_timestamp", "CPG-Timestamp must be Unix seconds");
                return;
            }
            if (Math.abs(System.currentTimeMillis() / 1000 - timestamp) > RequestSignature.TOLERANCE_SECONDS) {
                problem(response, HttpStatus.UNAUTHORIZED, "timestamp_out_of_range",
                        "CPG-Timestamp is more than 300 seconds from the server's clock");
                return;
            }
            String nonce = request.getHeader(RequestSignature.NONCE_HEADER);
            if (nonce == null || !nonce.matches(NONCE_PATTERN)) {
                problem(response, HttpStatus.UNAUTHORIZED, "invalid_nonce",
                        "CPG-Nonce must be 16-128 characters of A-Z, a-z, 0-9, '_' or '-'");
                return;
            }
            CachedBodyRequest cached;
            try {
                cached = CachedBodyRequest.wrap(request, MAX_BODY_BYTES);
            } catch (IOException e) {
                problem(response, HttpStatus.CONTENT_TOO_LARGE, "request_too_large", "Request body is too large");
                return;
            }
            String target = request.getQueryString() == null ? request.getRequestURI()
                    : request.getRequestURI() + "?" + request.getQueryString();
            String expected = RequestSignature.sign(apiKeys.signingSecret(key.signingSecretEncrypted()), timestamp,
                    nonce, request.getMethod(), target, cached.body());
            if (!RequestSignature.matches(signature, expected)) {
                problem(response, HttpStatus.UNAUTHORIZED, "invalid_signature", "Request signature does not match");
                return;
            }
            // Claimed only after the signature checks out, so forged requests cannot burn a merchant's nonces.
            if (!nonces.claim(keyId, nonce)) {
                problem(response, HttpStatus.UNAUTHORIZED, "nonce_reused", "This nonce was already used");
                return;
            }
            forward = cached;
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_MERCHANT"));
        key.principal().scopes().forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(key.principal(), null, authorities));
        chain.doFilter(forward, response);
    }

    private void problem(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), Problems.of(status, code, detail));
    }
}
