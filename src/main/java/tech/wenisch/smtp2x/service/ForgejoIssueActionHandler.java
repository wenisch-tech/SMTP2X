package tech.wenisch.smtp2x.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tech.wenisch.smtp2x.domain.ActionType;
import tech.wenisch.smtp2x.domain.InboundAttachment;

@Component
public class ForgejoIssueActionHandler implements CleanupActionHandler {
  private final ObjectMapper json;
  private final SecretCipher secrets;
  private final ActionHttpClientFactory clients;

  public ForgejoIssueActionHandler(ObjectMapper json, SecretCipher secrets,
      ActionHttpClientFactory clients) {
    this.json = json;
    this.secrets = secrets;
    this.clients = clients;
  }

  @Override public ActionType type() { return ActionType.FORGEJO_ISSUE; }

  @Override
  public DeliveryResult deliver(MessageData data, JsonNode config) throws DeliveryException {
    String base = required(config, "baseUrl").replaceAll("/$", "");
    String[] repository = repository(config);
    String token = secrets.decrypt(required(config, "accessToken"));
    ObjectNode issue = json.createObjectNode();
    issue.put("title", MessageTemplate.render(config.path("titleTemplate").asText("{{subject}}"), data));
    String baseBody = MessageTemplate.render(config.path("bodyTemplate").asText("{{body}}"), data);
    boolean uploadEnabled = config.path("uploadAttachments").asBoolean(true);
    issue.put("body", uploadEnabled && !data.attachments().isEmpty() ? baseBody
        : AttachmentMarkdown.render(baseBody, data.attachments(), Map.of()));
    copyStrings(config, issue, "assignees");
    List<Long> labelIds = new ArrayList<>();
    config.path("labelIds").forEach(value -> {
      if (value.canConvertToLong()) labelIds.add(value.asLong());
    });
    if (!labelIds.isEmpty()) {
      ArrayNode labels = issue.putArray("labels");
      labelIds.forEach(labels::add);
    }
    String url = base + "/api/v1/repos/" + segment(repository[0]) + "/" + segment(repository[1]) + "/issues";
    try {
      RestClient client = clients.forConfiguration(config);
      JsonNode response = client.post().uri(url)
          .header(HttpHeaders.AUTHORIZATION, "token " + token)
          .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
          .contentType(MediaType.APPLICATION_JSON)
          .body(json.writeValueAsString(issue))
          .exchange((request, result) -> {
            if (result.getStatusCode().is2xxSuccessful()) return json.readTree(result.getBody());
            int status = result.getStatusCode().value();
            boolean retry = status == 429 || status >= 500;
            throw new DeliveryException("Forgejo returned " + result.getStatusCode(), retry);
          });
      String number = response.path("number").asText();
      List<String> warnings = new ArrayList<>();
      if (uploadEnabled && !data.attachments().isEmpty()) {
        Map<UUID, String> uploaded = uploadAttachments(client, url + "/" + number + "/assets",
            token, data.attachments(), warnings);
        String finalBody = AttachmentMarkdown.render(baseBody, data.attachments(), uploaded);
        updateBody(clients.forPatchConfiguration(config), url + "/" + number, token, finalBody,
            warnings);
      } else if (!uploadEnabled && !data.attachments().isEmpty()) {
        warnings.add("Forgejo attachment upload is disabled");
      }
      return new DeliveryResult(response.path("html_url").asText(),
          "Forgejo issue #" + number + " created", String.join("; ", warnings), number);
    } catch (DeliveryException e) {
      throw e;
    } catch (Exception e) {
      throw new DeliveryException("Forgejo issue creation failed", true, e);
    }
  }

  @Override
  public void cleanup(JsonNode config, String resourceReference) throws DeliveryException {
    String base = required(config, "baseUrl").replaceAll("/$", "");
    String[] repository = repository(config);
    String token = secrets.decrypt(required(config, "accessToken"));
    long issueNumber = positiveReference(resourceReference);
    String url = base + "/api/v1/repos/" + segment(repository[0]) + "/"
        + segment(repository[1]) + "/issues/" + issueNumber;
    try {
      clients.forConfiguration(config).delete().uri(url)
          .header(HttpHeaders.AUTHORIZATION, "token " + token)
          .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
          .exchange((request, result) -> {
            int status = result.getStatusCode().value();
            if (result.getStatusCode().is2xxSuccessful() || status == 404) return null;
            boolean retry = status == 429 || status >= 500;
            throw new DeliveryException("Forgejo cleanup returned " + result.getStatusCode(),
                retry);
          });
    } catch (DeliveryException e) {
      throw e;
    } catch (Exception e) {
      throw new DeliveryException("Forgejo cleanup failed", true, e);
    }
  }

  private String[] repository(JsonNode config) throws DeliveryException {
    String[] value = required(config, "repository").split("/", -1);
    if (value.length != 2 || value[0].isBlank() || value[1].isBlank())
      throw new DeliveryException("Forgejo repository must use owner/repository", false);
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

  private Map<UUID, String> uploadAttachments(RestClient client, String url, String token,
      List<InboundAttachment> attachments, List<String> warnings) {
    Map<UUID, String> uploaded = new LinkedHashMap<>();
    for (InboundAttachment attachment : attachments) {
      try {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("attachment", resource(attachment));
        String endpoint = url + "?name="
            + URLEncoder.encode(attachment.getFilename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        String responseBody = client.post().uri(endpoint)
            .header(HttpHeaders.AUTHORIZATION, "token " + token)
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(String.class);
        JsonNode response = responseBody == null ? null : json.readTree(responseBody);
        String downloadUrl = response == null ? "" : response.path("browser_download_url").asText();
        if (downloadUrl.isBlank()) throw new IllegalStateException("Missing attachment URL");
        uploaded.put(attachment.getId(), downloadUrl);
      } catch (Exception e) {
        warnings.add("Could not upload attachment " + attachment.getFilename());
      }
    }
    return uploaded;
  }

  private void updateBody(RestClient client, String url, String token, String body,
      List<String> warnings) {
    try {
      ObjectNode update = json.createObjectNode().put("body", body);
      client.patch().uri(url).header(HttpHeaders.AUTHORIZATION, "token " + token)
          .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
          .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(update))
          .retrieve().toBodilessEntity();
    } catch (Exception e) {
      warnings.add("Attachments were uploaded but the Forgejo issue body could not be updated");
    }
  }

  private FileSystemResource resource(InboundAttachment attachment) {
    return new FileSystemResource(Path.of(attachment.getContentPath())) {
      @Override public String getFilename() { return attachment.getFilename(); }
    };
  }

  private String required(JsonNode config, String field) throws DeliveryException {
    String value = config.path(field).asText();
    if (value.isBlank()) throw new DeliveryException("Forgejo action requires " + field, false);
    return value;
  }

  private long positiveReference(String value) throws DeliveryException {
    try {
      long result = Long.parseLong(value);
      if (result <= 0) throw new NumberFormatException();
      return result;
    } catch (NumberFormatException e) {
      throw new DeliveryException("Forgejo issue reference is invalid", false, e);
    }
  }

  private String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
