package tech.wenisch.smtp2x.service;
public record DeliveryResult(String remoteUrl, String diagnostics, String warnings) {}
