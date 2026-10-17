CREATE TABLE command_results (
    user_id uuid NOT NULL,
    operation_type text NOT NULL CHECK (operation_type <> ''),
    command_id uuid NOT NULL,
    payload_hash char(64) NOT NULL,
    response_status integer NOT NULL CHECK (response_status BETWEEN 100 AND 599),
    response_body jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz,
    PRIMARY KEY (user_id, operation_type, command_id)
);
CREATE INDEX command_results_expiry ON command_results (expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE outbox (
    event_id uuid PRIMARY KEY,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
    event_type text NOT NULL,
    envelope jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_until timestamptz,
    lease_owner text
);
CREATE INDEX outbox_unpublished ON outbox (created_at, event_id) WHERE published_at IS NULL;

CREATE TABLE inbox (
    consumer_name text NOT NULL,
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_name, event_id)
);
