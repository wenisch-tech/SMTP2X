package tech.wenisch.smtp2x.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tech.wenisch.smtp2x.domain.ActionType;

@Component
public class GitHubIssueActionHandler implements ActionHandler {
  private static final String API_VERSION = "2026-03-10";
  private final ObjectMapper json;
  private final SecretCipher secrets;
  private final RestClient client = RestClient.builder().build();

  public GitHubIssueActionHandler(ObjectMapper json, SecretCipher secrets) {
    this.json = json;
    this.secrets = secrets;
  }

  @Override public ActionType type() { return ActionType.GITHUB_ISSUE; }

  @Override
  public DeliveryResult deliver(MessageData data, JsonNode config) throws DeliveryException {
    String api = required(config, "baseUrl").replaceAll("/$", "");
    String[] repository = repository(config);
    String token = secrets.decrypt(required(config, "accessToken"));
    ObjectNode issue = json.createObjectNode();
    issue.put("title", MessageTemplate.render(config.path("titleTemplate").asText("{{subject}}"), data));
    issue.put("body", MessageTemplate.render(config.path("bodyTemplate").asText("{{body}}"), data));
    copyStrings(config, issue, "labels");
    copyStrings(config, issue, "assignees");
    String url = api + "/repos/" + segment(repository[0]) + "/" + segment(repository[1]) + "/issues";
    try {
      JsonNode response = client.post().uri(url)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
          .header("X-GitHub-Api-Version", API_VERSION)
          .contentType(MediaType.APPLICATION_JSON)
          .body(json.writeValueAsString(issue))
          .exchange((request, result) -> {
            if (result.getStatusCode().is2xxSuccessful()) return json.readTree(result.getBody());
            int status = result.getStatusCode().value();
            boolean retry = status == 429 || status >= 500;
            throw new DeliveryException("GitHub returned " + result.getStatusCode(), retry);
          });
      return new DeliveryResult(response.path("html_url").asText(),
          "GitHub issue #" + response.path("number").asText() + " created", "");
    } catch (DeliveryException e) {
      throw e;
    } catch (Exception e) {
      throw new DeliveryException("GitHub issue creation failed", true, e);
    }
  }

  private String[] repository(JsonNode config) throws DeliveryException {
    String[] value = required(config, "repository").split("/", -1);
    if (value.length != 2 || value[0].isBlank() || value[1].isBlank())
      throw new DeliveryException("GitHub repository must use owner/repository", false);
    return value;
  }

  private void copyStrings(JsonNode config, ObjectNode target, String field) {
    List<String> values = new ArrayList<>();
    config.path(field).forEach(value -> {
      if (!value.asText().isBlank()) values.add(value.asText().trim());
    });
    if (!values.isEmpty()) {
      ArrayNode array = target.putArray(field);
      values.forEach(array::add);
    }
  }

  private String required(JsonNode config, String field) throws DeliveryException {
    String value = config.path(field).asText();
    if (value.isBlank()) throw new DeliveryException("GitHub action requires " + field, false);
    return value;
  }

  private String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
