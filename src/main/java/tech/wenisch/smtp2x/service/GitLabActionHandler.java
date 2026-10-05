package tech.wenisch.smtp2x.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tech.wenisch.smtp2x.domain.ActionType;
import tech.wenisch.smtp2x.domain.AppUser;
import tech.wenisch.smtp2x.domain.InboundAttachment;

@Component
public class GitLabActionHandler implements CleanupActionHandler {
  private final ObjectMapper json;
  private final SecretCipher secrets;
  private final ActionHttpClientFactory clients;

  public GitLabActionHandler(ObjectMapper json, SecretCipher secrets,
      ActionHttpClientFactory clients) {
    this.json = json;
    this.secrets = secrets;
    this.clients = clients;
  }

  @Override public ActionType type() { return ActionType.GITLAB_ISSUE; }

  public AssigneePreview previewAssignees(JsonNode config, List<String> recipients)
      throws DeliveryException {
    try {
      RestClient client = clients.forConfiguration(config);
      String base = required(config, "baseUrl").replaceAll("/$", "");
      String api = projectApi(base, required(config, "project"));
      String token = secrets.decrypt(required(config, "accessToken"));
      List<String> requested = new ArrayList<>();
      if (config.path("useRecipient").asBoolean(false)) requested.addAll(recipients);
      config.path("defaultAssigneeEmails").forEach(node -> requested.add(node.asText()));
      Map<String, Long> mappings = mappings(config);
      List<AssigneeResolution> values = new ArrayList<>();
      for (String identifier : requested.stream().filter(value -> value != null && !value.isBlank())
          .map(AppUser::normalize).distinct().toList()) {
        Long id = mappings.get(identifier);
        if (id != null) {
          values.add(new AssigneeResolution(identifier, id, "mapping", "resolved"));
        }
        else {
          List<String> warnings = new ArrayList<>();
          ResolvedAssignee assignee = lookupAssignee(client, base, api, token, identifier, warnings);
          values.add(new AssigneeResolution(identifier,
              assignee == null ? null : assignee.id(),
              assignee == null ? "lookup" : assignee.source(),
              assignee == null ? String.join("; ", warnings) : "resolved"));
        }
      }
      return new AssigneePreview(values);
    } catch (DeliveryException e) {
      throw e;
    } catch (Exception e) {
      throw new DeliveryException("Could not validate GitLab assignees", true, e);
    }
  }

  public record AssigneeResolution(String email, Long gitlabUserId, String source, String status) {}
  public record AssigneePreview(List<AssigneeResolution> resolutions) {}

  @Override
  public DeliveryResult deliver(MessageData data, JsonNode config) throws DeliveryException {
    try {
      RestClient client = clients.forConfiguration(config);
      String base = required(config, "baseUrl").replaceAll("/$", "");
      String project = required(config, "project");
      String token = secrets.decrypt(required(config, "accessToken"));
      String api = projectApi(base, project);
      List<String> requestedRaw = new ArrayList<>();
      if (config.path("useRecipient").asBoolean(false)) requestedRaw.addAll(data.recipients());
      config.path("defaultAssigneeEmails").forEach(node -> requestedRaw.add(node.asText()));
      List<String> requested = requestedRaw.stream()
          .filter(value -> value != null && !value.isBlank()).map(AppUser::normalize)
          .distinct().toList();
      List<Long> resolved = new ArrayList<>();
      List<String> warnings = new ArrayList<>();
      Map<String, Long> mappings = mappings(config);
      for (String identifier : requested) {
        Long id = mappings.get(identifier);
        if (id == null) {
          ResolvedAssignee assignee = lookupAssignee(
              client, base, api, token, identifier, warnings);
          id = assignee == null ? null : assignee.id();
        }
        if (id != null && !resolved.contains(id)) resolved.add(id);
      }

      Map<UUID, String> uploaded = config.path("uploadAttachments").asBoolean(true)
          ? uploadAttachments(client, api, token, data.attachments(), warnings) : Map.of();
      if (!config.path("uploadAttachments").asBoolean(true) && !data.attachments().isEmpty()) {
        warnings.add("GitLab attachment upload is disabled");
      }
      String title = MessageTemplate.render(
          config.path("titleTemplate").asText("{{subject}}"), data);
      String description = AttachmentMarkdown.render(MessageTemplate.render(
          config.path("descriptionTemplate").asText("{{body}}"), data), data.attachments(),
          uploaded);
      ObjectNode issue = json.createObjectNode();
      issue.put("title", title);
      issue.put("description", description);
      if (config.path("confidential").asBoolean(false)) issue.put("confidential", true);
      if (!resolved.isEmpty()) {
        ArrayNode ids = issue.putArray("assignee_ids");
        resolved.forEach(ids::add);
      }
      if (config.has("labels") && !config.get("labels").isNull()) {
        issue.set("labels", config.get("labels"));
      }
      JsonNode response = postJson(client, api + "/issues", token, issue);
      if (response == null && !resolved.isEmpty()) {
        warnings.add("GitLab rejected one or more assignees; issue was created without assignment.");
        issue.remove("assignee_ids");
        response = postJson(client, api + "/issues", token, issue);
      }
      if (response == null) throw new DeliveryException("GitLab rejected issue creation", false);
      List<String> returned = new ArrayList<>();
      response.path("assignees").forEach(node -> returned.add(
          node.path("username").asText(node.path("id").asText())));
      String iid = response.path("iid").asText();
      return new DeliveryResult(response.path("web_url").asText(),
          "GitLab issue !" + iid + " created; requested=" + requested + ", resolved=" + resolved
              + ", assigned=" + returned,
          String.join("; ", warnings), iid);
    } catch (DeliveryException e) {
      throw e;
    } catch (Exception e) {
      throw new DeliveryException("GitLab delivery failed: " + safe(e.getMessage()), true, e);
    }
  }

