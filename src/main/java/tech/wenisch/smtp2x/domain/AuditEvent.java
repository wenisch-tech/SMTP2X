package tech.wenisch.smtp2x.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="audit_event") public class AuditEvent {
 @Id private UUID id=UUID.randomUUID(); @Column(nullable=false) private Instant occurredAt=Instant.now(); @Column(length=320) private String actor; @Column(nullable=false,length=80) private String eventType; @Column(length=80) private String objectType; @Column(length=100) private String objectId; @Lob private String detail;
 protected AuditEvent(){} public AuditEvent(String actor,String type,String objectType,String objectId,String detail){this.actor=actor;this.eventType=type;this.objectType=objectType;this.objectId=objectId;this.detail=detail;} public UUID getId(){return id;} public Instant getOccurredAt(){return occurredAt;} public String getActor(){return actor;} public String getEventType(){return eventType;} public String getObjectType(){return objectType;} public String getObjectId(){return objectId;} public String getDetail(){return detail;}
}
