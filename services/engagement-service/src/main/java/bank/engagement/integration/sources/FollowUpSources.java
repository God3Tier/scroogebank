package bank.engagement.integration.sources;

import java.util.UUID;

/**
 * What a follow-up draft came from (ADR-0068): a Client Health task or an
 * event alert, read from its owning service with the calling agent's token.
 * That service decides ownership, so a task or alert the agent may not see
 * comes back as not found. Only the client the source is about is returned;
 * the task's signals and evidence and the alert's news are never read.
 */
public interface FollowUpSources {

    /**
     * @return the CRM client the task is about
     * @throws SourceNotFoundException    the agent may not see this task
     * @throws SourceUnavailableException Client Health could not answer; callers fail closed
     */
    String healthTaskClient(String taskId);

    /**
     * @return the alert's {@code clientRef}: the CRM client ID for CRM-linked
     *         holdings (ADR-0066), or a synthetic reference for older alerts
     * @throws SourceNotFoundException    the agent may not see this alert
     * @throws SourceUnavailableException event-alert-service could not answer; callers fail closed
     */
    String alertClient(UUID alertId);
}
