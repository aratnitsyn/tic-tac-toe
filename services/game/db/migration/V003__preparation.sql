ALTER TABLE match_member ADD COLUMN connected boolean NOT NULL DEFAULT true;
ALTER TABLE match_member ADD COLUMN connection_generation bigint NOT NULL DEFAULT 0 CHECK (connection_generation >= 0);
ALTER TABLE match_member ADD COLUMN return_version bigint;
CREATE INDEX match_ready_due ON game_match(ready_deadline) WHERE state='WAITING_READY';
CREATE INDEX match_start_due ON game_match(start_deadline) WHERE state='COUNTDOWN';
CREATE TABLE game (
    match_id uuid PRIMARY KEY REFERENCES game_match(match_id),
    state text NOT NULL CHECK (state IN ('X_TURN','O_TURN','PAUSED','FINISHED')),
    board jsonb NOT NULL,
    x_player_id uuid NOT NULL REFERENCES participation(user_id),
    o_player_id uuid NOT NULL REFERENCES participation(user_id),
    turn_deadline timestamptz NOT NULL,
    recovery_x_ms integer NOT NULL DEFAULT 30000 CHECK (recovery_x_ms BETWEEN 0 AND 30000),
    recovery_o_ms integer NOT NULL DEFAULT 30000 CHECK (recovery_o_ms BETWEEN 0 AND 30000),
    version bigint NOT NULL DEFAULT 0,
    CHECK (x_player_id <> o_player_id)
);
