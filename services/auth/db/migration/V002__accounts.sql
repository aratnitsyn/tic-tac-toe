CREATE TABLE accounts (
    user_id uuid PRIMARY KEY,
    email_original varchar(254) NOT NULL,
    email_key varchar(254) NOT NULL UNIQUE,
    email_verified_at timestamptz,
    password_hash text NOT NULL,
    credential_version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- Future providers attach identities to a stable account without changing user_id.
CREATE TABLE identities (
    provider text NOT NULL,
    subject text NOT NULL,
    user_id uuid NOT NULL REFERENCES accounts(user_id),
    PRIMARY KEY (provider, subject)
);
-- No credentials or password-derived fingerprints in command_results.
-- Registration retries verify the supplied password against the account credential.
CREATE TABLE registration_commands (
    email_key varchar(254) NOT NULL,
    command_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES accounts(user_id),
    client_type text NOT NULL CHECK (client_type IN ('WEB', 'NATIVE')),
    response_body jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (email_key, command_id)
);
