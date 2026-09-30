package tech.wenisch.smtp2x.service;
public class DeliveryException extends RuntimeException { private final boolean retryable; public DeliveryException(String message,boolean retryable){super(message);this.retryable=retryable;} public DeliveryException(String message,boolean retryable,Throwable cause){super(message,cause);this.retryable=retryable;} public boolean retryable(){return retryable;} }
