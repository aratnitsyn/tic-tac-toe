CREATE TABLE coordination_operation (
    operation_id uuid PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('CLAIM', 'REGISTER_ROOM', 'ASSIGN_PAIR')),
    payload jsonb NOT NULL,
    correlation_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED')),
    result jsonb,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    CHECK ((status = 'PENDING') = (completed_at IS NULL)),
    CHECK ((status = 'PENDING') = (result IS NULL))
);
CREATE INDEX coordination_pending ON coordination_operation(created_at) WHERE status = 'PENDING';

CREATE TABLE participation (
    user_id uuid PRIMARY KEY,
    state text NOT NULL DEFAULT 'MENU' CHECK (state IN ('MENU', 'LOBBY', 'QUEUED', 'IN_MATCH')),
    resource_id uuid,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    operation_id uuid REFERENCES coordination_operation(operation_id),
    CHECK ((state = 'MENU') = (resource_id IS NULL))
);
CREATE UNIQUE INDEX participation_queue_ticket ON participation(resource_id) WHERE state = 'QUEUED';
CREATE INDEX participation_pending ON participation(operation_id) WHERE operation_id IS NOT NULL;

CREATE TABLE coordination_member (
    operation_id uuid NOT NULL REFERENCES coordination_operation(operation_id),
    user_id uuid NOT NULL REFERENCES participation(user_id),
    PRIMARY KEY (operation_id, user_id)
);

CREATE TABLE room_registration (
    room_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES participation(user_id),
    active boolean NOT NULL DEFAULT true,
    operation_id uuid NOT NULL UNIQUE REFERENCES coordination_operation(operation_id)
);
CREATE TABLE room_slot (
    room_id uuid NOT NULL REFERENCES room_registration(room_id),
    slot smallint NOT NULL CHECK (slot IN (1, 2)),
    user_id uuid NOT NULL UNIQUE REFERENCES participation(user_id),
    PRIMARY KEY (room_id, slot),
    UNIQUE (room_id, user_id)
);

CREATE TABLE game_match (
    match_id uuid PRIMARY KEY,
    source text NOT NULL CHECK (source IN ('QUEUE', 'LOBBY')),
    source_ref uuid NOT NULL,
    state text NOT NULL CHECK (state IN ('WAITING_READY', 'COUNTDOWN', 'RUNNING', 'CANCELLED', 'FINISHED')),
    ready_deadline timestamptz NOT NULL,
    start_deadline timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    operation_id uuid NOT NULL UNIQUE REFERENCES coordination_operation(operation_id),
    correlation_id uuid NOT NULL
);
CREATE TABLE match_member (
    match_id uuid NOT NULL REFERENCES game_match(match_id),
    user_id uuid NOT NULL REFERENCES participation(user_id),
    slot smallint NOT NULL CHECK (slot IN (1, 2)),
    ready boolean NOT NULL DEFAULT false,
    ticket_id uuid,
    original_sequence numeric,
    active boolean NOT NULL DEFAULT true,
    PRIMARY KEY (match_id, user_id),
    UNIQUE (match_id, slot),
    CHECK ((ticket_id IS NULL) = (original_sequence IS NULL)),
    CHECK (original_sequence IS NULL OR original_sequence >= 0)
);
CREATE UNIQUE INDEX match_member_active_user ON match_member(user_id) WHERE active;
