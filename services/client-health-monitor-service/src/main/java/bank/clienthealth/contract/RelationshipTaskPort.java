package bank.clienthealth.contract;

/**
 * In-process boundary between detection and task management.
 *
 * <p>Garvit's analysis stream calls this port. Swayam's task stream owns the
 * implementation. Callers may retry the same finding and must receive the
 * existing task outcome rather than creating a duplicate.</p>
 */
@FunctionalInterface
public interface RelationshipTaskPort {

    TaskUpsertResult upsertFinding(ClientHealthFinding finding);
}
