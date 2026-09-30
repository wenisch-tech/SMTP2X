CREATE TABLE inbound_attachment (
  id UUID PRIMARY KEY, message_id UUID NOT NULL REFERENCES inbound_message(id) ON DELETE CASCADE,
  filename VARCHAR(500) NOT NULL, content_type VARCHAR(255), content_path VARCHAR(1000) NOT NULL, size_bytes BIGINT NOT NULL
);
CREATE INDEX ix_attachment_message ON inbound_attachment(message_id);
