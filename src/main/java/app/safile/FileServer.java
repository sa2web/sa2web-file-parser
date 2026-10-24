package app.safile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.internet.ContentDisposition;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class FileServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FILE_BYTES = 256 * 1024 * 1024;
    private final int maxBytes;
    private final int maxChars;
    private final int timeoutSeconds;
    private final String token;
    private final Semaphore slots;
    private final HttpServer server;

    public FileServer(String host, int port, String token, int maxBytes, int maxChars, int timeoutSeconds, int concurrency) throws Exception {
        requireRange("SA_FILE_MAX_BYTES", maxBytes, 1, MAX_FILE_BYTES);
        requireRange("SA_FILE_MAX_CHARS", maxChars, 1, 5000000);
        requireRange("SA_FILE_TIMEOUT_SECONDS", timeoutSeconds, 1, 120);
        requireRange("SA_FILE_CONCURRENCY", concurrency, 1, 8);
        this.token = token;
        this.maxBytes = maxBytes;
        this.maxChars = maxChars;
        this.timeoutSeconds = timeoutSeconds;
        this.slots = new Semaphore(concurrency);
        if (!Set.of("127.0.0.1", "localhost", "::1").contains(host) && token.isBlank()) {
            throw new IllegalArgumentException("SA_FILE_TOKEN is required for non-loopback binding");
        }
        server = HttpServer.create(new InetSocketAddress(host, port), 16);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health", x -> send(x, 200, Map.of("status", "UP")));
        server.createContext("/parse", this::handle);
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + " (received " + value + ")");
        }
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }
    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        if (!exchange.getRequestURI().getPath().equals("/parse")) { send(exchange, 404, Map.of("status", "FAILED")); return; }
        if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, Map.of("status", "FAILED")); return; }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String headerToken = exchange.getRequestHeaders().getFirst("X-DLP-Authorization");
        String presented = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : Objects.toString(headerToken, "");
        if (!token.isBlank() && !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
            send(exchange, 401, Map.of("status", "FAILED", "errorCode", "UNAUTHORIZED")); return;
        }
        if (!slots.tryAcquire()) { send(exchange, 429, Map.of("status", "FAILED", "errorCode", "BUSY")); return; }
        Path temp = null;
        try {
            byte[] request = exchange.getRequestBody().readNBytes(maxBytes + 65537);
            if (request.length > maxBytes + 65536) { send(exchange, 413, Map.of("status", "LIMIT_EXCEEDED")); return; }
            String type = Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "");
            if (!type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data;")) {
                send(exchange, 415, Map.of("status", "FAILED", "errorCode", "MULTIPART_REQUIRED")); return;
            }
            MimeMultipart multipart = new MimeMultipart(new ByteArrayDataSource(request, type));
            if (multipart.getCount() > 8) throw new IllegalArgumentException("Too many parts");
            byte[] bytes = null;
            String filename = "document";
            for (int i = 0; i < multipart.getCount(); i++) {
                var part = multipart.getBodyPart(i);
                String disposition = part.getHeader("Content-Disposition")[0];
                String name = new ContentDisposition(disposition).getParameter("name");
                if ("file".equals(name)) {
                    if (bytes != null) throw new IllegalArgumentException("Duplicate file part");
                    bytes = part.getInputStream().readNBytes(maxBytes + 1);
                    if (part.getFileName() != null) filename = part.getFileName();
                } else if ("filename".equals(name)) {
                    filename = new String(part.getInputStream().readNBytes(1025), StandardCharsets.UTF_8);
                }
            }
            if (bytes == null || bytes.length == 0) { send(exchange, 400, Map.of("status", "FAILED", "errorCode", "FILE_REQUIRED")); return; }
            if (bytes.length > maxBytes) { send(exchange, 413, Map.of("status", "LIMIT_EXCEEDED")); return; }
            // Original names are metadata only, never filesystem paths.
            filename = filename.replace('/', '_').replace('\\', '_').replace('\r', '_').replace('\n', '_');
            if (filename.length() > 512) throw new IllegalArgumentException("Filename too long");
            temp = Files.createTempDirectory("sa-file-");
            Path input = temp.resolve("input.bin"), output = temp.resolve("response.json");
            Files.write(input, bytes);
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m", "-Djava.awt.headless=true", "-cp", System.getProperty("sa.file.classpath", System.getProperty("java.class.path")),
                    ParseWorker.class.getName(), input.toString(), filename, Integer.toString(maxChars), output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS);
                send(exchange, 200, Map.of("status", "FAILED", "errorCode", "TIMEOUT")); return;
            }
            if (child.exitValue() != 0 || Files.size(output) > (long) maxChars * 6 + 65536) {
                send(exchange, 200, Map.of("status", "FAILED", "errorCode", "WORKER_FAILED")); return;
            }
            Map<?, ?> result = JSON.readValue(Files.readAllBytes(output), Map.class);
            send(exchange, 200, result);
        } catch (Exception ignored) {
            send(exchange, 200, Map.of("status", "FAILED", "errorCode", "INVALID_REQUEST_OR_PARSE_FAILURE"));
        } finally {
            slots.release();
            if (temp != null) {
                try (var paths = Files.walk(temp)) {
                    for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                } catch (Exception ignored) { /* No content or path is logged. */ }
            }
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, Object body) throws java.io.IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    public static void main(String[] args) throws Exception {
        var env = System.getenv();
        FileServer service = new FileServer(env.getOrDefault("SA_FILE_HOST", "127.0.0.1"),
                Integer.parseInt(env.getOrDefault("SA_FILE_PORT", "4190")), env.getOrDefault("SA_FILE_TOKEN", ""),
                Integer.parseInt(env.getOrDefault("SA_FILE_MAX_BYTES", "20971520")),
                Integer.parseInt(env.getOrDefault("SA_FILE_MAX_CHARS", "1000000")),
                Integer.parseInt(env.getOrDefault("SA_FILE_TIMEOUT_SECONDS", "25")),
                Integer.parseInt(env.getOrDefault("SA_FILE_CONCURRENCY", "2")));
        service.start();
        Runtime.getRuntime().addShutdownHook(new Thread(service::stop));
        System.out.println("sa_file listening on port " + service.port());
    }
}
