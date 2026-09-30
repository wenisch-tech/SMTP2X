package tech.wenisch.smtp2x.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_cleanup_job")
public class ExternalCleanupJob {
  @Id
  private UUID id = UUID.randomUUID();
  @Column(name = "delivery_id", nullable = false, unique = true)
  private UUID deliveryId;
  @Enumerated(EnumType.STRING)
  @Column(name = "action_type", nullable = false, length = 32)
  private ActionType actionType;
  @Lob
  @Column(name = "configuration_snapshot", nullable = false)
  private String configurationSnapshot;
  @Column(name = "resource_reference", nullable = false, length = 200)
  private String resourceReference;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private CleanupStatus status = CleanupStatus.PENDING;
  @Column(nullable = false)
  private int attempts;
  @Column(name = "due_at", nullable = false)
  private Instant dueAt;
  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;
  @Column(name = "claimed_at")
  private Instant claimedAt;
  @Lob
  @Column(name = "last_error")
  private String lastError = "";
  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  protected ExternalCleanupJob() {
  }

  public ExternalCleanupJob(UUID deliveryId, ActionType actionType,
      String configurationSnapshot, String resourceReference, Instant dueAt) {
    this.deliveryId = deliveryId;
    this.actionType = actionType;
    this.configurationSnapshot = configurationSnapshot;
    this.resourceReference = resourceReference;
    this.dueAt = dueAt;
    this.nextAttemptAt = dueAt;
  }

  public UUID getId() { return id; }
  public UUID getDeliveryId() { return deliveryId; }
  public ActionType getActionType() { return actionType; }
  public String getConfigurationSnapshot() { return configurationSnapshot; }
  public String getResourceReference() { return resourceReference; }
  public CleanupStatus getStatus() { return status; }
  public int getAttempts() { return attempts; }
  public Instant getDueAt() { return dueAt; }
  public Instant getNextAttemptAt() { return nextAttemptAt; }
  public String getLastError() { return lastError; }

  public void claim() {
    status = CleanupStatus.RUNNING;
    claimedAt = Instant.now();
    updatedAt = Instant.now();
  }

  public void succeed() {
    status = CleanupStatus.SUCCEEDED;
    claimedAt = null;
    lastError = "";
    configurationSnapshot = "{}";
    updatedAt = Instant.now();
  }

  public void fail(String error, boolean retry, Instant nextAttempt) {
    attempts++;
    lastError = error == null ? "Unknown cleanup error" : error;
    status = retry ? CleanupStatus.PENDING : CleanupStatus.FAILED;
    if (!retry) configurationSnapshot = "{}";
    nextAttemptAt = nextAttempt;
    claimedAt = null;
    updatedAt = Instant.now();
  }

  public void recover(Instant nextAttempt) {
    status = CleanupStatus.PENDING;
    claimedAt = null;
    nextAttemptAt = nextAttempt;
    lastError = "Recovered cleanup interrupted by an application restart";
    updatedAt = Instant.now();
  }
}
