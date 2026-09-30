package tech.wenisch.smtp2x.service;
import java.util.List; import tech.wenisch.smtp2x.domain.*;
public record MessageData(InboundMessage message, List<String> recipients, List<InboundAttachment> attachments) {}
