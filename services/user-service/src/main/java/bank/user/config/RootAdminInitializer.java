package bank.user.config;

import bank.user.audit.AuditEventPublisher;
import bank.user.domain.Role;
import bank.user.domain.User;
import bank.user.identity.IdentityProviderClient;
import bank.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Seeds the non-deletable root admin on first startup, in both the IdP and the
 * local `users` table.
 *
 * This runs once per task, and the service can run several (auto scaling goes
 * up to 6 in the ecs-service module, and a deployment overlaps old and new
 * tasks), so on a fresh database two replicas can reach the existsByEmail
 * check before either has written anything and both proceed.
 * That is fine and expected: createUser adopts an existing IdP user, and the
 * losing replica's insert is rejected by the unique constraint on email and
 * swallowed below. Whichever task gets there second simply finds the work
 * already done.
 *
 * An exception escaping a CommandLineRunner kills the application, so anything
 * not handled here becomes a crash loop rather than a failed request.
 */
@Component
public class RootAdminInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final IdentityProviderClient identityProviderClient;
    private final AuditEventPublisher auditEventPublisher;
    private final String rootAdminEmail;

    // Explicit constructor: Lombok does not copy @Value onto the generated
    // constructor parameter, so Spring found no String bean and the task crash-looped.
    public RootAdminInitializer(
            UserRepository userRepository,
            IdentityProviderClient identityProviderClient,
            AuditEventPublisher auditEventPublisher,
            @Value("${root-admin.email}") String rootAdminEmail
    ) {
        this.userRepository = userRepository;
        this.identityProviderClient = identityProviderClient;
        this.auditEventPublisher = auditEventPublisher;
        this.rootAdminEmail = rootAdminEmail;
    }

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmail(rootAdminEmail)) {
            return;
        }
        String idpSub = identityProviderClient.createUser(rootAdminEmail, "Root", "Admin", Role.ADMIN);
        // Must be the IdP username (email), not idpSub -- see addToGroup's
        // contract. AdminAddUserToGroup is idempotent, so repeating it is safe.
        identityProviderClient.addToGroup(rootAdminEmail, Role.ADMIN);

        User rootAdmin = new User(UUID.randomUUID(), "Root", "Admin", rootAdminEmail, Role.ADMIN, idpSub);
        rootAdmin.setDeletable(false);
        try {
            userRepository.save(rootAdmin);
            auditEventPublisher.publish("CREATE", "system:root-admin-initializer", "USER",
                    rootAdmin.getId().toString(), "/internal/root-admin", "SYSTEM", 201, Role.ADMIN.name());
        } catch (DataIntegrityViolationException e) {
            // The other replica committed the same root admin first. The
            // desired end state is reached either way, so this is not a
            // failure -- and throwing here would take the whole task down.
        }
    }
}
