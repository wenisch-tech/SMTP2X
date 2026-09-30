package tech.wenisch.smtp2x.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="delivery_job", uniqueConstraints=@UniqueConstraint(name="uk_delivery_message_action", columnNames={"message_id","action_id"}))
public class DeliveryJob {
  @Id private UUID id=UUID.randomUUID(); @Column(name="message_id",nullable=false) private UUID messageId; @Column(name="action_id",nullable=false) private UUID actionId;
  @Lob @Column(name="configuration_snapshot",nullable=false) private String configurationSnapshot;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private DeliveryStatus status=DeliveryStatus.PENDING;
  @Column(nullable=false) private int attempts; @Column(name="next_attempt_at",nullable=false) private Instant nextAttemptAt=Instant.now();
  @Column(name="claimed_at") private Instant claimedAt; @Lob private String warnings=""; @Lob private String diagnostics=""; @Column(name="remote_url",length=1000) private String remoteUrl;
  @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now(); @Column(name="updated_at",nullable=false) private Instant updatedAt=Instant.now();
  protected DeliveryJob(){} public DeliveryJob(UUID messageId,UUID actionId,String snapshot){this.messageId=messageId;this.actionId=actionId;this.configurationSnapshot=snapshot;}
  public UUID getId(){return id;} public UUID getMessageId(){return messageId;} public UUID getActionId(){return actionId;} public String getConfigurationSnapshot(){return configurationSnapshot;} public DeliveryStatus getStatus(){return status;} public int getAttempts(){return attempts;} public Instant getNextAttemptAt(){return nextAttemptAt;} public String getWarnings(){return warnings;} public String getDiagnostics(){return diagnostics;} public String getRemoteUrl(){return remoteUrl;}
  public void claim(){status=DeliveryStatus.RUNNING; claimedAt=Instant.now();updatedAt=Instant.now();} public void success(String url,String details,String warnings){status=DeliveryStatus.SUCCEEDED;remoteUrl=url;diagnostics=details;this.warnings=warnings;updatedAt=Instant.now();}
  public void fail(String details, boolean retry, Instant next){attempts++;diagnostics=details;status=retry?DeliveryStatus.PENDING:DeliveryStatus.FAILED;nextAttemptAt=next;claimedAt=null;updatedAt=Instant.now();} public void cancel(){status=DeliveryStatus.CANCELLED;updatedAt=Instant.now();} public void retryNow(){status=DeliveryStatus.PENDING;nextAttemptAt=Instant.now();claimedAt=null;updatedAt=Instant.now();}
}
