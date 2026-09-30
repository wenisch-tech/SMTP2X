package tech.wenisch.smtp2x.service;

public record DeliveryResult(String remoteUrl, String diagnostics, String warnings,
    String cleanupReference) {
  public DeliveryResult(String remoteUrl, String diagnostics, String warnings) {
    this(remoteUrl, diagnostics, warnings, "");
  }
}
