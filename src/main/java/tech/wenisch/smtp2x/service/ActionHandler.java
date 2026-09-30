package tech.wenisch.smtp2x.service;
import com.fasterxml.jackson.databind.JsonNode; import tech.wenisch.smtp2x.domain.ActionType;
public interface ActionHandler { ActionType type(); DeliveryResult deliver(MessageData message, JsonNode configuration) throws DeliveryException; }
