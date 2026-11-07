package bank.user.service;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bank.user.domain.Role;
import bank.user.domain.User;
import bank.user.dto.CreateUserRequest;
import bank.user.dto.UpdateUserRequest;
import bank.user.exception.ConflictException;
import bank.user.exception.ForbiddenOperationException;
import bank.user.exception.NotFoundException;
import bank.user.identity.IdentityProviderClient;
import bank.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final IdentityProviderClient identityProviderClient;

    @Transactional
    public User createUser(CreateUserRequest request, String callerSub) {
        User caller = getUserByIdpSub(callerSub);
        if (request.role() == Role.ROOT_ADMIN) {
            throw new ForbiddenOperationException("Root Admin status cannot be granted through user management");
        }
        if (!caller.isEnabled()) {
            throw new ForbiddenOperationException("Disabled users cannot create accounts");
        }
        if (request.role() == Role.ADMIN && caller.getRole() != Role.ROOT_ADMIN) {
            throw new ForbiddenOperationException("Only the Root Admin can create Admin accounts");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email already registered: " + request.email());
        }

        String idpSub = identityProviderClient.createUser(
                request.email(), request.firstName(), request.lastName(), request.role());
        // Must be the IdP username (email), not idpSub -- see addToGroup's contract.
        identityProviderClient.addToGroup(request.email(), request.role());

        User user = new User(UUID.randomUUID(), request.firstName(), request.lastName(),
                request.email(), request.role(), idpSub);
        try {
            // saveAndFlush, not save: this method is @Transactional and User
            // has an assigned @Id, so nothing forces the INSERT until the
            // transaction commits -- which happens after this method returns,
            // where the catch below could not see the violation. Flushing
            // here moves it inside the try.
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // The existsByEmail check above is not a lock, so a concurrent
            // request for the same email can get this far. Surface it as the
            // 409 the caller would have got had it lost that check, not a 500.
            throw new ConflictException("Email already registered: " + request.email());
        }
    }

    public User getUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
    }

    /** The CRM user behind a verified token's {@code sub}. */
    public User getUserByIdpSub(String idpSub) {
        return userRepository.findByIdpSub(idpSub)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    // A null filter means "any". See UserRepository.search.
    public Page<User> listUsers(Role role, Boolean enabled, int page, int size) {
        return userRepository.search(role, enabled, page, size);
    }

    @Transactional
    public User updateUser(UUID id, UpdateUserRequest request) {
        User user = getUser(id);
        identityProviderClient.updateUser(user.getIdpSub(), request.firstName(), request.lastName());
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        return user;
    }

    /**
     * Moves a user to another role. Cognito group membership is the role
     * that tokens carry, so it is changed first and the local column follows.
     *
     * The new group is added before the old one is removed. If the second
     * call fails, the user is briefly in both groups, which grants no more
     * than the admin asked for; removing first could instead leave the user
     * in no group and unable to use the system. Both calls are idempotent,
     * so repeating the request completes a change that stopped half-way.
     */
    @Transactional
    public User changeRole(UUID id, Role newRole) {
        User user = getUser(id);
        if (user.getRole() == Role.ROOT_ADMIN || !user.isDeletable()) {
            throw new ForbiddenOperationException("Root admin's role cannot be changed");
        }
        if (newRole == Role.ADMIN || newRole == Role.ROOT_ADMIN) {
            throw new ForbiddenOperationException("Role changes cannot grant Admin or Root Admin status");
        }
        Role oldRole = user.getRole();
        if (oldRole == newRole) {
            return user;
        }
        // Must be the IdP username (email), not idpSub -- see addToGroup's contract.
        identityProviderClient.addToGroup(user.getEmail(), newRole);
        identityProviderClient.removeFromGroup(user.getEmail(), oldRole);
        user.setRole(newRole);
        return user;
    }

    @Transactional
    public void disableUser(UUID id) {
        User user = getUser(id);
        if (user.getRole() == Role.ROOT_ADMIN || !user.isDeletable()) {
            throw new ForbiddenOperationException("Root admin cannot be disabled");
        }
        identityProviderClient.disableUser(user.getIdpSub());
        user.setEnabled(false);
    }

    /** Resolves an admin's application role from the verified token subject. */
    public User getCurrentAdmin(String callerSub) {
        User caller = getUserByIdpSub(callerSub);
        if (!caller.isEnabled() || (caller.getRole() != Role.ADMIN && caller.getRole() != Role.ROOT_ADMIN)) {
            throw new ForbiddenOperationException("Admin access required");
        }
        return caller;
    }

    public void resetPassword(UUID id) {
        User user = getUser(id);
        identityProviderClient.resetPassword(user.getIdpSub());
    }
}
