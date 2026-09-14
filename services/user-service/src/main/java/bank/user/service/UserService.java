package bank.user.service;

import bank.user.domain.User;
import bank.user.dto.CreateUserRequest;
import bank.user.dto.UpdateUserRequest;
import bank.user.exception.ConflictException;
import bank.user.exception.ForbiddenOperationException;
import bank.user.exception.NotFoundException;
import bank.user.identity.IdentityProviderClient;
import bank.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final IdentityProviderClient identityProviderClient;

    public UserService(UserRepository userRepository, IdentityProviderClient identityProviderClient) {
        this.userRepository = userRepository;
        this.identityProviderClient = identityProviderClient;
    }

    @Transactional
    public User createUser(CreateUserRequest request) {
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

    @Transactional
    public User updateUser(UUID id, UpdateUserRequest request) {
        User user = getUser(id);
        identityProviderClient.updateUser(user.getIdpSub(), request.firstName(), request.lastName());
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        return user;
    }

    @Transactional
    public void disableUser(UUID id) {
        User user = getUser(id);
        if (!user.isDeletable()) {
            throw new ForbiddenOperationException("Root admin cannot be disabled");
        }
        identityProviderClient.disableUser(user.getIdpSub());
        user.setEnabled(false);
    }

    public void resetPassword(UUID id) {
        User user = getUser(id);
        identityProviderClient.resetPassword(user.getIdpSub());
    }
}
