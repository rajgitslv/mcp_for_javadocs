package com.mcp.mcpforjavadoc.mcpserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class JdkDocServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String BASE_URL = "https://docs.oracle.com/en/java/javase/24/docs/api/java.base/";

    public static void main(String[] args) throws IOException {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/query", JdkDocServer::handleQuery);
        server.setExecutor(executor);
        System.out.println("✅ Server running on http://localhost:8080/query?topic=java.util.stream.Stream.map");
        server.start();
    }

    private static void handleQuery(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        var topic = getQueryParam(exchange.getRequestURI().getQuery(), "topic");
        DocInfo result;
        try {
            if (topic == null || topic.isBlank())
                throw new IllegalArgumentException("Missing 'topic' query parameter");
            result = fetchJavadocInfo(topic);
        } catch (Exception e) {
            result = new DocInfo(topic, "", "", "", "", "❌ " + e.getMessage());
        }

        byte[] json = MAPPER.writeValueAsBytes(result);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, json.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json);
        }
    }

    private static String getQueryParam(String query, String key) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            var parts = kv.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static DocInfo fetchJavadocInfo(String topic) throws Exception {
        // Split "java.util.stream.Stream.map"
        int idx = topic.lastIndexOf('.');
        if (idx < 0) throw new IllegalArgumentException("Topic must be ClassName.methodName");
        var className = topic.substring(0, idx);
        var methodName = topic.substring(idx + 1);

        // Construct Javadoc URL
        var path = className.replace('.', '/') + ".html";
        var url = BASE_URL + path;

        // Fetch HTML
        var request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        var body = HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
        Document doc = Jsoup.parse(body);

        // Attempt to find method anchor or <pre> with signature
        String signature = "";
        String description = "";
        String params = "";
        String returns = "";

        // Oracle Javadoc anchor names look like map(java.util.function.Function)
        Elements methodBlocks = doc.select("section.detail");
        for (Element sec : methodBlocks) {
            if (sec.select("h3").text().startsWith(methodName + "(")) {
                signature = sec.select("pre").text();
                description = sec.select("div.block").text();
                for (Element dt : sec.select("dl dt")) {
                    switch (dt.text()) {
                        case "Parameters" -> params = dt.nextElementSibling().text();
                        case "Returns" -> returns = dt.nextElementSibling().text();
                    }
                }
                break;
            }
        }

        if (signature.isBlank())
            throw new IOException("Method '" + methodName + "' not found on page");

        return new DocInfo(topic, signature, description, params, returns, "✅ success");
    }

    // Modern Java record for JSON serialization
    record DocInfo(String topic, String signature, String description,
                   String parameters, String returns, String status) {}
}
