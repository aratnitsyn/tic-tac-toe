ALTER TABLE queue_ticket ADD COLUMN availability_until timestamptz NOT NULL DEFAULT (clock_timestamp()+interval '6 seconds');
