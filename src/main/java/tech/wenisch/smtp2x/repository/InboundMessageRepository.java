package tech.wenisch.smtp2x.repository;
import java.time.*; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import tech.wenisch.smtp2x.domain.InboundMessage;
public interface InboundMessageRepository extends JpaRepository<InboundMessage,UUID>{List<InboundMessage> findByReceivedAtBefore(Instant instant);}
