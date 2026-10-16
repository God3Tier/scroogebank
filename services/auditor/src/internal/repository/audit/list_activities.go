package audit

import (
	"context"
	"strings"

	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
	pkgerrors "github.com/pkg/errors"
)

// changesOnly keeps the entries the activity feed shows: an allowed action for
// its source service (models.ActivityActions), and not a failed attempt. A
// payload's status_code is the HTTP status of the request it records, so 400
// or above means nothing changed. Payloads without a numeric status_code are
// kept.
var changesOnly, changesOnlyArgs = func() (string, []any) {
	var clauses []string
	var args []any
	for _, allowed := range models.ActivityActions {
		placeholders := strings.TrimSuffix(strings.Repeat("?,", len(allowed.Actions)), ",")
		clauses = append(clauses, "(source_service = ? AND upper(action) IN ("+placeholders+"))")
		args = append(args, string(allowed.Source))
		for _, action := range allowed.Actions {
			args = append(args, action)
		}
	}
	// payload is json, not jsonb (data/schema.sql), hence json_typeof.
	succeeded := `CASE WHEN json_typeof(payload->'status_code') = 'number'
		THEN (payload->>'status_code')::numeric < 400 ELSE true END`
	return "(" + strings.Join(clauses, " OR ") + ") AND " + succeeded, args
}()

// ListActivities returns the changes matching filter, newest first. Paging is
// by sequence number rather than offset, so entries appended while a caller is
// paging do not shift the pages it has not read yet.
func (i impl) ListActivities(ctx context.Context, filter models.ActivityFilter) ([]models.AuditLog, error) {
	mods := []qm.QueryMod{qm.Where(changesOnly, changesOnlyArgs...)}
	if filter.ActorID != "" {
		mods = append(mods, qm.Where("actor_id = ?", filter.ActorID))
	}
	// "timestamp" is quoted because it is also a type name in SQL.
	if !filter.From.IsZero() {
		mods = append(mods, qm.Where(`"timestamp" >= ?`, filter.From))
	}
	if !filter.To.IsZero() {
		mods = append(mods, qm.Where(`"timestamp" < ?`, filter.To))
	}
	if filter.BeforeSequenceNo > 0 {
		mods = append(mods, qm.Where("sequence_no < ?", filter.BeforeSequenceNo))
	}
	mods = append(mods, qm.OrderBy("sequence_no desc"), qm.Limit(filter.Limit))

	raw, err := orm.AuditLogs(mods...).All(ctx, i.db)
	if err != nil {
		return nil, pkgerrors.WithStack(err)
	}

	response := make([]models.AuditLog, 0, len(raw))
	for _, auditLog := range raw {
		response = append(response, mapToAuditLog(auditLog))
	}
	return response, nil
}
