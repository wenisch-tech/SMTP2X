package tech.wenisch.smtp2x.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.smtp2x.config.Smtp2xProperties;
import tech.wenisch.smtp2x.domain.ActionConfiguration;
import tech.wenisch.smtp2x.domain.DeliveryJob;
import tech.wenisch.smtp2x.domain.InboundAttachment;
import tech.wenisch.smtp2x.domain.InboundMessage;
import tech.wenisch.smtp2x.domain.RoutingRule;
import tech.wenisch.smtp2x.repository.ActionConfigurationRepository;
import tech.wenisch.smtp2x.repository.DeliveryJobRepository;
import tech.wenisch.smtp2x.repository.InboundAttachmentRepository;
import tech.wenisch.smtp2x.repository.InboundMessageRepository;
import tech.wenisch.smtp2x.repository.RoutingRuleRepository;

@Service
public class MessageIngestionService {
  private final InboundMessageRepository messages;
  private final InboundAttachmentRepository attachments;
  private final RoutingRuleRepository rules;
  private final ActionConfigurationRepository actions;
  private final DeliveryJobRepository deliveries;
  private final ObjectMapper json;
  private final Smtp2xProperties properties;
  private final RuleMatcher matcher;
  private final AuditService audit;
  private final ApplicationMetrics metrics;

  public MessageIngestionService(InboundMessageRepository messages,
      InboundAttachmentRepository attachments, RoutingRuleRepository rules,
      ActionConfigurationRepository actions, DeliveryJobRepository deliveries, ObjectMapper json,
      Smtp2xProperties properties, RuleMatcher matcher, AuditService audit,
      ApplicationMetrics metrics) {
    this.messages = messages;
    this.attachments = attachments;
    this.rules = rules;
    this.actions = actions;
    this.deliveries = deliveries;
    this.json = json;
    this.properties = properties;
    this.matcher = matcher;
    this.audit = audit;
    this.metrics = metrics;
  }

  @Transactional
  public UUID accept(String envelopeFrom, List<String> recipients, byte[] raw) throws Exception {
    if (recipients == null || recipients.isEmpty())
      throw new IllegalArgumentException("An SMTP recipient is required");
    if (raw.length > properties.smtp().maxMessageBytes())
      throw new IllegalArgumentException("Message exceeds configured size");

    Parsed parsed = parse(raw);
    List<RoutingRule> matched = rules.findAll().stream()
        .filter(rule -> matcher.matches(rule, envelopeFrom, recipients, parsed.subject())).toList();
    Set<UUID> actionIds = new LinkedHashSet<>();
    matched.forEach(rule -> actionIds.addAll(rule.getActionIds()));
    List<ActionConfiguration> selected = actions.findAllById(actionIds).stream()
        .filter(ActionConfiguration::isEnabled).toList();
    if (selected.isEmpty())
      throw new IllegalArgumentException("No enabled routing action matches this message");

    String body = MessageBody.fromParts(parsed.text(), parsed.html());
    InboundMessage message = new InboundMessage(envelopeFrom, json.writeValueAsString(recipients),
        parsed.subject(), body, parsed.html(), "");
    Path root = Path.of(properties.dataDirectory(), "messages", message.getId().toString());
    Files.createDirectories(root);
    Path source = root.resolve("message.eml");
    Files.write(source, raw, StandardOpenOption.CREATE_NEW);
    message.contentPath(source.toString());
    message = messages.save(message);

    int index = 0;
    for (AttachmentData data : parsed.attachments()) {
      String storedName = "%03d-%s".formatted(++index, safeName(data.filename()));
      Path path = root.resolve("attachments").resolve(storedName);
      Files.createDirectories(path.getParent());
      Files.write(path, data.bytes(), StandardOpenOption.CREATE_NEW);
      attachments.save(new InboundAttachment(message.getId(), data.filename(), data.contentType(),
          data.contentId(), data.disposition(), path.toString(), data.bytes().length));
    }

    for (ActionConfiguration action : selected) {
      var snapshot = json.createObjectNode();
      snapshot.put("type", action.getType().name());
      snapshot.set("configuration", json.readTree(action.getConfigurationJson()));
      deliveries.save(new DeliveryJob(message.getId(), action.getId(),
          json.writeValueAsString(snapshot)));
    }
    audit.record("smtp", "MESSAGE_ACCEPTED", "message", message.getId().toString(),
        "rules=" + matched.stream().map(RoutingRule::getName).toList()
            + ", actions=" + selected.stream().map(ActionConfiguration::getName).toList());
    metrics.mailAccepted(raw.length, parsed.attachments().size(),
        selected.stream().map(ActionConfiguration::getType).toList());
    return message.getId();
  }

  private Parsed parse(byte[] raw) throws Exception {
    MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
        new ByteArrayInputStream(raw));
    Parts parts = new Parts();
    collect(mime, parts);
    return new Parsed(Optional.ofNullable(mime.getSubject()).orElse("(no subject)"), parts.text,
        parts.html, parts.attachments);
  }

  private void collect(Part part, Parts result) throws Exception {
    if (part.isMimeType("multipart/*")) {
      Multipart multipart = (Multipart) part.getContent();
      for (int i = 0; i < multipart.getCount(); i++) collect(multipart.getBodyPart(i), result);
      return;
    }
    String disposition = part.getDisposition();
    String filename = part.getFileName();
    String[] contentIds = part.getHeader("Content-ID");
    String contentId = contentIds == null || contentIds.length == 0 ? null : contentIds[0];
    boolean bodyText = part.isMimeType("text/plain") || part.isMimeType("text/html");
    boolean file = filename != null || Part.ATTACHMENT.equalsIgnoreCase(disposition)
        || !bodyText && (Part.INLINE.equalsIgnoreCase(disposition) || contentId != null
            || part.isMimeType("image/*"));
    if (file) {
      String resolvedName = filename == null || filename.isBlank()
          ? generatedName(part.getContentType()) : filename;
      String resolvedDisposition = Part.INLINE.equalsIgnoreCase(disposition) || contentId != null
          ? "inline" : "attachment";
      try (InputStream input = part.getInputStream()) {
        result.attachments.add(new AttachmentData(limit(resolvedName, 500),
            limit(part.getContentType(), 255), AttachmentMarkdown.normalizeContentId(contentId),
            resolvedDisposition, input.readAllBytes()));
      }
      return;
    }
    if (part.isMimeType("text/plain") && result.text == null) {
      result.text = String.valueOf(part.getContent());
    } else if (part.isMimeType("text/html") && result.html == null) {
      result.html = String.valueOf(part.getContent());
    }
  }

  private String generatedName(String contentType) {
    String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    String extension = type.startsWith("image/png") ? ".png"
        : type.startsWith("image/jpeg") ? ".jpg"
        : type.startsWith("image/gif") ? ".gif"
        : type.startsWith("image/webp") ? ".webp"
        : type.startsWith("image/svg+xml") ? ".svg" : "";
    return "inline-image" + extension;
  }

  private String safeName(String value) {
    String safe = value.replaceAll("[^a-zA-Z0-9._-]", "_");
    if (safe.isBlank() || safe.equals(".") || safe.equals("..")) safe = "attachment";
    return limit(safe, 240);
  }

  private String limit(String value, int max) {
    if (value == null || value.length() <= max) return value;
    return value.substring(0, max);
  }

  private record AttachmentData(String filename, String contentType, String contentId,
                                String disposition, byte[] bytes) {}
  private record Parsed(String subject, String text, String html,
                        List<AttachmentData> attachments) {}
  private static class Parts {
    String text;
    String html;
    List<AttachmentData> attachments = new ArrayList<>();
  }
}
