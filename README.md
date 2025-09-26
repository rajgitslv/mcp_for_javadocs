# JDKDoc‑MCP — README

**Live Javadoc server for Oracle JDK 24**

This project provides an **MCP‑style** (Model Context Protocol) Java server and a simple client that dynamically fetch and parse Oracle JDK 24 API documentation ([https://docs.oracle.com/en/java/javase/24/docs/api/](https://docs.oracle.com/en/java/javase/24/docs/api/)) and return structured JSON for method documentation (signature, description, parameters, returns, examples, and source URL).

The implementation includes:

* `JdkDocServerEnhanced` (server) — module discovery, robust anchor resolution, HTML parsing with Jsoup, page caching.
* `JdkDocClient` (client) — simple HTTP client that queries the server and prints pretty JSON.
* `pom.xml` — Maven build, dependencies (Jsoup, Jackson).
* `Dockerfile` — container image for the server.
* Optional test fixtures (saved Javadoc HTML files) + JUnit test descriptions.

---

## Table of contents

1. Overview
2. Features
3. Prerequisites
4. Project layout
5. Build (Maven)
6. Run the server (local)
7. Run the client (local)
8. Query formats & examples
9. Module discovery & anchor rules (how it works)
10. Caching & configuration
11. Docker (build & run)
12. Testing (JUnit fixtures & how-to)
13. Troubleshooting
14. Security & usage notes
15. Extending the server
16. License

---

## 1. Overview

`jdkdoc-mcp` provides a runtime HTTP endpoint that accepts queries like:

```
GET /query?topic=java.util.stream.Stream.map
```

or with parameters to disambiguate overloaded methods:

```
GET /query?topic=java.util.stream.Stream.map(java.util.function.Function)
```

The server maps the class → JDK module using the module index from Oracle's API docs, fetches the class HTML page, locates the method block, and returns structured JSON.

## 2. Features

* Dynamic fetching of official Oracle JDK 24 API docs
* Module discovery (maps package → module path automatically)
* Anchor encoding logic to resolve overloaded methods accurately
* Robust fallbacks: anchor lookup → `<pre>` signature scan → method summary listing
* In‑memory caching for class pages (configurable TTL)
* JSON output using Jackson; HTML parsing via JSoup
* Virtual threads executor for high concurrency (Java 21+)
* Dockerfile for container deployment

## 3. Prerequisites

* Java 21+ JDK (Project Loom support recommended)
* Maven 3.6+ (for build)
* Internet access from the server host to `docs.oracle.com`
* (Optional) Docker if you want to run the server in a container

## 4. Project layout (recommended)

```
jdkdoc-mcp/
├─ pom.xml
├─ src/main/java/com/example/jdkdoc/JdkDocServerEnhanced.java
├─ src/main/java/com/example/jdkdoc/JdkDocClient.java
├─ src/test/resources/javadoc-fixtures/   # optional saved HTML files for tests
├─ Dockerfile
└─ README.md
```

## 5. Build (Maven)

From the project root:

```bash
mvn -DskipTests package
```

This produces `target/jdkdoc-mcp-1.0-SNAPSHOT.jar` (or similar depending on your `pom.xml`).

## 6. Run the server (local)

1. Start the server in a terminal:

```bash
java -cp target/jdkdoc-mcp-1.0-SNAPSHOT.jar com.example.jdkdoc.JdkDocServerEnhanced
```

2. You should see a log message such as:

```
Enhanced JDK doc server listening at http://localhost:8080/query
Module index loaded for <N> packages
```

3. By default the server listens on port **8080** (change in source if you need another port).

## 7. Run the client (local)

A simple client is provided. Run it like this:

```bash
java -cp target/jdkdoc-mcp-1.0-SNAPSHOT.jar com.example.jdkdoc.JdkDocClient "java.util.stream.Stream.map(java.util.function.Function)"
```

Or use `curl`:

```bash
curl -G --data-urlencode "topic=java.util.stream.Stream.map(java.util.function.Function)" "http://localhost:8080/query"
```

## 8. Query formats & examples

**Supported topic formats**

* `package.Class.method` — best for simple cases.
* `package.Class.method(paramType1,paramType2,...)` — provide fully qualified parameter type(s) to disambiguate overloads.

**Examples (curl)**

1. Simple method lookup:

```bash
curl -G --data-urlencode "topic=java.util.stream.Stream.map" http://localhost:8080/query
```

2. Overloaded or generic method disambiguation:

```bash
curl -G --data-urlencode "topic=java.util.stream.Stream.map(java.util.function.Function)" http://localhost:8080/query
```

**Sample JSON response**

```json
{
  "topic":"java.util.stream.Stream.map(java.util.function.Function)",
  "className":"java.util.stream.Stream",
  "method":"map",
  "signature":"<R> Stream<R> map(Function<? super T, ? extends R> mapper)",
  "description":"Returns a stream consisting of the results of applying the given function to the elements of this stream.",
  "parameters": { "mapper": "a non-interfering, stateless function to apply to each element" },
  "throwsInfo": null,
  "examples": [ "List<String> names = List.of(\"Alice\", \"Bob\");\n..." ],
  "sourceUrl":"https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/util/stream/Stream.html"
}
```

## 9. Module discovery & anchor rules (how it works)

**Module discovery**

* On startup, the server fetches the JDK 24 API index page and scans links that point to `module-summary.html`.
* For each module it fetches the module summary and collects package → module mappings. This lets the server construct the correct URL for a class page
  (e.g. `.../java.base/java/util/stream/Stream.html`).

**Anchor encoding (method name lookup)**

* Oracle Javadoc typically encodes method anchors like `map-java.util.function.Function` (method name plus dash-separated, canonical param type names). The server attempts to construct this anchor as: `methodName(-paramType1-paramType2...)` where each parameter type is normalized (generics removed, whitespace stripped).
* Lookup order: exact anchor lookup → anchors starting with `method(` → `<pre>` blocks scanning for signatures → method summary listing fallback.

This multi-step approach increases accuracy for overloaded and generic methods.

## 10. Caching & configuration

* Class pages fetched from the Oracle docs are cached in an in‑memory map (simple TTL). The default TTL is **1 hour** (set by `CACHE_TTL_SECONDS`).
* To change TTL or other constants, edit the server constants and rebuild.
* For production use consider switching to a proper external cache (Caffeine, Redis) and making configuration available through environment variables or configuration files.

## 11. Docker (build & run)

**Dockerfile (provided)** builds a minimal image with the packaged JAR.

Build image:

```bash
mvn -DskipTests package
docker build -t jdkdoc-server .
```

Run container:

```bash
docker run -p 8080:8080 --rm jdkdoc-server
```

Then query `http://localhost:8080/query?topic=java.util.stream.Stream.map` from your host.

## 12. Testing (JUnit fixtures & how-to)

**Test strategy**

* Save representative class HTML pages from the Oracle Javadoc site to `src/test/resources/javadoc-fixtures/` (e.g. `Stream.html`).
* Write JUnit tests that load the fixture as a JSoup `Document` and call the internal parsing helpers (e.g. `findMethodElement`, `findParameters`, `findDescriptionBlockText`) to assert correct extraction.

**Example test command**

```bash
mvn test
```

**Notes**

* Tests using live network calls are flaky — prefer fixture-based parsing unit tests. Only include integration tests that hit the live site if you accept network instability.
* I can provide a sample JUnit test class on request that validates extraction of `Stream.map` and another overloaded method.

## 13. Troubleshooting

**Common issues & fixes**

* *404 when fetching docs*: Confirm internet access and that Oracle docs are reachable from the server host. Also verify the `package → module` mapping; some uncommon packages may require module discovery updates.
* *Method not found*: Try specifying the fully qualified parameter types in the topic to disambiguate overloads, e.g. `Class.method(java.lang.String,int)`.
* *Parsing errors*: The Javadoc HTML changed markup; update the JSoup selectors in helper methods (`findDescriptionBlockText`, `findParameters`, etc.).
* *High load / rate limits*: Add more aggressive caching, or throttle requests to the remote docs. Consider a replication/crawl approach for production.

## 14. Security & usage notes

* **Input sanitization**: The server restricts remote fetches to the official JDK 24 base URL and only appends computed module/class paths to avoid SSRF. Do not remove this check if you accept arbitrary `topic` values.
* **Rate limiting**: If exposing this publicly, add rate limiting and request throttling to avoid overloading Oracle servers and your own resources.
* **Robots / Terms of Use**: Respect Oracle’s terms — this implementation is intended for lightweight, courteous usage (developer tooling, interview preparation). For heavy automated use, consider hosting/replicating a copy with permission.

## 15. Extending the server (ideas / next steps)

* Add a configuration file or environment variables for `PORT`, `CACHE_TTL_SECONDS`, and `JDK_BASE_URL`.
* Replace the in‑memory cache with Caffeine (local) or Redis (distributed).
* Add authentication & rate limiting for public deployment.
* Implement a small UI (React) that queries the server and shows formatted docs.
* Provide an internal index mapping method names for fast autocomplete / suggestions.

## 16. License

MIT License — feel free to reuse and modify. Include attribution if used in other projects.

---

If you’d like, I can now:

* Add the **JUnit test class** and saved HTML fixtures for `Stream.html` (so `mvn test` runs out-of-the-box).
* Add a **Docker Compose** file for multi-service deployment.
* Provide a **sample React UI** that queries `GET /query` and displays results nicely.

Which of those would you like next?
