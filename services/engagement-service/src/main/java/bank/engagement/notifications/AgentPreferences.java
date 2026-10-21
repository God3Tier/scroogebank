package bank.engagement.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "preferences")
class AgentPreferences {

    @Id
    @Column(name = "agent_sub", length = 64)
    private String agentSub;

    @Column(name = "invites_enabled", nullable = false)
    private boolean invitesEnabled;

    @Column(name = "reminders_enabled", nullable = false)
    private boolean remindersEnabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentPreferences() {
    }

    AgentPreferences(String agentSub) {
        this.agentSub = agentSub;
        this.invitesEnabled = true;
        this.remindersEnabled = true;
    }

    void update(boolean invites, boolean reminders, Instant now) {
        invitesEnabled = invites;
        remindersEnabled = reminders;
        updatedAt = now;
    }

    Preferences view() {
        return new Preferences(invitesEnabled, remindersEnabled);
    }
}
