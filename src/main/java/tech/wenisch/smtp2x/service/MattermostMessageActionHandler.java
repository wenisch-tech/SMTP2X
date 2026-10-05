package tech.wenisch.smtp2x.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tech.wenisch.smtp2x.domain.ActionType;

@Component
public class MattermostMessageActionHandler implements ActionHandler {
  private final ObjectMapper json;
  private final SecretCipher secrets;
  private final ActionHttpClientFactory clients;

  public MattermostMessageActionHandler(ObjectMapper json, SecretCipher secrets,
      ActionHttpClientFactory clients) {
    this.json = json;
    this.secrets = secrets;
    this.clients = clients;
  }

  @Override public ActionType type() { return ActionType.MATTERMOST_MESSAGE; }

  @Override
  public DeliveryResult deliver(MessageData data, JsonNode config) throws DeliveryException {
    String url = secrets.decrypt(required(config, "webhookUrl"));
    ObjectNode message = json.createObjectNode();
    String text = MessageTemplate.render(
        config.path("textTemplate").asText("### {{subject}}\n\n{{body}}\n\n_From: {{from}}_"), data);
    message.put("text", AttachmentMarkdown.render(text, data.attachments(), java.util.Map.of()));
    copy(config, message, "channel");
    copy(config, message, "username");
    copy(config, message, "icon_url", "iconUrl");
    try {
      clients.forConfiguration(config).post().uri(url).contentType(MediaType.APPLICATION_JSON)
          .body(json.writeValueAsString(message))
          .exchange((request, result) -> {
            if (result.getStatusCode().is2xxSuccessful()) return null;
            int status = result.getStatusCode().value();
            boolean retry = status == 429 || status >= 500;
            throw new DeliveryException("Mattermost returned " + result.getStatusCode(), retry);
          });
      // Incoming webhook URLs contain their credential, so never persist one as a remote link.
      String warnings = data.attachments().isEmpty() ? ""
          : "Mattermost incoming webhooks cannot upload attachments; attachment notes were added";
      return new DeliveryResult("", "Mattermost message posted", warnings);
    } catch (DeliveryException e) {
      throw e;
    } catch (Exception e) {
      throw new DeliveryException("Mattermost message delivery failed", true, e);
    }
  }

  private void copy(JsonNode config, ObjectNode target, String field) {
    copy(config, target, field, field);
  }

  private void copy(JsonNode config, ObjectNode target, String targetField, String sourceField) {
    String value = config.path(sourceField).asText();
    if (!value.isBlank()) target.put(targetField, value);
  }

  private String required(JsonNode config, String field) throws DeliveryException {
    String value = config.path(field).asText();
    if (value.isBlank()) throw new DeliveryException("Mattermost action requires " + field, false);
    return value;
  }
}
