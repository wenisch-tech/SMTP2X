package tech.wenisch.smtp2x.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.smtp2x.config.Smtp2xProperties;
import tech.wenisch.smtp2x.domain.InboundMessage;

class IntegrationActionHandlersTest {
  private HttpServer server;
  private ObjectMapper json;
  private SecretCipher secrets;
  private String baseUrl;
  private final ConcurrentHashMap<String, AtomicReference<String>> bodies = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicReference<String>> authorization = new ConcurrentHashMap<>();

  @BeforeEach
  void start() throws Exception {
    json = new ObjectMapper();
    var properties = new Smtp2xProperties("", null,
        new Smtp2xProperties.Crypto(
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), null, null);
    secrets = new SecretCipher(properties);
    server = HttpServer.create(new InetSocketAddress(0), 0);
    endpoint("/repos/acme/alerts/issues", 201,
        "{\"number\":17,\"html_url\":\"https://github.example/acme/alerts/issues/17\"}");
    endpoint("/api/v1/repos/acme/alerts/issues", 201,
        "{\"number\":23,\"html_url\":\"https://forgejo.example/acme/alerts/issues/23\"}");
    endpoint("/hooks/mattermost-token", 200, "ok");
    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort();
  }

  @AfterEach void stop() { server.stop(0); }

  @Test
  void githubCreatesTemplatedIssueWithLabelsAndAssignees() throws Exception {
    var config = json.createObjectNode();
    config.put("baseUrl", baseUrl);
    config.put("repository", "acme/alerts");
    config.put("accessToken", "github-token");
    config.put("titleTemplate", "Alert: {{subject}}");
    config.put("bodyTemplate", "{{body}}\nFrom {{from}} to {{recipients}}");
    config.putArray("labels").add("smtp").add("production");
    config.putArray("assignees").add("octocat");

    var result = new GitHubIssueActionHandler(json, secrets, new ActionHttpClientFactory())
        .deliver(message(), config);

    assertThat(result.remoteUrl()).isEqualTo("https://github.example/acme/alerts/issues/17");
    assertThat(authorization.get("/repos/acme/alerts/issues").get())
        .isEqualTo("Bearer github-token");
    assertThat(bodies.get("/repos/acme/alerts/issues").get())
        .contains("\"title\":\"Alert: Database unavailable\"")
        .contains("\"body\":\"Connection refused\\nFrom monitor@example.com to ops@example.com\"")
        .contains("\"labels\":[\"smtp\",\"production\"]")
        .contains("\"assignees\":[\"octocat\"]");
  }

  @Test
  void forgejoCreatesTemplatedIssueWithLabelIdsAndAssignees() throws Exception {
    var config = json.createObjectNode();
    config.put("baseUrl", baseUrl);
    config.put("repository", "acme/alerts");
    config.put("accessToken", "forgejo-token");
    config.put("titleTemplate", "{{subject}}");
    config.put("bodyTemplate", "{{body}}");
    config.putArray("labelIds").add(5).add(8);
    config.putArray("assignees").add("oncall");

    var result = new ForgejoIssueActionHandler(json, secrets, new ActionHttpClientFactory())
        .deliver(message(), config);

    assertThat(result.remoteUrl()).isEqualTo("https://forgejo.example/acme/alerts/issues/23");
    assertThat(authorization.get("/api/v1/repos/acme/alerts/issues").get())
        .isEqualTo("token forgejo-token");
    assertThat(bodies.get("/api/v1/repos/acme/alerts/issues").get())
        .contains("\"title\":\"Database unavailable\"")
        .contains("\"labels\":[5,8]")
        .contains("\"assignees\":[\"oncall\"]");
  }

  @Test
  void mattermostPostsMessageWithoutPersistingSecretWebhookUrl() throws Exception {
    var config = json.createObjectNode();
    config.put("webhookUrl", secrets.encrypt(baseUrl + "/hooks/mattermost-token"));
    config.put("textTemplate", "**{{subject}}**\n{{body}}\n{{from}}");
    config.put("channel", "ops-alerts");
    config.put("username", "SMTP2X");
    config.put("iconUrl", "https://example.com/smtp2x.png");

    var result = new MattermostMessageActionHandler(json, secrets, new ActionHttpClientFactory())
        .deliver(message(), config);

    assertThat(result.remoteUrl()).isEmpty();
    assertThat(result.diagnostics()).isEqualTo("Mattermost message posted");
    assertThat(bodies.get("/hooks/mattermost-token").get())
        .contains("\"text\":\"**Database unavailable**\\nConnection refused\\nmonitor@example.com\"")
        .contains("\"channel\":\"ops-alerts\"")
        .contains("\"username\":\"SMTP2X\"")
        .contains("\"icon_url\":\"https://example.com/smtp2x.png\"");
  }

  private MessageData message() {
    var message = new InboundMessage("monitor@example.com", "[]", "Database unavailable",
        "Connection refused", null, "unused");
    return new MessageData(message, List.of("ops@example.com"), List.of());
  }

  private void endpoint(String path, int status, String response) {
    bodies.put(path, new AtomicReference<>());
    authorization.put(path, new AtomicReference<>());
    server.createContext(path, exchange -> {
      bodies.get(path).set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      authorization.get(path).set(exchange.getRequestHeaders().getFirst("Authorization"));
      respond(exchange, status, response);
    });
  }

  private void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
