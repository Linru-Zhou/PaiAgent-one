package com.paiagent.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolcengineAgentPlanClientTest {

    private final VolcengineAgentPlanClient client = new VolcengineAgentPlanClient();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private int status = 200;
    private String body = "{}";

    @BeforeEach
    void startFakeProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            if (status == 204) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeProvider() {
        server.stop(0);
    }

    private ResolvedAgentPlanConfig config() {
        return new ResolvedAgentPlanConfig(1L, "volcengine",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3", "secret-key", "m",
                null, null, null, false);
    }

    @Test
    void cancelVideoTask_SendsDeleteToTheTaskUrlWithBearerKey() throws Exception {
        client.cancelVideoTask(config(), "cgt-123");

        assertEquals(List.of("DELETE /api/v3/contents/generations/tasks/cgt-123 auth=Bearer secret-key"), requests);
    }

    @Test
    void cancelVideoTask_NoContentResponse_Succeeds() throws Exception {
        status = 204;

        client.cancelVideoTask(config(), "cgt-123");

        assertEquals(1, requests.size());
    }

    @Test
    void cancelVideoTask_ProviderRejects_ThrowsWithStatusAndBody() {
        status = 400;
        body = "{\"error\":\"task is running\"}";

        IOException exception = assertThrows(IOException.class, () -> client.cancelVideoTask(config(), "cgt-123"));

        assertTrue(exception.getMessage().contains("HTTP 400"));
        assertTrue(exception.getMessage().contains("task is running"));
    }
}
