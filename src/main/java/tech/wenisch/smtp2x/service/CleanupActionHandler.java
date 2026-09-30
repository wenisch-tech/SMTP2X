package tech.wenisch.smtp2x.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface CleanupActionHandler extends ActionHandler {
  void cleanup(JsonNode configuration, String resourceReference) throws DeliveryException;
}
