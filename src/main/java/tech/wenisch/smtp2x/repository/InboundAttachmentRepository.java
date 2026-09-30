package tech.wenisch.smtp2x.repository;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import tech.wenisch.smtp2x.domain.InboundAttachment;
public interface InboundAttachmentRepository extends JpaRepository<InboundAttachment,UUID>{List<InboundAttachment> findByMessageId(UUID messageId);}
