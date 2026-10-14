package com.cpg.gateway.chain;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON over HTTP to a node or RPC provider: JSON-RPC 2.0 calls (EVM, Bitcoin) and plain JSON POSTs (Tron HTTP
 * API). Credentials in the URL's user-info become a Basic Authorization header and are never logged.
 */
final class RpcTransport {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Floats (Bitcoin amounts) are read as exact decimals. */
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final URI endpoint;
    private final String safeName;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    RpcTransport(String url, Map<String, String> extraHeaders) {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw new IllegalArgumentException("RPC URL must be http(s)");
        }
        if (uri.getRawUserInfo() != null) {
            headers.put("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(java.net.URLDecoder.decode(uri.getRawUserInfo(), StandardCharsets.UTF_8)
                            .getBytes(StandardCharsets.UTF_8)));
            uri = URI.create(uri.getScheme() + "://" + uri.getRawAuthority().substring(uri.getRawAuthority().indexOf('@') + 1)
                    + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        }
        this.endpoint = uri;
        this.safeName = uri.getHost();
        headers.putAll(extraHeaders);
    }

    /** JSON-RPC 2.0 call; returns {@code result}, which may be a JSON null. */
    JsonNode call(String method, Object... params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", ids.incrementAndGet());
        body.put("method", method);
        body.put("params", List.of(params));
        JsonNode response = send(endpoint, body);
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            throw new ChainRpcException(method + " failed on " + safeName + ": " + error.path("message").asString());
        }
        if (!response.has("result")) {
            throw new ChainRpcException(method + " on " + safeName + " returned no result");
        }
        return response.get("result");
    }

    /** Plain JSON POST to a path below the endpoint (Tron HTTP API). */
    JsonNode post(String path, Object body) {
        String base = endpoint.toString();
        return send(URI.create(base.endsWith("/") ? base + path.substring(1) : base + path), body);
    }

    private JsonNode send(URI uri, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            headers.forEach(request::header);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            // bitcoind answers JSON-RPC errors with HTTP 500 and a JSON body; let the caller read the error.
            if (response.statusCode() / 100 != 2 && !(response.statusCode() == 500 && response.body().startsWith("{"))) {
                throw new ChainRpcException("HTTP " + response.statusCode() + " from " + safeName);
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new ChainRpcException("cannot reach " + safeName + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChainRpcException("interrupted calling " + safeName, e);
        } catch (JacksonException e) {
            throw new ChainRpcException("unreadable response from " + safeName, e);
        }
    }

    @Override
    public String toString() {
        return safeName;
    }
}
