package tech.wenisch.smtp2x.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tech.wenisch.smtp2x.domain.ActionType;
import tech.wenisch.smtp2x.domain.DeliveryJob;
import tech.wenisch.smtp2x.domain.DeliveryStatus;
import tech.wenisch.smtp2x.domain.InboundMessage;
import tech.wenisch.smtp2x.repository.DeliveryJobRepository;
import tech.wenisch.smtp2x.repository.InboundAttachmentRepository;
import tech.wenisch.smtp2x.repository.InboundMessageRepository;

class DeliveryServiceTest {
  @Test
  void backgroundDeliveryPersistsClaimAndSchedulesCleanup() throws Exception {
    var jobs = mock(DeliveryJobRepository.class);
    var messages = mock(InboundMessageRepository.class);
    var attachments = mock(InboundAttachmentRepository.class);
    var handler = mock(CleanupActionHandler.class);
    var audit = mock(AuditService.class);
    var cleanups = mock(ExternalCleanupService.class);
    var json = new ObjectMapper();
    var message = new InboundMessage("sender@example.com", "[\"ops@example.com\"]",
        "Alert", "Details", null, "unused");
    String snapshot = json.createObjectNode()
        .put("type", ActionType.GITLAB_ISSUE.name())
        .set("configuration", json.createObjectNode().put("autoDeleteAfter", "5m"))
        .toString();
    var job = new DeliveryJob(message.getId(), java.util.UUID.randomUUID(), snapshot);
    var result = new DeliveryResult("https://gitlab.example/issues/42", "created", "", "42");
    when(handler.type()).thenReturn(ActionType.GITLAB_ISSUE);
    when(handler.deliver(any(), any())).thenReturn(result);
    when(jobs.findDue(eq(DeliveryStatus.PENDING), any())).thenReturn(List.of(job));
    when(jobs.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
    when(messages.findById(message.getId())).thenReturn(Optional.of(message));
    when(attachments.findByMessageId(message.getId())).thenReturn(List.of());
    var service = new DeliveryService(jobs, messages, attachments, json, List.of(handler), audit,
        cleanups);

    service.processDue();

    assertThat(job.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
    verify(cleanups).schedule(eq(job.getId()), eq(ActionType.GITLAB_ISSUE), eq(snapshot),
        any(), eq("42"));
    verify(jobs).save(job);
  }
}
