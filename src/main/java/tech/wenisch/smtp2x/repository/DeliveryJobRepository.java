package tech.wenisch.smtp2x.repository;
import java.time.*; import java.util.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import tech.wenisch.smtp2x.domain.*;
public interface DeliveryJobRepository extends JpaRepository<DeliveryJob,UUID>{
 @Query("select j from DeliveryJob j where j.status = :status and j.nextAttemptAt <= :now order by j.nextAttemptAt") List<DeliveryJob> findDue(@Param("status") DeliveryStatus status,@Param("now") Instant now);
 interface StatusCount { DeliveryStatus getStatus(); long getTotal(); }
 @Query("select j.status as status, count(j) as total from DeliveryJob j group by j.status") List<StatusCount> countStatuses();
 interface FailureSummary { UUID getId(); UUID getActionId(); UUID getMessageId(); int getAttempts(); Instant getUpdatedAt(); }
 @Query("select j.id as id, j.actionId as actionId, j.messageId as messageId, j.attempts as attempts, j.updatedAt as updatedAt from DeliveryJob j where j.status = tech.wenisch.smtp2x.domain.DeliveryStatus.FAILED order by j.updatedAt desc")
 List<FailureSummary> recentFailures(org.springframework.data.domain.Pageable page);
 List<DeliveryJob> findByMessageId(UUID messageId); List<DeliveryJob> findByStatus(DeliveryStatus status);
}
