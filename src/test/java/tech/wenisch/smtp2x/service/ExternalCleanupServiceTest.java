package tech.wenisch.smtp2x.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.smtp2x.domain.ActionType;
import tech.wenisch.smtp2x.domain.CleanupStatus;
import tech.wenisch.smtp2x.domain.ExternalCleanupJob;
import tech.wenisch.smtp2x.repository.ExternalCleanupJobRepository;

class ExternalCleanupServiceTest {
  @Test
  void schedulesAndRunsDurableProviderCleanup() throws Exception {
    var repository = mock(ExternalCleanupJobRepository.class);
    var handler = mock(CleanupActionHandler.class);
    var audit = mock(AuditService.class);
    var metrics = mock(ApplicationMetrics.class);
    var json = new ObjectMapper();
    when(handler.type()).thenReturn(ActionType.GITLAB_ISSUE);
    when(repository.save(any(ExternalCleanupJob.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var service = new ExternalCleanupService(repository, json, List.of(handler), audit, metrics);
    var configuration = json.createObjectNode();
    configuration.put("autoDeleteAfter", "5m");
    configuration.put("accessToken", "enc:test-token");
    String snapshot = json.createObjectNode()
        .put("type", ActionType.GITLAB_ISSUE.name())
        .set("configuration", configuration)
        .toString();
    UUID deliveryId = UUID.randomUUID();
    Instant before = Instant.now().plusSeconds(299);

    service.schedule(deliveryId, ActionType.GITLAB_ISSUE, snapshot, configuration, "42");

    ArgumentCaptor<ExternalCleanupJob> capture = ArgumentCaptor.forClass(ExternalCleanupJob.class);
    verify(repository).save(capture.capture());
    ExternalCleanupJob job = capture.getValue();
    assertThat(job.getDeliveryId()).isEqualTo(deliveryId);
    assertThat(job.getResourceReference()).isEqualTo("42");
    assertThat(job.getDueAt()).isAfter(before);
    assertThat(job.getStatus()).isEqualTo(CleanupStatus.PENDING);

    when(repository.findDue(eq(CleanupStatus.PENDING), any())).thenReturn(List.of(job));
    when(repository.findStale(eq(CleanupStatus.RUNNING), any())).thenReturn(List.of());
    when(repository.findById(job.getId())).thenReturn(Optional.of(job));
    service.processDue();

    verify(handler).cleanup(configuration, "42");
    assertThat(job.getStatus()).isEqualTo(CleanupStatus.SUCCEEDED);
    assertThat(job.getLastError()).isEmpty();
    assertThat(job.getConfigurationSnapshot()).isEqualTo("{}");
    verify(metrics).cleanupAttempt(ActionType.GITLAB_ISSUE,
        ApplicationMetrics.Outcome.SUCCEEDED);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void cleanupMetricsDistinguishRetryAndTerminalFailureWithoutDoubleCounting() throws Exception {
    var repository = mock(ExternalCleanupJobRepository.class);
    var handler = mock(CleanupActionHandler.class);
    var audit = mock(AuditService.class);
    var metrics = mock(ApplicationMetrics.class);
    var json = new ObjectMapper();
    when(handler.type()).thenReturn(ActionType.FORGEJO_ISSUE);
    doThrow(new DeliveryException("Forgejo cleanup returned 503", true))
        .doThrow(new DeliveryException("Forgejo cleanup rejected the request", false))
        .when(handler).cleanup(any(), eq("23"));
    String snapshot = "{\"configuration\":{\"accessToken\":\"enc:test-token\"}}";
    var job = new ExternalCleanupJob(UUID.randomUUID(), ActionType.FORGEJO_ISSUE,
        snapshot, "23", Instant.now());
    var failedJob = new ExternalCleanupJob(UUID.randomUUID(), ActionType.FORGEJO_ISSUE,
        snapshot, "23", Instant.now());
    job.claim();
    failedJob.claim();
    when(repository.findById(job.getId())).thenReturn(Optional.of(job));
    when(repository.findById(failedJob.getId())).thenReturn(Optional.of(failedJob));
    var service = new ExternalCleanupService(repository, json, List.of(handler), audit, metrics);

    service.cleanup(job.getId());
    service.cleanup(failedJob.getId());

    assertThat(job.getStatus()).isEqualTo(CleanupStatus.PENDING);
    assertThat(job.getAttempts()).isEqualTo(1);
    assertThat(job.getLastError()).contains("503");
    assertThat(job.getConfigurationSnapshot()).isEqualTo(snapshot);
    assertThat(failedJob.getStatus()).isEqualTo(CleanupStatus.FAILED);
    assertThat(failedJob.getAttempts()).isEqualTo(1);
    verify(metrics).cleanupAttempt(ActionType.FORGEJO_ISSUE,
        ApplicationMetrics.Outcome.RETRY);
    verify(metrics).cleanupAttempt(ActionType.FORGEJO_ISSUE,
        ApplicationMetrics.Outcome.FAILED);
    verifyNoMoreInteractions(metrics);
  }
}