  @Override
  public void cleanup(JsonNode config, String resourceReference) throws DeliveryException {
    try {
      long iid = positiveReference(resourceReference, "GitLab issue");
      RestClient client = clients.forConfiguration(config);
      String base = required(config, "baseUrl").replaceAll("/$", "");
      String project = required(config, "project");
      String token = secrets.decrypt(required(config, "accessToken"));
      String url = base + "/api/v4/projects/"
          + URLEncoder.encode(project, StandardCharsets.UTF_8).replace("+", "%20")
          + "/issues/" + iid;
      client.delete().uri(URI.create(url)).header("PRIVATE-TOKEN", token)
          .exchange((request, response) -> {
            int status = response.getStatusCode().value();
            if (response.getStatusCode().is2xxSuccessful() || status == 404) return null;
            if (status == 429 || status >= 500)
              throw new DeliveryException("GitLab cleanup returned " + response.getStatusCode(), true);
            throw new DeliveryException("GitLab cleanup returned " + response.getStatusCode(), false);
          });
    } catch (DeliveryException e) {
      throw e;
    } catch (Exception e) {
      throw new DeliveryException("GitLab cleanup failed", true, e);
    }
  }

  private Map<String, Long> mappings(JsonNode config) {
    Map<String, Long> result = new LinkedHashMap<>();
    config.path("assigneeEmailMappings").fields().forEachRemaining(entry -> {
      if (entry.getValue().canConvertToLong())
        result.put(AppUser.normalize(entry.getKey()), entry.getValue().asLong());
    });
    return result;
  }

  private ResolvedAssignee lookupAssignee(RestClient client, String base, String api,
      String token, String identifier, List<String> warnings) throws DeliveryException {
    ResolvedAssignee member = lookupProjectMember(client, api, token, identifier);
    if (member != null) return member;

    ResolvedAssignee user = lookupRegularUser(client, base, token, identifier);
    if (user != null) return user;

    warnings.add("No assignable GitLab project member resolved for " + identifier);
    return null;
  }

  private ResolvedAssignee lookupProjectMember(RestClient client, String api, String token,
      String identifier) throws DeliveryException {
    String lookup = lookupValue(identifier);
    JsonNode members = getUsers(client, api + "/members/all?query=" + encode(lookup)
        + "&per_page=100", token, "project member");
    if (members == null || !members.isArray()) return null;

    List<JsonNode> active = new ArrayList<>();
    for (JsonNode member : members) {
      if (validUser(member)) active.add(member);
    }
    if (isUsername(identifier)) {
      for (JsonNode member : active) {
        if (lookup.equalsIgnoreCase(member.path("username").asText("")))
          return new ResolvedAssignee(member.path("id").asLong(), "project-member");
      }
      return null;
    }
    for (JsonNode member : active) {
      if (emailMatches(member, lookup))
        return new ResolvedAssignee(member.path("id").asLong(), "project-member");
    }
    // GitLab can filter project members by a private email without returning that email.
    // Only accept the result when the server narrowed it to one unambiguous member.
    return active.size() == 1
        ? new ResolvedAssignee(active.get(0).path("id").asLong(), "project-member") : null;
  }

