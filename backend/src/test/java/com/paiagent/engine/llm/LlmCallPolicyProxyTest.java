package com.paiagent.engine.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 配了 HTTP 代理时，请求要发给代理（请求行是绝对地址）；没配（主机为空或端口为 0）就直连。
 * 本地起一个假的"代理"，只看它收到的请求行，不依赖外网和 DNS。
 */
class LlmCallPolicyProxyTest {

    private static HttpServer startFakeProxy(AtomicReference<String> requestTarget) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestTarget.set(exchange.getRequestURI().toString());
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static int get(LlmCallPolicy policy, String url) throws Exception {
        ClientHttpRequest request = policy.requestFactory().createRequest(URI.create(url), HttpMethod.GET);
        try (ClientHttpResponse response = request.execute()) {
            return response.getStatusCode().value();
        }
    }

    @Test
    void configuredProxy_receivesTheRequestInAbsoluteForm() throws Exception {
        AtomicReference<String> target = new AtomicReference<>();
        HttpServer proxy = startFakeProxy(target);
        try {
            LlmCallPolicy policy = new LlmCallPolicy(2000, 2000, 1, 50, 100, "127.0.0.1", proxy.getAddress().getPort());

            // 这个主机名根本不存在：能拿到 200，只可能是代理替它转发（这里的假代理直接作答）
            assertEquals(200, get(policy, "http://llm.proxy-test.invalid/v1/ping"));
            assertEquals("http://llm.proxy-test.invalid/v1/ping", target.get());
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void withoutProxy_orWithAnIncompleteOne_connectsDirectly() throws Exception {
        AtomicReference<String> target = new AtomicReference<>();
        HttpServer server = startFakeProxy(target);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/direct";

            assertEquals(200, get(LlmCallPolicy.defaults(), url));
            assertEquals("/direct", target.get(), "直连时请求行是相对路径");

            target.set(null);
            assertEquals(200, get(new LlmCallPolicy(2000, 2000, 1, 50, 100, "127.0.0.1", 0), url));
            assertEquals("/direct", target.get(), "只配了主机没配端口，不应该走代理");
        } finally {
            server.stop(0);
        }
    }
}
