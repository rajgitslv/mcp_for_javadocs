package com.mcp.mcpforjavadoc.mcpclient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class JdkDocClient {
    public static void main(String[] args) throws Exception {
        String topic = "java.time.LocalDate.now"; // you can change dynamically
        String query = "http://localhost:8081/query?topic=" +
                java.net.URLEncoder.encode(topic, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(query)).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("🔎 Documentation for: " + topic);
        System.out.println(resp.body());
    }
}

