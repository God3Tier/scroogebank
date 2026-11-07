package bank.user.activity;

import java.util.List;

/** One newest-first page of the complete audit trail. */
public record AuditLogPage(List<AuditLogResponse> content, String nextCursor) {
}
