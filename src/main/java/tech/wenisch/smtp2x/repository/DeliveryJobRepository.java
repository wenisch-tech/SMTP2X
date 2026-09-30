package tech.wenisch.smtp2x.repository;
import java.time.*; import java.util.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import tech.wenisch.smtp2x.domain.*;
public interface DeliveryJobRepository extends JpaRepository<DeliveryJob,UUID>{
 @Query("select j from DeliveryJob j where j.status = :status and j.nextAttemptAt <= :now order by j.nextAttemptAt") List<DeliveryJob> findDue(@Param("status") DeliveryStatus status,@Param("now") Instant now);
 List<DeliveryJob> findByMessageId(UUID messageId); List<DeliveryJob> findByStatus(DeliveryStatus status);
}
