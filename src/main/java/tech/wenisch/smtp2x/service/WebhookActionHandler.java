package tech.wenisch.smtp2x.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tech.wenisch.smtp2x.domain.ActionType;
import tech.wenisch.smtp2x.domain.InboundAttachment;

@Component
public class WebhookActionHandler implements ActionHandler {
  private final ObjectMapper json;
  private final SecretCipher secrets;
  private final ActionHttpClientFactory clients;

  public WebhookActionHandler(ObjectMapper json, SecretCipher secrets,
      ActionHttpClientFactory clients) {
    this.json = json;
    this.secrets = secrets;
    this.clients = clients;
  }

  @Override public ActionType type() { return ActionType.WEBHOOK; }

  @Override
  public DeliveryResult deliver(MessageData data, JsonNode config) throws DeliveryException {
    try {
      String url = config.path("url").asText();
      if (url.isBlank()) throw new DeliveryException("Webhook action requires url", false);
      ObjectNode body = defaultPayload(data);
      JsonNode custom = config.get("payload");
      if (custom != null && !custom.isNull()) body = (ObjectNode) custom.deepCopy();
      RestClient.RequestBodySpec request = clients.forConfiguration(config).post().uri(url)
          .contentType(MediaType.APPLICATION_JSON);
      config.path("headers").fields()
          .forEachRemaining(entry -> request.header(entry.getKey(), entry.getValue().asText()));
      String bearer = secrets.decrypt(config.path("bearerToken").asText());
      if (!bearer.isBlank()) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
      ResponseEntity<String> response = request.body(json.writeValueAsString(body)).retrieve()
          .toEntity(String.class);
      return new DeliveryResult(url, "Webhook returned " + response.getStatusCode(), "");
    } catch (DeliveryException e) {
      throw e;
    } catch (Exception e) {
      throw new DeliveryException("Webhook delivery failed", true, e);
    }
  }

  private ObjectNode defaultPayload(MessageData data) throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("version", "2");
    body.put("messageId", data.message().getId().toString());
    body.put("from", data.message().getEnvelopeFrom());
    body.put("subject", data.message().getSubject());
    body.put("text", MessageBody.markdown(data.message()));
    body.set("recipients", json.valueToTree(data.recipients()));
    ArrayNode attachments = body.putArray("attachments");
    for (InboundAttachment attachment : data.attachments()) {
      ObjectNode item = attachments.addObject();
      item.put("id", attachment.getId().toString());
      item.put("filename", attachment.getFilename());
      item.put("contentType", attachment.getContentType());
      item.put("contentId", attachment.getContentId());
      item.put("disposition", attachment.getDisposition());
      item.put("inline", attachment.isInline());
      item.put("size", attachment.getSizeBytes());
      item.put("contentBase64", Base64.getEncoder().encodeToString(
          Files.readAllBytes(Path.of(attachment.getContentPath()))));
    }
    return body;
  }
}
