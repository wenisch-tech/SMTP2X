package tech.wenisch.smtp2x.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tech.wenisch.smtp2x.domain.CleanupStatus;
import tech.wenisch.smtp2x.domain.ExternalCleanupJob;

public interface ExternalCleanupJobRepository extends JpaRepository<ExternalCleanupJob, UUID> {
  @Query("select j from ExternalCleanupJob j where j.status = :status "
      + "and j.nextAttemptAt <= :now order by j.nextAttemptAt")
  List<ExternalCleanupJob> findDue(@Param("status") CleanupStatus status,
      @Param("now") Instant now);

  @Query("select j from ExternalCleanupJob j where j.status = :status "
      + "and j.claimedAt < :cutoff order by j.claimedAt")
  List<ExternalCleanupJob> findStale(@Param("status") CleanupStatus status,
      @Param("cutoff") Instant cutoff);
}
