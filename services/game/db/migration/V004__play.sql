ALTER TABLE game ALTER COLUMN turn_deadline DROP NOT NULL;
ALTER TABLE game ADD COLUMN saved_turn text NOT NULL DEFAULT 'X_TURN' CHECK (saved_turn IN ('X_TURN','O_TURN'));
ALTER TABLE game ADD COLUMN remaining_turn_ns bigint NOT NULL DEFAULT 5000000000 CHECK (remaining_turn_ns BETWEEN 0 AND 5000000000);
ALTER TABLE game ADD COLUMN recovery_x_ns bigint NOT NULL DEFAULT 30000000000 CHECK (recovery_x_ns BETWEEN 0 AND 30000000000);
ALTER TABLE game ADD COLUMN recovery_o_ns bigint NOT NULL DEFAULT 30000000000 CHECK (recovery_o_ns BETWEEN 0 AND 30000000000);
ALTER TABLE game ADD COLUMN x_disconnected_at timestamptz;
ALTER TABLE game ADD COLUMN o_disconnected_at timestamptz;
ALTER TABLE game ADD COLUMN x_recovery_deadline timestamptz;
ALTER TABLE game ADD COLUMN o_recovery_deadline timestamptz;
ALTER TABLE game ADD COLUMN result jsonb;
ALTER TABLE game ADD COLUMN deadline_generation bigint NOT NULL DEFAULT 0 CHECK (deadline_generation >= 0);
ALTER TABLE game ADD CHECK ((x_disconnected_at IS NULL) = (x_recovery_deadline IS NULL));
ALTER TABLE game ADD CHECK ((o_disconnected_at IS NULL) = (o_recovery_deadline IS NULL));
CREATE TABLE game_deadline (
    match_id uuid NOT NULL REFERENCES game(match_id),
    kind text NOT NULL CHECK (kind IN ('TURN','RECOVERY_X','RECOVERY_O')),
    generation bigint NOT NULL,
    expected_version bigint NOT NULL,
    due_at timestamptz NOT NULL,
    lease_owner uuid,
    lease_until timestamptz,
    PRIMARY KEY(match_id,kind)
);
CREATE INDEX game_deadline_due ON game_deadline(due_at);
INSERT INTO game_deadline(match_id,kind,generation,expected_version,due_at)
SELECT match_id,'TURN',deadline_generation,version,turn_deadline FROM game WHERE state IN ('X_TURN','O_TURN');
