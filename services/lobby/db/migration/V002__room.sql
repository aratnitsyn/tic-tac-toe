CREATE TABLE room (
    room_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    code varchar(10) NOT NULL UNIQUE CHECK (code ~ '^[A-HJ-NP-Z2-9]{10}$'),
    status text NOT NULL CHECK (status IN ('REGISTERING','WAITING','PREPARING','CLOSED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    source_version bigint NOT NULL DEFAULT -1,
    match_id uuid,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE room_member (
    room_id uuid NOT NULL REFERENCES room,
    user_id uuid NOT NULL,
    PRIMARY KEY (room_id,user_id)
);
CREATE TABLE room_operation (
    operation_id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    command_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('CREATE','JOIN','LEAVE')),
    payload jsonb NOT NULL,
    room_id uuid NOT NULL REFERENCES room,
    game_payload jsonb NOT NULL,
    correlation_id uuid NOT NULL,
    response jsonb,
    response_status integer,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(user_id,kind,command_id),
    CHECK ((response IS NULL) = (response_status IS NULL))
);
CREATE INDEX room_operation_pending ON room_operation(created_at) WHERE response IS NULL;
CREATE TABLE code_attempt (
    scope text PRIMARY KEY,
    window_start timestamptz NOT NULL,
    attempts integer NOT NULL
);
