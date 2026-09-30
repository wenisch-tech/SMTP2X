package tech.wenisch.smtp2x.repository;
import java.time.*; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import tech.wenisch.smtp2x.domain.AuditEvent;
public interface AuditEventRepository extends JpaRepository<AuditEvent,UUID>{List<AuditEvent> findByOccurredAtBefore(Instant instant);}
