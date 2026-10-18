-- Historical tickets remain queryable; the partial active-user index cannot
-- serve own-ticket snapshots/cancellation once a ticket is consumed or cancelled.
CREATE INDEX queue_ticket_user_latest ON queue_ticket(user_id,admission_sequence DESC);