  private ResolvedAssignee lookupRegularUser(RestClient client, String base, String token,
      String identifier) throws DeliveryException {
    String lookup = lookupValue(identifier);
    String parameter = isUsername(identifier) ? "username=" : "search=";
    JsonNode users = getUsers(client,
        base + "/api/v4/users?" + parameter + encode(lookup) + "&per_page=100",
        token, "user");
    if (users == null || !users.isArray()) return null;
    for (JsonNode user : users) {
      if (!validUser(user)) continue;
      if (isUsername(identifier)
          && lookup.equalsIgnoreCase(user.path("username").asText("")))
        return new ResolvedAssignee(user.path("id").asLong(), "username");
      if (!isUsername(identifier) && emailMatches(user, lookup))
        return new ResolvedAssignee(user.path("id").asLong(), "public-email");
    }
    return null;
  }

  private JsonNode getUsers(RestClient client, String url, String token, String lookupType)
      throws DeliveryException {
    try {
      return client.get().uri(URI.create(url))
          .header("PRIVATE-TOKEN", token).exchange((request, response) -> {
            if (response.getStatusCode().is2xxSuccessful())
              return json.readTree(response.getBody());
            if (response.getStatusCode().value() == 429
                || response.getStatusCode().is5xxServerError())
              throw new DeliveryException(
                  "GitLab " + lookupType + " lookup returned " + response.getStatusCode(), true);
            return null;
          });
    } catch (DeliveryException e) {
      throw e;
    } catch (Exception e) {
      throw new DeliveryException("GitLab " + lookupType + " lookup failed", true, e);
    }
  }

  private boolean validUser(JsonNode user) {
    return user.path("id").canConvertToLong()
        && user.path("id").asLong() > 0
        && (user.path("state").isMissingNode()
            || "active".equalsIgnoreCase(user.path("state").asText()));
  }

  private boolean emailMatches(JsonNode user, String email) {
    return email.equalsIgnoreCase(user.path("email").asText(""))
        || email.equalsIgnoreCase(user.path("public_email").asText(""));
  }

  private boolean isUsername(String identifier) {
    return identifier.startsWith("@") || !identifier.contains("@");
  }

  private String lookupValue(String identifier) {
    return identifier.startsWith("@") ? identifier.substring(1) : identifier;
  }

  private String projectApi(String base, String project) {
    return base + "/api/v4/projects/" + encode(project);
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private record ResolvedAssignee(long id, String source) {}

  private JsonNode postJson(RestClient client, String url, String token, ObjectNode body)
      throws DeliveryException {
    try {
      return client.post().uri(URI.create(url)).header("PRIVATE-TOKEN", token)
          .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body))
          .exchange((request, response) -> {
            if (response.getStatusCode().is2xxSuccessful())
              return json.readTree(response.getBody());
            int status = response.getStatusCode().value();
            if (status == 400 || status == 403 || status == 404 || status == 422) return null;
            if (status == 429 || response.getStatusCode().is5xxServerError())
              throw new DeliveryException("GitLab returned " + response.getStatusCode(), true);
            throw new DeliveryException("GitLab returned " + response.getStatusCode(), false);
          });
    } catch (DeliveryException e) {
      throw e;
    } catch (Exception e) {
      throw new DeliveryException("GitLab request failed", true, e);
    }
  }

  private Map<UUID, String> uploadAttachments(RestClient client, String api, String token,
      List<InboundAttachment> attachments, List<String> warnings) {
    Map<UUID, String> uploaded = new LinkedHashMap<>();
    for (InboundAttachment attachment : attachments) {
      try {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", resource(attachment));
        String response = client.post().uri(URI.create(api + "/uploads"))
            .header("PRIVATE-TOKEN", token).contentType(MediaType.MULTIPART_FORM_DATA)
            .body(form).retrieve().body(String.class);
        JsonNode result = response == null ? null : json.readTree(response);
        String url = result == null ? "" : result.path("url").asText();
        if (url.isBlank()) throw new IllegalStateException("GitLab did not return an upload URL");
        uploaded.put(attachment.getId(), url);
      } catch (Exception e) {
        warnings.add("Could not upload attachment " + attachment.getFilename());
      }
    }
    return uploaded;
  }

  private FileSystemResource resource(InboundAttachment attachment) {
    return new FileSystemResource(Path.of(attachment.getContentPath())) {
      @Override public String getFilename() { return attachment.getFilename(); }
    };
  }

  private long positiveReference(String value, String label) throws DeliveryException {
    try {
      long result = Long.parseLong(value);
      if (result <= 0) throw new NumberFormatException();
      return result;
    } catch (NumberFormatException e) {
      throw new DeliveryException(label + " reference is invalid", false, e);
    }
  }

  private String required(JsonNode config, String field) throws DeliveryException {
    String value = config.path(field).asText();
    if (value.isBlank()) throw new DeliveryException("GitLab action requires " + field, false);
    return value;
  }

  private String safe(String value) {
    return value == null ? "unknown error" : value.substring(0, Math.min(value.length(), 800));
  }
}
