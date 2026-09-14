package bank.user.config;

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
 * This runs once per task, and the service runs two of them (desired_count = 2
 * in the ecs-service module), so on a fresh database both replicas reach the
 * existsByEmail check before either has written anything and both proceed.
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
    private final String rootAdminEmail;

    public RootAdminInitializer(
            UserRepository userRepository,
            IdentityProviderClient identityProviderClient,
            @Value("${root-admin.email}") String rootAdminEmail
    ) {
        this.userRepository = userRepository;
        this.identityProviderClient = identityProviderClient;
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
        } catch (DataIntegrityViolationException e) {
            // The other replica committed the same root admin first. The
            // desired end state is reached either way, so this is not a
            // failure -- and throwing here would take the whole task down.
        }
    }
}
