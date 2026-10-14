package com.cpg.gateway.chain;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/** Local HTTP server answering JSON-RPC methods or plain JSON paths with canned responses. */
public final class FakeRpcServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, Function<JsonNode, Object>> methods = new ConcurrentHashMap<>();
    private final Map<String, Function<JsonNode, Object>> paths = new ConcurrentHashMap<>();
    final List<Map<String, List<String>>> requestHeaders = new CopyOnWriteArrayList<>();

    public FakeRpcServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestHeaders.add(Map.copyOf(exchange.getRequestHeaders()));
            JsonNode request = RpcTransport.JSON.readTree(exchange.getRequestBody().readAllBytes());
            String path = exchange.getRequestURI().getPath();
            Object response;
            Function<JsonNode, Object> byPath = paths.get(path);
            if (byPath != null) {
                response = byPath.apply(request);
            } else {
                Function<JsonNode, Object> method = methods.get(request.path("method").asString());
                Object result;
                try {
                    result = method == null ? new RpcError("no such method") : method.apply(request.path("params"));
                } catch (RpcError e) {
                    result = e;
                }
                response = result instanceof RpcError e
                        ? Map.of("jsonrpc", "2.0", "id", request.path("id").asLong(),
                                 "error", Map.of("code", -32000, "message", e.getMessage()))
                        : rpcResult(request, result);
            }
            byte[] body = RpcTransport.JSON.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private static Object rpcResult(JsonNode request, Object result) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", request.path("id").asLong());
        out.put("result", result);
        return out;
    }

    /** Thrown by a handler to answer with a JSON-RPC error carrying this message. */
    public static final class RpcError extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public RpcError(String message) {
            super(message);
        }
    }

    /** JSON-RPC method returning the given result (the handler receives {@code params}). */
    public FakeRpcServer method(String name, Function<JsonNode, Object> handler) {
        methods.put(name, handler);
        return this;
    }

    FakeRpcServer path(String path, Function<JsonNode, Object> handler) {
        paths.put(path, handler);
        return this;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
