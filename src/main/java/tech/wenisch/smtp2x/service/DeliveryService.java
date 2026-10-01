package tech.wenisch.smtp2x.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.smtp2x.domain.ActionType;
import tech.wenisch.smtp2x.domain.DeliveryJob;
import tech.wenisch.smtp2x.domain.DeliveryStatus;
import tech.wenisch.smtp2x.domain.InboundMessage;
import tech.wenisch.smtp2x.repository.DeliveryJobRepository;
import tech.wenisch.smtp2x.repository.InboundAttachmentRepository;
import tech.wenisch.smtp2x.repository.InboundMessageRepository;

@Service
public class DeliveryService {
  private final DeliveryJobRepository jobs;
  private final InboundMessageRepository messages;
  private final InboundAttachmentRepository attachments;
  private final ObjectMapper json;
  private final Map<ActionType, ActionHandler> handlers;
  private final AuditService audit;
  private final ExternalCleanupService cleanups;
  private final ApplicationMetrics metrics;

  public DeliveryService(DeliveryJobRepository jobs, InboundMessageRepository messages,
      InboundAttachmentRepository attachments, ObjectMapper json, List<ActionHandler> handlers,
      AuditService audit, ExternalCleanupService cleanups, ApplicationMetrics metrics) {
    this.jobs = jobs;
    this.messages = messages;
    this.attachments = attachments;
    this.json = json;
    this.handlers = new EnumMap<>(ActionType.class);
    handlers.forEach(handler -> this.handlers.put(handler.type(), handler));
    this.audit = audit;
    this.cleanups = cleanups;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelayString = "${smtp2x.delivery.poll-ms:5000}")
  public void processDue() {
    for (DeliveryJob job : claimDue()) deliver(job);
  }

  @Transactional
  List<DeliveryJob> claimDue() {
    List<DeliveryJob> ready = jobs.findDue(DeliveryStatus.PENDING, Instant.now()).stream()
        .limit(20).toList();
    ready.forEach(DeliveryJob::claim);
    return jobs.saveAll(ready);
  }

  @Transactional
  void deliver(DeliveryJob detached) {
    DeliveryJob job = jobs.findById(detached.getId()).orElseThrow();
    if (job.getStatus() != DeliveryStatus.RUNNING) return;
    Instant started = Instant.now();
    ActionType actionType = null;
    ApplicationMetrics.Outcome outcome = ApplicationMetrics.Outcome.FAILED;
    try {
      InboundMessage message = messages.findById(job.getMessageId()).orElseThrow();
      List<String> recipients = json.readValue(message.getRecipientsJson(), new TypeReference<>() {});
      MessageData data = new MessageData(message, recipients,
          attachments.findByMessageId(message.getId()));
      JsonNode config = json.readTree(job.getConfigurationSnapshot());
      actionType = ActionType.valueOf(config.path("type").asText(""));
      ActionHandler handler = handlers.get(actionType);
      if (handler == null) throw new DeliveryException("No action handler configured", false);
      JsonNode actionConfig = config.path("configuration");
      DeliveryResult result = handler.deliver(data, actionConfig);
      job.success(result.remoteUrl(), result.diagnostics(), result.warnings());
      cleanups.schedule(job.getId(), actionType, job.getConfigurationSnapshot(), actionConfig,
          result.cleanupReference());
      jobs.save(job);
      audit.record("system", "DELIVERY_SUCCEEDED", "delivery", job.getId().toString(),
          result.diagnostics());
      outcome = ApplicationMetrics.Outcome.SUCCEEDED;
    } catch (DeliveryException e) {
      boolean retry = e.retryable() && job.getAttempts() < 4;
      job.fail(e.getMessage(), retry, Instant.now().plus(retryDelay(job.getAttempts())));
      jobs.save(job);
      audit.record("system", retry ? "DELIVERY_RETRY" : "DELIVERY_FAILED", "delivery",
          job.getId().toString(), e.getMessage());
      outcome = retry ? ApplicationMetrics.Outcome.RETRY : ApplicationMetrics.Outcome.FAILED;
    } catch (Exception e) {
      job.fail(e.getMessage(), false, Instant.now());
      jobs.save(job);
      audit.record("system", "DELIVERY_FAILED", "delivery", job.getId().toString(),
          String.valueOf(e.getMessage()));
    } finally {
      if (actionType != null) {
        metrics.deliveryAttempt(actionType, outcome, Duration.between(started, Instant.now()));
      }
    }
  }

  private Duration retryDelay(int attempts) {
    return switch (attempts) {
      case 0 -> Duration.ofMinutes(1);
      case 1 -> Duration.ofMinutes(5);
      case 2 -> Duration.ofMinutes(15);
      default -> Duration.ofMinutes(60);
    };
  }

  public void retry(UUID id) {
    DeliveryJob job = jobs.findById(id).orElseThrow();
    job.retryNow();
    jobs.save(job);
  }

  public void cancel(UUID id) {
    DeliveryJob job = jobs.findById(id).orElseThrow();
    job.cancel();
    jobs.save(job);
  }
}
