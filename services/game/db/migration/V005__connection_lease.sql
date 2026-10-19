ALTER TABLE match_member ADD COLUMN connection_lease_until timestamptz DEFAULT (clock_timestamp()+interval '6 seconds');
CREATE INDEX match_connection_due ON match_member(connection_lease_until) WHERE connected;
