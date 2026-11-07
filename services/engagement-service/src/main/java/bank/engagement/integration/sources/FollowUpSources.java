package bank.engagement.integration.sources;

/**
 * What a follow-up draft came from (ADR-0068): a Client Health task, read
 * from its owning service with the calling agent's token. (The alert follow-up
 * was replaced by News Alert drafts, news-alert-drafts plan.)
 * That service decides ownership, so a task or alert the agent may not see
 * comes back as not found. Only the client the task is about is returned;
 * its signals and evidence are never read.
 */
public interface FollowUpSources {

    /**
     * @return the CRM client the task is about
     * @throws SourceNotFoundException    the agent may not see this task
     * @throws SourceUnavailableException Client Health could not answer; callers fail closed
     */
    String healthTaskClient(String taskId);
}
