package tech.wenisch.smtp2x.service;

import java.util.Optional;

final class MessageTemplate {
  private MessageTemplate() {}

  static String render(String template, MessageData data) {
    return template
        .replace("{{subject}}", Optional.ofNullable(data.message().getSubject()).orElse(""))
        .replace("{{body}}", MessageBody.markdown(data.message()))
        .replace("{{from}}", Optional.ofNullable(data.message().getEnvelopeFrom()).orElse(""))
        .replace("{{recipients}}", String.join(", ", data.recipients()));
  }
}
