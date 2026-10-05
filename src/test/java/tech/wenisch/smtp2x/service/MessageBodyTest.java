package tech.wenisch.smtp2x.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.wenisch.smtp2x.domain.InboundMessage;

class MessageBodyTest {
  @Test
  void plainTextIsPreferredOverHtmlAlternative() {
    var message = new InboundMessage("sender@example.com", "[]", "Subject", "Plain details",
        "<p>HTML details</p>", "unused");

    assertThat(MessageBody.markdown(message)).isEqualTo("Plain details");
  }

  @Test
  void htmlOnlyMessageBecomesReadableMarkdownAndKeepsImageCid() {
    var message = new InboundMessage("sender@example.com", "[]", "Subject", null,
        "<h2>Failure</h2><p>See <a href='https://status.example.com'>status</a>.</p>"
            + "<img src='cid:graph@example' alt='Latency graph'>",
        "unused");

    assertThat(MessageBody.markdown(message))
        .contains("## Failure")
        .contains("[status](https://status.example.com)")
        .contains("![Latency graph](cid:graph@example)");
  }
}
