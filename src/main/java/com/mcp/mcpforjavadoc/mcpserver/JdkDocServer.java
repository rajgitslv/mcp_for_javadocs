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
    private static final String BASE_URL = "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/";

    public static void main(String[] args) throws IOException {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
        server.createContext("/query", JdkDocServer::handleQuery);
        server.setExecutor(executor);
        System.out.println("✅ Server running on http://localhost:8081/query?topic=java.util.stream.Stream.map");
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
        int idx = topic.lastIndexOf('.');
        if (idx < 0) throw new IllegalArgumentException("Topic must be ClassName.methodName");
        var className = topic.substring(0, idx);
        var methodName = topic.substring(idx + 1);

        var path = className.replace('.', '/') + ".html";
        var url = BASE_URL + path;

        var request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        var body = HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
        Document doc = Jsoup.parse(body);

        String signature = "";
        String description = "";
        String params = "";
        String returns = "";

        Element methodSection = null;
        
        // Try multiple strategies to find the method
        // 1. Look for sections with method name (handle overloads)
        Elements sections = doc.select("section");
        for (Element section : sections) {
            String id = section.id();
            if (id.equals(methodName + "()") || id.startsWith(methodName + "(")) {
                methodSection = section;
                break;
            }
        }
        
        // 2. Look for method-detail class with method name
        if (methodSection == null) {
            Elements methodDetails = doc.select(".method-detail");
            for (Element detail : methodDetails) {
                Element heading = detail.selectFirst("h4");
                if (heading != null && heading.text().contains(methodName)) {
                    methodSection = detail;
                    break;
                }
            }
        }
        
        // 3. Look for headings containing method name
        if (methodSection == null) {
            Elements headings = doc.select("h3, h4");
            for (Element h : headings) {
                if (h.text().contains(methodName + "(") || h.text().equals(methodName)) {
                    methodSection = h.parent();
                    break;
                }
            }
        }
        
        // 4. Look for sections by ID pattern
        if (methodSection == null) {
            Elements sectionsByPattern = doc.select("section[id*=" + methodName + "]");
            if (!sectionsByPattern.isEmpty()) {
                methodSection = sectionsByPattern.first();
            }
        }
        
        // 5. Fallback: search for any element containing the method name
        if (methodSection == null) {
            Elements allElements = doc.select("*:containsOwn(" + methodName + ")");
            for (Element elem : allElements) {
                if (elem.tagName().equals("h4") || elem.tagName().equals("h3")) {
                    methodSection = elem.parent();
                    break;
                }
            }
        }

        if (methodSection != null) {
            // Extract signature from pre tag or code tag
            Element preElement = methodSection.selectFirst("pre");
            if (preElement != null) {
                signature = preElement.text();
            } else {
                Element codeElement = methodSection.selectFirst("code");
                if (codeElement != null) {
                    signature = codeElement.text();
                }
            }
            
            // Extract description
            Element descBlock = methodSection.selectFirst("div.block");
            if (descBlock != null) {
                description = descBlock.text();
            } else {
                // Fallback: look for first paragraph after heading
                Element p = methodSection.selectFirst("p");
                if (p != null) {
                    description = p.text();
                }
            }
            
            // Extract parameters and returns
            Elements dts = methodSection.select("dl dt");
            for (Element dt : dts) {
                String text = dt.text();
                Element dd = dt.nextElementSibling();
                if (dd != null) {
                    if (text.contains("Parameters")) {
                        params = dd.text();
                    } else if (text.contains("Returns")) {
                        returns = dd.text();
                    }
                }
            }
        }

        if (signature.isBlank() && methodSection == null)
            throw new IOException("Method '" + methodName + "' not found on page");
        
        // If we found a section but no signature, provide basic info
        if (signature.isBlank() && methodSection != null) {
            signature = "Method found but signature not extracted";
            if (description.isBlank()) {
                description = "Method documentation available on the page";
            }
        }

        return new DocInfo(topic, signature, description, params, returns, "✅ success");
    }

    record DocInfo(String topic, String signature, String description,
                   String parameters, String returns, String status) {}
}