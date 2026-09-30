package tech.wenisch.smtp2x.repository;
import java.time.*; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import tech.wenisch.smtp2x.domain.InboundMessage;
public interface InboundMessageRepository extends JpaRepository<InboundMessage,UUID>{
 interface Summary { UUID getId(); String getEnvelopeFrom(); String getSubject(); Instant getReceivedAt(); }
 @org.springframework.data.jpa.repository.Query("select m.id as id, m.envelopeFrom as envelopeFrom, m.subject as subject, m.receivedAt as receivedAt from InboundMessage m order by m.receivedAt desc")
 List<Summary> recent(org.springframework.data.domain.Pageable page);
 List<InboundMessage> findByReceivedAtBefore(Instant instant);}
