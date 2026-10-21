package bank.engagement.notifications;

import bank.engagement.audit.AuditAction;
import bank.engagement.audit.AuditTrail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PreferencesService {

    private final PreferencesRepository repository;
    private final MailQueue mailQueue;
    private final AuditTrail audit;
    private final Clock clock;

    public PreferencesService(PreferencesRepository repository, MailQueue mailQueue, AuditTrail audit, Clock clock) {
        this.repository = repository;
        this.mailQueue = mailQueue;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Preferences get(String agentSub) {
        return repository.findById(agentSub).map(AgentPreferences::view).orElse(Preferences.DEFAULTS);
    }

    /**
     * Turning invites off stops new invites only; entries already in the
     * agent's calendar stay (ADR-0052 decision 6). Turning reminders off
     * cancels every pending reminder.
     */
    @Transactional
    public Preferences update(String agentSub, Preferences requested) {
        Instant now = clock.instant();
        AgentPreferences stored = repository.findById(agentSub).orElseGet(() -> new AgentPreferences(agentSub));
        Preferences before = stored.view();
        stored.update(requested.invitesEnabled(), requested.remindersEnabled(), now);
        repository.save(stored);

        List<String> changed = new ArrayList<>();
        if (!before.invitesEnabled().equals(requested.invitesEnabled())) changed.add("invitesEnabled");
        if (!before.remindersEnabled().equals(requested.remindersEnabled())) changed.add("remindersEnabled");
        int cancelled = 0;
        if (before.remindersEnabled() && !requested.remindersEnabled()) {
            cancelled = mailQueue.cancelPendingRemindersFor(agentSub, now);
        }
        if (!changed.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("changedFields", changed);
            data.put("invitesEnabled", requested.invitesEnabled());
            data.put("remindersEnabled", requested.remindersEnabled());
            data.put("remindersCancelled", cancelled);
            audit.record(AuditAction.PREFERENCES_UPDATE, agentSub, data);
        }
        return stored.view();
    }
}
