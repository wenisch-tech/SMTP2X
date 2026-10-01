package tech.wenisch.smtp2x.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.wenisch.smtp2x.domain.ActionType;
import tech.wenisch.smtp2x.domain.CleanupStatus;
import tech.wenisch.smtp2x.domain.DeliveryStatus;
import tech.wenisch.smtp2x.repository.DeliveryJobRepository;
import tech.wenisch.smtp2x.repository.ExternalCleanupJobRepository;

class ApplicationMetricsTest {
  @Test
  void recordsBoundedApplicationMetricsAndPersistedQueueState() {
    var registry = new SimpleMeterRegistry();
    var deliveryRepository = mock(DeliveryJobRepository.class);
    var cleanupRepository = mock(ExternalCleanupJobRepository.class);
    var pendingDeliveries = mock(DeliveryJobRepository.StatusCount.class);
    var failedCleanups = mock(ExternalCleanupJobRepository.StatusCount.class);
    when(pendingDeliveries.getStatus()).thenReturn(DeliveryStatus.PENDING);
    when(pendingDeliveries.getTotal()).thenReturn(3L);
    when(failedCleanups.getStatus()).thenReturn(CleanupStatus.FAILED);
    when(failedCleanups.getTotal()).thenReturn(2L);
    when(deliveryRepository.countStatuses()).thenReturn(List.of(pendingDeliveries));
    when(cleanupRepository.countStatuses()).thenReturn(List.of(failedCleanups));
    var metrics = new ApplicationMetrics(registry, deliveryRepository, cleanupRepository);

    metrics.mailAccepted(2048, 2, List.of(ActionType.WEBHOOK, ActionType.GITLAB_ISSUE));
    metrics.mailRejected(ApplicationMetrics.RejectionReason.INVALID);
    metrics.deliveryAttempt(ActionType.WEBHOOK, ApplicationMetrics.Outcome.RETRY,
        Duration.ofMillis(250));
    metrics.cleanupAttempt(ActionType.GITLAB_ISSUE, ApplicationMetrics.Outcome.FAILED);
    metrics.refreshQueueGauges();

    assertThat(registry.get("smtp2x.mail.received").counter().count()).isEqualTo(1);
    assertThat(registry.get("smtp2x.mail.size").summary().totalAmount()).isEqualTo(2048);
    assertThat(registry.get("smtp2x.mail.attachments").counter().count()).isEqualTo(2);
    assertThat(registry.get("smtp2x.mail.rejected").tag("reason", "invalid").counter().count())
        .isEqualTo(1);
    assertThat(registry.get("smtp2x.action.triggered").tag("action_type", "webhook")
        .counter().count()).isEqualTo(1);
    assertThat(registry.get("smtp2x.action.delivery.attempts")
        .tags("action_type", "webhook", "outcome", "retry").counter().count()).isEqualTo(1);
    assertThat(registry.get("smtp2x.action.delivery.duration")
        .tags("action_type", "webhook", "outcome", "retry").timer().totalTime(
            java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(250);
    assertThat(registry.get("smtp2x.cleanup.attempts")
        .tags("action_type", "gitlab_issue", "outcome", "failed").counter().count())
        .isEqualTo(1);
    assertThat(registry.get("smtp2x.delivery.jobs").tag("status", "pending").gauge().value())
        .isEqualTo(3);
    assertThat(registry.get("smtp2x.cleanup.jobs").tag("status", "failed").gauge().value())
        .isEqualTo(2);
  }
}
