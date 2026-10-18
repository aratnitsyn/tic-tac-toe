-- Every mutation of eligibility/order takes this singleton lock first.
CREATE TABLE queue_control (id boolean PRIMARY KEY CHECK(id), next_sequence numeric(40,0) NOT NULL DEFAULT 0);
INSERT INTO queue_control(id) VALUES(true);
CREATE TABLE queue_ticket (
 ticket_id uuid PRIMARY KEY, user_id uuid NOT NULL,
 admission_sequence numeric(40,0) NOT NULL UNIQUE, original_sequence numeric(40,0) NOT NULL,
 participation_version bigint NOT NULL CHECK(participation_version>=0),
 status text NOT NULL CHECK(status IN ('AVAILABLE','RESERVED','CONSUMED','CANCELLED')),
 connected boolean NOT NULL DEFAULT true, connection_generation bigint NOT NULL DEFAULT 0,
 version bigint NOT NULL DEFAULT 0, pair_id uuid,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX queue_ticket_active_user ON queue_ticket(user_id) WHERE status IN ('AVAILABLE','RESERVED');
CREATE INDEX queue_ticket_fifo ON queue_ticket(original_sequence,admission_sequence) WHERE status='AVAILABLE' AND connected;
CREATE TABLE queue_operation (
 operation_id uuid PRIMARY KEY, kind text NOT NULL CHECK(kind IN ('JOIN','CANCEL','PAIR')),
 user_id uuid NOT NULL, command_id uuid NOT NULL, payload jsonb NOT NULL,
 game_payload jsonb NOT NULL, correlation_id uuid NOT NULL,
 response_status integer, response_body jsonb,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(user_id,kind,command_id), CHECK((response_status IS NULL)=(response_body IS NULL))
);
CREATE INDEX queue_operation_pending ON queue_operation(created_at) WHERE response_status IS NULL;
