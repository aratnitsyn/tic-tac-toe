CREATE TABLE session_slots (
    user_id uuid PRIMARY KEY REFERENCES accounts(user_id),
    session_id uuid NOT NULL UNIQUE,
    token_hash char(64) NOT NULL UNIQUE,
    state text NOT NULL CHECK (state IN ('ACTIVE','CLOSING','CLOSED')),
    last_activity timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE TABLE login_responses (
    user_id uuid NOT NULL REFERENCES accounts(user_id),
    command_id uuid NOT NULL,
    session_id uuid NOT NULL,
    client_type text NOT NULL,
    key_id text,
    nonce bytea,
    ciphertext bytea,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (user_id,command_id)
);
CREATE INDEX login_response_expiry ON login_responses(expires_at) WHERE ciphertext IS NOT NULL;
