package tech.wenisch.smtp2x.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="inbound_message")
public class InboundMessage {
  @Id private UUID id=UUID.randomUUID(); @Column(name="envelope_from",length=320) private String envelopeFrom;
  @Lob @Column(name="recipients_json",nullable=false) private String recipientsJson;
  @Column(length=998) private String subject; @Lob @Column(name="text_body") private String textBody; @Lob @Column(name="html_body") private String htmlBody;
  @Column(name="content_path",nullable=false,length=1000) private String contentPath; @Column(name="received_at",nullable=false) private Instant receivedAt=Instant.now();
  protected InboundMessage(){} public InboundMessage(String from,String recipients,String subject,String text,String html,String contentPath){this.envelopeFrom=from;this.recipientsJson=recipients;this.subject=subject;this.textBody=text;this.htmlBody=html;this.contentPath=contentPath;}
  public UUID getId(){return id;} public String getEnvelopeFrom(){return envelopeFrom;} public String getRecipientsJson(){return recipientsJson;} public String getSubject(){return subject;} public String getTextBody(){return textBody;} public String getHtmlBody(){return htmlBody;} public String getContentPath(){return contentPath;} public Instant getReceivedAt(){return receivedAt;}
  public void contentPath(String value){contentPath=value;}
}
