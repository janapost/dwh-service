package kz.janapost.dwhservice.testsupport;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Локальный HTTP-сервер вместо внешнего API (AppsFlyer): запоминает запросы, отвечает заданным статусом,
 * может «зависнуть» на заданное время. На JDK HttpServer — без лишних зависимостей.
 */
public final class FakeHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "ok";
    private volatile long delayMs;

    public FakeHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // каждый запрос в своём потоке: «зависший» ответ одного теста не задерживает запросы следующего
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()), body));
            sleep(delayMs);
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        return requests;
    }

    public void respond(int newStatus, String newBody) {
        this.status = newStatus;
        this.responseBody = newBody;
    }

    public void delay(long millis) {
        this.delayMs = millis;
    }

    public void reset() {
        requests.clear();
        status = 200;
        responseBody = "ok";
        delayMs = 0;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Заголовки — как их отдаёт JDK HttpServer (имя с заглавной буквы). */
    public record Request(String method, String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0))
                    .findFirst()
                    .orElse(null);
        }
    }
}
