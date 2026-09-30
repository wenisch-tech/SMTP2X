package tech.wenisch.smtp2x.domain;
import jakarta.persistence.*; import java.util.UUID;
@Entity @Table(name="inbound_attachment") public class InboundAttachment {
 @Id private UUID id=UUID.randomUUID(); @Column(name="message_id",nullable=false) private UUID messageId; @Column(nullable=false,length=500) private String filename; @Column(name="content_type",length=255) private String contentType; @Column(name="content_path",nullable=false,length=1000) private String contentPath; @Column(name="size_bytes",nullable=false) private long sizeBytes;
 protected InboundAttachment(){} public InboundAttachment(UUID messageId,String filename,String contentType,String path,long size){this.messageId=messageId;this.filename=filename;this.contentType=contentType;this.contentPath=path;this.sizeBytes=size;} public UUID getId(){return id;} public UUID getMessageId(){return messageId;} public String getFilename(){return filename;} public String getContentType(){return contentType;} public String getContentPath(){return contentPath;} public long getSizeBytes(){return sizeBytes;}
}
