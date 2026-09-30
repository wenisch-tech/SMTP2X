CREATE TABLE app_user (
  id UUID PRIMARY KEY, email VARCHAR(320) NOT NULL UNIQUE, password_hash VARCHAR(200) NOT NULL,
  role VARCHAR(16) NOT NULL, enabled BOOLEAN NOT NULL, password_change_required BOOLEAN NOT NULL,
  oidc_only BOOLEAN NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE action_configuration (
  id UUID PRIMARY KEY, name VARCHAR(120) NOT NULL UNIQUE, type VARCHAR(32) NOT NULL, enabled BOOLEAN NOT NULL,
  configuration_json CLOB NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE routing_rule (
  id UUID PRIMARY KEY, name VARCHAR(120) NOT NULL UNIQUE, global_rule BOOLEAN NOT NULL, enabled BOOLEAN NOT NULL,
  recipient_pattern VARCHAR(320), sender_pattern VARCHAR(320), subject_filter VARCHAR(1000), subject_mode VARCHAR(16), created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE routing_rule_action (rule_id UUID NOT NULL REFERENCES routing_rule(id) ON DELETE CASCADE, action_id UUID NOT NULL, PRIMARY KEY(rule_id, action_id));
CREATE TABLE inbound_message (
  id UUID PRIMARY KEY, envelope_from VARCHAR(320), recipients_json CLOB NOT NULL, subject VARCHAR(998), text_body CLOB,
  html_body CLOB, content_path VARCHAR(1000) NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE delivery_job (
  id UUID PRIMARY KEY, message_id UUID NOT NULL REFERENCES inbound_message(id) ON DELETE CASCADE, action_id UUID NOT NULL,
  configuration_snapshot CLOB NOT NULL, status VARCHAR(16) NOT NULL, attempts INTEGER NOT NULL, next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
  claimed_at TIMESTAMP WITH TIME ZONE, warnings CLOB, diagnostics CLOB, remote_url VARCHAR(1000), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL, CONSTRAINT uk_delivery_message_action UNIQUE(message_id, action_id)
);
CREATE INDEX ix_delivery_due ON delivery_job(status, next_attempt_at);
CREATE TABLE audit_event (
  id UUID PRIMARY KEY, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL, actor VARCHAR(320), event_type VARCHAR(80) NOT NULL,
  object_type VARCHAR(80), object_id VARCHAR(100), detail CLOB
);
CREATE INDEX ix_audit_occurred ON audit_event(occurred_at DESC);
