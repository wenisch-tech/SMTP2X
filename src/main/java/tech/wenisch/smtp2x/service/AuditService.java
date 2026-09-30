package tech.wenisch.smtp2x.service;
import org.springframework.stereotype.Service; import tech.wenisch.smtp2x.domain.AuditEvent; import tech.wenisch.smtp2x.repository.AuditEventRepository;
@Service public class AuditService { private final AuditEventRepository events; public AuditService(AuditEventRepository events){this.events=events;} public void record(String actor,String type,String objectType,String objectId,String detail){events.save(new AuditEvent(actor,type,objectType,objectId,detail));} }
