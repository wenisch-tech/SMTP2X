package tech.wenisch.smtp2x.service;

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
import tech.wenisch.smtp2x.domain.ActionType;
import tech.wenisch.smtp2x.domain.CleanupStatus;
import tech.wenisch.smtp2x.domain.ExternalCleanupJob;
import tech.wenisch.smtp2x.repository.ExternalCleanupJobRepository;

@Service
public class ExternalCleanupService {
  private final ExternalCleanupJobRepository jobs;
  private final ObjectMapper json;
  private final Map<ActionType, CleanupActionHandler> handlers = new EnumMap<>(ActionType.class);
  private final AuditService audit;

  public ExternalCleanupService(ExternalCleanupJobRepository jobs, ObjectMapper json,
      List<CleanupActionHandler> handlers, AuditService audit) {
    this.jobs = jobs;
    this.json = json;
    handlers.forEach(handler -> this.handlers.put(handler.type(), handler));
    this.audit = audit;
  }

  public void schedule(UUID deliveryId, ActionType actionType, String configurationSnapshot,
      JsonNode actionConfiguration, String resourceReference) {
    AutoDeleteDuration.parse(actionConfiguration.path("autoDeleteAfter").asText())
        .ifPresent(delay -> {
          if (!handlers.containsKey(actionType) || resourceReference == null
              || resourceReference.isBlank())
            throw new IllegalStateException(
                "The action did not return a reference for automatic deletion");
          ExternalCleanupJob cleanup = jobs.save(new ExternalCleanupJob(deliveryId, actionType,
              configurationSnapshot, resourceReference, Instant.now().plus(delay)));
          audit.record("system", "CLEANUP_SCHEDULED", "cleanup", cleanup.getId().toString(),
              actionType + " cleanup due at " + cleanup.getDueAt());
        });
  }

  @Scheduled(fixedDelayString = "${smtp2x.cleanup.poll-ms:5000}")
  public void processDue() {
    Instant now = Instant.now();
    List<ExternalCleanupJob> interrupted = jobs.findStale(CleanupStatus.RUNNING,
        now.minus(Duration.ofMinutes(10)));
    interrupted.forEach(job -> job.recover(now));
    jobs.saveAll(interrupted);
    List<ExternalCleanupJob> ready = jobs.findDue(CleanupStatus.PENDING, now)
        .stream().limit(20).toList();
    ready.forEach(ExternalCleanupJob::claim);
    jobs.saveAll(ready);
    ready.forEach(job -> cleanup(job.getId()));
  }

  void cleanup(UUID id) {
    ExternalCleanupJob job = jobs.findById(id).orElseThrow();
    if (job.getStatus() != CleanupStatus.RUNNING) return;
    try {
      JsonNode snapshot = json.readTree(job.getConfigurationSnapshot());
      CleanupActionHandler handler = handlers.get(job.getActionType());
      if (handler == null)
        throw new DeliveryException("No cleanup handler configured", false);
      handler.cleanup(snapshot.path("configuration"), job.getResourceReference());
      job.succeed();
      jobs.save(job);
      audit.record("system", "CLEANUP_SUCCEEDED", "cleanup", job.getId().toString(),
          job.getActionType() + " resource deleted");
    } catch (DeliveryException e) {
      boolean retry = e.retryable() && job.getAttempts() < 4;
      job.fail(e.getMessage(), retry, Instant.now().plus(retryDelay(job.getAttempts())));
      jobs.save(job);
      audit.record("system", retry ? "CLEANUP_RETRY" : "CLEANUP_FAILED", "cleanup",
          job.getId().toString(), e.getMessage());
    } catch (Exception e) {
      job.fail(e.getMessage(), false, Instant.now());
      jobs.save(job);
      audit.record("system", "CLEANUP_FAILED", "cleanup", job.getId().toString(),
          String.valueOf(e.getMessage()));
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
}
