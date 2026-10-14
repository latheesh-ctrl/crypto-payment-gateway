package com.cpg.gateway.idempotency;

import com.cpg.gateway.apikey.MerchantPrincipal;
import com.cpg.gateway.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes every state-changing merchant API call safe to retry.
 *
 * <p>Each {@code POST} under {@code /v1/} must carry an {@code Idempotency-Key}. The key is claimed in the
 * database before the request runs. A retry with the same key and body replays the stored response; the same
 * key with a different body is rejected; a retry while the first attempt is still running gets 409. Server
 * errors release the key so the client can retry, because the transaction behind them rolled back.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final IdempotencyStore store;
    private final JsonMapper json;

    public IdempotencyFilter(IdempotencyStore store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<MerchantPrincipal> merchant = currentMerchant();
        if (merchant.isEmpty()) {
            // Unauthenticated: let Spring Security answer with 401.
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank() || key.length() > 255) {
            problem(response, HttpStatus.BAD_REQUEST, "idempotency_key_required",
                    "POST requests require an Idempotency-Key header of 1-255 characters");
            return;
        }

        CachedBodyRequest cached;
        try {
            cached = CachedBodyRequest.wrap(request, MAX_BODY_BYTES);
        } catch (IOException e) {
            problem(response, HttpStatus.CONTENT_TOO_LARGE, "request_too_large", "Request body is too large");
            return;
        }
        byte[] hash = hash(request.getMethod(), request.getRequestURI(), cached.body());
        String merchantId = merchant.get().merchantId();

        if (!store.claim(merchantId, key, request.getMethod(), request.getRequestURI(), hash)) {
            replayOrReject(merchantId, key, hash, response);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(cached, wrapped);
            if (wrapped.getStatus() < 500) {
                store.complete(merchantId, key, wrapped.getStatus(), wrapped.getContentAsByteArray(),
                        wrapped.getContentType());
                completed = true;
            }
        } finally {
            if (!completed) {
                store.release(merchantId, key);
            }
            wrapped.copyBodyToResponse();
        }
    }

    private void replayOrReject(String merchantId, String key, byte[] hash, HttpServletResponse response)
            throws IOException {
        IdempotencyStore.Record existing = store.find(merchantId, key).orElse(null);
        if (existing == null) {
            // Released between our claim attempt and this read; the client can simply retry.
            problem(response, HttpStatus.CONFLICT, "idempotency_request_in_progress",
                    "A request with this Idempotency-Key is being processed; retry shortly");
        } else if (!Arrays.equals(existing.requestHash(), hash)) {
            problem(response, HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused",
                    "This Idempotency-Key was already used with a different request");
        } else if (existing.responseStatus() == null) {
            problem(response, HttpStatus.CONFLICT, "idempotency_request_in_progress",
                    "A request with this Idempotency-Key is being processed; retry shortly");
        } else {
            response.setStatus(existing.responseStatus());
            if (existing.responseType() != null) {
                response.setContentType(existing.responseType());
            }
            response.setHeader(REPLAYED_HEADER, "true");
            response.getOutputStream().write(existing.responseBody());
        }
    }

    private void problem(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), Problems.of(status, code, detail));
    }

    private static Optional<MerchantPrincipal> currentMerchant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof MerchantPrincipal p ? Optional.of(p) : Optional.empty();
    }

    private static byte[] hash(String method, String path, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((method + " " + path + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
