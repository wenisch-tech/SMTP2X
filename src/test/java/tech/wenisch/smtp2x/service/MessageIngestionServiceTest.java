package tech.wenisch.smtp2x.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tech.wenisch.smtp2x.config.Smtp2xProperties;
import tech.wenisch.smtp2x.domain.ActionConfiguration;
import tech.wenisch.smtp2x.domain.ActionType;
import tech.wenisch.smtp2x.domain.InboundAttachment;
import tech.wenisch.smtp2x.domain.InboundMessage;
import tech.wenisch.smtp2x.domain.RoutingRule;
import tech.wenisch.smtp2x.repository.ActionConfigurationRepository;
import tech.wenisch.smtp2x.repository.DeliveryJobRepository;
import tech.wenisch.smtp2x.repository.InboundAttachmentRepository;
import tech.wenisch.smtp2x.repository.InboundMessageRepository;
import tech.wenisch.smtp2x.repository.RoutingRuleRepository;

class MessageIngestionServiceTest {
  @TempDir Path dataDirectory;

  @Test
  void htmlOnlyMimeMessageStoresMarkdownBodyAndInlineImageMetadata() throws Exception {
    var messages = mock(InboundMessageRepository.class);
    var attachments = mock(InboundAttachmentRepository.class);
    var rules = mock(RoutingRuleRepository.class);
    var actions = mock(ActionConfigurationRepository.class);
    var deliveries = mock(DeliveryJobRepository.class);
    var action = new ActionConfiguration("Webhook", ActionType.WEBHOOK, "{}");
    var rule = new RoutingRule("All", true, null, null, null,
        RoutingRule.SubjectMode.CONTAINS, List.of(action.getId()));
    when(rules.findAll()).thenReturn(List.of(rule));
    when(actions.findAllById(any())).thenReturn(List.of(action));
    when(messages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var smtp = new Smtp2xProperties.Smtp(true, 2525, 1024 * 1024, 10, 5,
        Smtp2xProperties.Mode.DISABLED, Smtp2xProperties.Mode.DISABLED, List.of(), null, null,
        null, null, null, null);
    var properties = new Smtp2xProperties(dataDirectory.toString(), null, null, smtp, null);
    var service = new MessageIngestionService(messages, attachments, rules, actions, deliveries,
        new ObjectMapper(), properties, new RuleMatcher(), mock(AuditService.class),
        mock(ApplicationMetrics.class));
    String raw = "From: sender@example.com\r\nTo: ops@example.com\r\nSubject: Graph alert\r\n"
        + "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=parts\r\n\r\n"
        + "--parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
        + "<p>Latency is high.</p><img src=\"cid:graph-1\" alt=\"Graph\">\r\n"
        + "--parts\r\nContent-Type: image/png\r\nContent-ID: <graph-1>\r\n"
        + "Content-Disposition: inline; filename=graph.png\r\n"
        + "Content-Transfer-Encoding: base64\r\n\r\naW1hZ2U=\r\n--parts--\r\n";

    service.accept("sender@example.com", List.of("ops@example.com"),
        raw.getBytes(StandardCharsets.US_ASCII));

    ArgumentCaptor<InboundMessage> message = ArgumentCaptor.forClass(InboundMessage.class);
    org.mockito.Mockito.verify(messages).save(message.capture());
    assertThat(message.getValue().getTextBody())
        .contains("Latency is high.").contains("![Graph](cid:graph-1)");
    ArgumentCaptor<InboundAttachment> attachment = ArgumentCaptor.forClass(InboundAttachment.class);
    org.mockito.Mockito.verify(attachments).save(attachment.capture());
    assertThat(attachment.getValue().getContentId()).isEqualTo("graph-1");
    assertThat(attachment.getValue().isInline()).isTrue();
    assertThat(Files.readString(Path.of(attachment.getValue().getContentPath())))
        .isEqualTo("image");
  }
}
