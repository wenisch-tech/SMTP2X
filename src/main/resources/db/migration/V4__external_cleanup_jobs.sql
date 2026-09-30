CREATE TABLE external_cleanup_job (
  id UUID PRIMARY KEY,
  delivery_id UUID NOT NULL UNIQUE,
  action_type VARCHAR(32) NOT NULL,
  configuration_snapshot CLOB NOT NULL,
  resource_reference VARCHAR(200) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INTEGER NOT NULL,
  due_at TIMESTAMP WITH TIME ZONE NOT NULL,
  next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
  claimed_at TIMESTAMP WITH TIME ZONE,
  last_error CLOB,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_external_cleanup_due ON external_cleanup_job(status, next_attempt_at);
