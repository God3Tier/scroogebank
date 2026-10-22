package bank.engagement.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

interface PreferencesRepository extends JpaRepository<AgentPreferences, String> {
}
