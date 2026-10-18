package queue

import (
	"context"
	"github.com/jackc/pgx/v5"
)

// Availability is a prepared connection-generation adapter for the future
// Realtime integration. It never releases RESERVED players or changes priority.
func (s *Service) Availability(ctx context.Context, ticket string, generation int64, connected bool) error {
	if !validID(ticket) || generation < 0 {
		return fail("VALIDATION_ERROR", 400)
	}
	return s.transaction(ctx, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `UPDATE queue_ticket SET connected=$3,connection_generation=$2 WHERE ticket_id=$1 AND (connection_generation<$2 OR (connection_generation=$2 AND connected AND NOT $3))`, ticket, generation, connected)
		return err
	})
}
