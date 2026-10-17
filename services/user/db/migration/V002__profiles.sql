CREATE TABLE profiles (
    user_id uuid PRIMARY KEY,
    registered_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
