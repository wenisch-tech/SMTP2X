package tech.wenisch.smtp2x.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.wenisch.smtp2x.domain.ActionType;
import tech.wenisch.smtp2x.domain.CleanupStatus;
import tech.wenisch.smtp2x.domain.DeliveryStatus;
import tech.wenisch.smtp2x.repository.DeliveryJobRepository;
import tech.wenisch.smtp2x.repository.ExternalCleanupJobRepository;

@Component
public class ApplicationMetrics {
  public enum RejectionReason {
    NOT_ALLOWED,
    TOO_LARGE,
    INVALID,
    PROCESSING_ERROR
  }

  public enum Outcome {
    SUCCEEDED,
    RETRY,
    FAILED
  }

  private final MeterRegistry registry;
  private final DeliveryJobRepository deliveries;
  private final ExternalCleanupJobRepository cleanups;
  private final Counter receivedMail;
  private final Counter attachments;
  private final DistributionSummary mailSize;
  private final MultiGauge deliveryJobs;
  private final MultiGauge cleanupJobs;

  public ApplicationMetrics(MeterRegistry registry, DeliveryJobRepository deliveries,
      ExternalCleanupJobRepository cleanups) {
    this.registry = registry;
    this.deliveries = deliveries;
    this.cleanups = cleanups;
    receivedMail = Counter.builder("smtp2x.mail.received")
        .description("SMTP messages durably accepted by SMTP2X")
        .register(registry);
    attachments = Counter.builder("smtp2x.mail.attachments")
        .description("Attachments in durably accepted SMTP messages")
        .register(registry);
    mailSize = DistributionSummary.builder("smtp2x.mail.size")
        .description("Size of durably accepted SMTP messages")
        .baseUnit("bytes")
        .register(registry);
    deliveryJobs = MultiGauge.builder("smtp2x.delivery.jobs")
        .description("Persisted delivery jobs by status")
        .register(registry);
    cleanupJobs = MultiGauge.builder("smtp2x.cleanup.jobs")
        .description("Persisted cleanup jobs by status")
        .register(registry);
    registerBoundedMeters();
  }

  private void registerBoundedMeters() {
    for (RejectionReason reason : RejectionReason.values()) {
      Counter.builder("smtp2x.mail.rejected")
          .description("SMTP messages rejected by SMTP2X")
          .tag("reason", label(reason))
          .register(registry);
    }
    for (ActionType actionType : ActionType.values()) {
      Counter.builder("smtp2x.action.triggered")
          .description("Action delivery jobs created from accepted messages")
          .tag("action_type", label(actionType))
          .register(registry);
      for (Outcome outcome : Outcome.values()) {
        Tags tags = Tags.of("action_type", label(actionType), "outcome", label(outcome));
        Counter.builder("smtp2x.action.delivery.attempts")
            .description("Action delivery attempts by outcome")
            .tags(tags)
            .register(registry);
        Timer.builder("smtp2x.action.delivery.duration")
            .description("Action delivery attempt duration")
            .tags(tags)
            .register(registry);
        Counter.builder("smtp2x.cleanup.attempts")
            .description("External cleanup attempts by outcome")
            .tags(tags)
            .register(registry);
      }
    }
  }

  public void mailAccepted(long sizeBytes, int attachmentCount,
      Collection<ActionType> actionTypes) {
    afterCommit(() -> {
      receivedMail.increment();
      mailSize.record(sizeBytes);
      attachments.increment(attachmentCount);
      actionTypes.forEach(actionType -> registry.counter("smtp2x.action.triggered",
          "action_type", label(actionType)).increment());
    });
  }

  public void mailRejected(RejectionReason reason) {
    registry.counter("smtp2x.mail.rejected", "reason", label(reason)).increment();
  }

  public void deliveryAttempt(ActionType actionType, Outcome outcome, Duration duration) {
    afterCommit(() -> {
      Tags tags = Tags.of("action_type", label(actionType), "outcome", label(outcome));
      registry.counter("smtp2x.action.delivery.attempts", tags).increment();
      registry.timer("smtp2x.action.delivery.duration", tags).record(duration);
    });
  }

  public void cleanupAttempt(ActionType actionType, Outcome outcome) {
    registry.counter("smtp2x.cleanup.attempts", "action_type", label(actionType),
        "outcome", label(outcome)).increment();
  }

  @PostConstruct
  @Scheduled(fixedDelayString = "${smtp2x.metrics.refresh-ms:5000}")
  public void refreshQueueGauges() {
    Map<DeliveryStatus, Long> deliveryCounts = new EnumMap<>(DeliveryStatus.class);
    deliveries.countStatuses().forEach(row -> deliveryCounts.put(row.getStatus(), row.getTotal()));
    deliveryJobs.register(Arrays.stream(DeliveryStatus.values())
        .map(status -> MultiGauge.Row.of(Tags.of("status", label(status)),
            deliveryCounts.getOrDefault(status, 0L)))
        .toList(), true);

    Map<CleanupStatus, Long> cleanupCounts = new EnumMap<>(CleanupStatus.class);
    cleanups.countStatuses().forEach(row -> cleanupCounts.put(row.getStatus(), row.getTotal()));
    cleanupJobs.register(Arrays.stream(CleanupStatus.values())
        .map(status -> MultiGauge.Row.of(Tags.of("status", label(status)),
            cleanupCounts.getOrDefault(status, 0L)))
        .toList(), true);
  }

  private void afterCommit(Runnable recording) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          recording.run();
        }
      });
    } else {
      recording.run();
    }
  }

  private String label(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }
}
