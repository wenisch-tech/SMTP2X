package tech.wenisch.smtp2x.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.smtp2x.config.Smtp2xProperties;
import tech.wenisch.smtp2x.domain.InboundMessage;

class GitLabActionHandlerTest {
  private HttpServer server;
  private final AtomicReference<String> issue = new AtomicReference<>();
  private final AtomicReference<String> deleted = new AtomicReference<>();
  private GitLabActionHandler handler;
  private ObjectMapper json;

  @BeforeEach void start() throws Exception {
    json = new ObjectMapper();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/api/v4/users", this::users);
    server.createContext("/api/v4/projects/group/project/issues", this::issue);
    server.start();
    var properties = new Smtp2xProperties("", null,
        new Smtp2xProperties.Crypto("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), null, null);
    handler = new GitLabActionHandler(json, new SecretCipher(properties),
        new ActionHttpClientFactory());
  }

  @AfterEach void stop() { server.stop(0); }

  @Test void recipientAndDefaultsAreCombinedAndDeduplicated() {
    var config = json.createObjectNode();
    config.put("baseUrl", "http://localhost:" + server.getAddress().getPort());
    config.put("project", "group/project");
    config.put("accessToken", "token");
    config.put("useRecipient", true);
    config.putArray("defaultAssigneeEmails").add("support@example.com").add("alice@example.com");
    config.put("titleTemplate", "{{subject}}");
    config.put("descriptionTemplate", "{{body}}");
    var message = new InboundMessage("sender@example.com", "[]", "Alert", "Details", null, "unused");

    var result = handler.deliver(new MessageData(message, List.of("alice@example.com"), List.of()), config);

    assertThat(result.remoteUrl()).isEqualTo("https://gitlab.example/issues/7");
    assertThat(result.cleanupReference()).isEqualTo("7");
    assertThat(issue.get()).contains("\"assignee_ids\":[101,202]");
    assertThat(issue.get()).contains("\"title\":\"Alert\"").contains("\"description\":\"Details\"");

    handler.cleanup(config, result.cleanupReference());
    assertThat(deleted.get()).isEqualTo("DELETE /api/v4/projects/group%2Fproject/issues/7");
  }

  private void users(HttpExchange request) throws java.io.IOException {
    String query = request.getRequestURI().getRawQuery();
    String body = query.contains("alice") ? "[{\"id\":101,\"public_email\":\"alice@example.com\"}]"
        : "[{\"id\":202,\"public_email\":\"support@example.com\"}]";
    respond(request, 200, body);
  }
  private void issue(HttpExchange request) throws java.io.IOException {
    if (request.getRequestMethod().equals("DELETE")) {
      deleted.set(request.getRequestMethod() + " " + request.getRequestURI().getRawPath());
      respond(request, 204, "");
      return;
    }
    issue.set(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    respond(request, 201, "{\"iid\":7,\"web_url\":\"https://gitlab.example/issues/7\",\"assignees\":[{\"id\":101},{\"id\":202}]}");
  }
  private void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
  }
}
