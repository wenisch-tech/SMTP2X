package tech.wenisch.smtp2x.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.smtp2x.domain.ActionConfiguration;
import tech.wenisch.smtp2x.domain.ActionType;
import tech.wenisch.smtp2x.repository.ActionConfigurationRepository;

@Service
public class ActionConfigurationService {
  private static final List<String> SECRET_FIELDS = List.of(
      "accessToken", "bearerToken", "basicPassword", "webhookUrl");
  private final ActionConfigurationRepository actions;
  private final ObjectMapper json;
  private final SecretCipher cipher;
  private final AuditService audit;

  public ActionConfigurationService(ActionConfigurationRepository actions, ObjectMapper json,
      SecretCipher cipher, AuditService audit) {
    this.actions = actions;
    this.json = json;
    this.cipher = cipher;
    this.audit = audit;
  }

  @Transactional
  public ActionConfiguration save(UUID id, String name, ActionType type, boolean enabled,
      JsonNode config, String actor) {
    if (name == null || name.isBlank() || name.trim().length() > 120)
      throw new IllegalArgumentException("Enter an action name of at most 120 characters");
    validate(type, config);
    ObjectNode secured = (ObjectNode) config.deepCopy();
    SECRET_FIELDS.forEach(field -> encrypt(secured, field));
    String value;
    try {
      value = json.writeValueAsString(secured);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid action configuration", e);
    }
    ActionConfiguration action = id == null
        ? new ActionConfiguration(name, type, value)
        : actions.findById(id).orElseThrow();
    if (id != null && action.getType() != type)
      throw new IllegalArgumentException("An action type cannot be changed");
    action.update(name.trim(), enabled, value);
    action = actions.save(action);
    audit.record(actor, id == null ? "ACTION_CREATED" : "ACTION_UPDATED", "action",
        action.getId().toString(), action.getName());
    return action;
  }

  public JsonNode publicConfiguration(ActionConfiguration action) {
    try {
      ObjectNode node = (ObjectNode) json.readTree(action.getConfigurationJson());
      SECRET_FIELDS.forEach(field -> redact(node, field));
      return node;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void encrypt(ObjectNode node, String field) {
    if (node.hasNonNull(field)) {
      String value = node.path(field).asText();
      if (!value.isBlank() && !value.startsWith("enc:")) node.put(field, cipher.encrypt(value));
    }
  }

  private void redact(ObjectNode node, String field) {
    if (node.has(field)) {
      node.put(field, "");
      node.put(field + "Configured", true);
    }
  }

  private void validate(ActionType type, JsonNode config) {
    if (type == null) throw new IllegalArgumentException("Select an action type");
    if (config == null || !config.isObject())
      throw new IllegalArgumentException("Action configuration must be an object");
    if (config.has("ignoreTlsErrors") && !config.path("ignoreTlsErrors").isBoolean())
      throw new IllegalArgumentException("Ignore TLS errors must be true or false");
    switch (type) {
      case GITLAB_ISSUE -> {
        require(config, "baseUrl", "GitLab");
        require(config, "project", "GitLab");
        require(config, "accessToken", "GitLab");
        httpUrl(config, "baseUrl", "GitLab");
        AutoDeleteDuration.parse(config.path("autoDeleteAfter").asText());
      }
      case GITHUB_ISSUE -> {
        require(config, "baseUrl", "GitHub");
        repository(config, "GitHub");
        require(config, "accessToken", "GitHub");
        httpUrl(config, "baseUrl", "GitHub");
      }
      case FORGEJO_ISSUE -> {
        require(config, "baseUrl", "Forgejo");
        repository(config, "Forgejo");
        require(config, "accessToken", "Forgejo");
        httpUrl(config, "baseUrl", "Forgejo");
        AutoDeleteDuration.parse(config.path("autoDeleteAfter").asText());
        config.path("labelIds").forEach(label -> {
          if (!label.canConvertToLong() || label.asLong() <= 0)
            throw new IllegalArgumentException("Forgejo label IDs must be positive numbers");
        });
      }
      case MATTERMOST_MESSAGE -> {
        require(config, "webhookUrl", "Mattermost");
        httpUrl(config, "webhookUrl", "Mattermost");
      }
      case WEBHOOK -> {
        require(config, "url", "Webhook");
        httpUrl(config, "url", "Webhook");
      }
    }
  }

  private void require(JsonNode config, String field, String integration) {
    if (config.path(field).asText().isBlank())
      throw new IllegalArgumentException(integration + " action requires " + field);
  }

  private void repository(JsonNode config, String integration) {
    String[] value = config.path("repository").asText().split("/", -1);
    if (value.length != 2 || value[0].isBlank() || value[1].isBlank())
      throw new IllegalArgumentException(integration + " repository must use owner/repository");
  }

  private void httpUrl(JsonNode config, String field, String integration) {
    try {
      URI uri = URI.create(config.path(field).asText());
      if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
          || "http".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new IllegalArgumentException(
          integration + " action requires a valid HTTP URL for " + field);
    }
  }
}
