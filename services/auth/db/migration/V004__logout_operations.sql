CREATE TABLE logout_operations (
    operation_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES accounts(user_id),
    session_id uuid NOT NULL UNIQUE,
    command_id uuid NOT NULL,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(user_id, command_id)
);
