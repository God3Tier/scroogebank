package bank.user.service;

import bank.user.domain.Role;
import bank.user.domain.User;
import bank.user.dto.CreateUserRequest;
import bank.user.exception.ForbiddenOperationException;
import bank.user.identity.IdentityProviderClient;
import bank.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RootAdminAuthorizationTest {
    @Mock UserRepository userRepository;
    @Mock IdentityProviderClient identityProviderClient;
    @InjectMocks UserService userService;

    @Test
    void rootAdminCanCreateAdmin() {
        User root = user(Role.ROOT_ADMIN, "root-sub");
        when(userRepository.findByIdpSub("root-sub")).thenReturn(Optional.of(root));
        when(userRepository.existsByEmail("new.admin@example.test")).thenReturn(false);
        when(identityProviderClient.createUser("new.admin@example.test", "New", "Admin", Role.ADMIN))
                .thenReturn("new-admin-sub");
        when(userRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        userService.createUser(request(Role.ADMIN), "root-sub");

        org.mockito.Mockito.verify(identityProviderClient).addToGroup("new.admin@example.test", Role.ADMIN);
    }

    @Test
    void rootAdminCanCreateAgent() {
        when(userRepository.findByIdpSub("root-sub"))
                .thenReturn(Optional.of(user(Role.ROOT_ADMIN, "root-sub")));
        when(userRepository.existsByEmail("new.admin@example.test")).thenReturn(false);
        when(identityProviderClient.createUser("new.admin@example.test", "New", "Admin", Role.AGENT))
                .thenReturn("new-agent-sub");
        when(userRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        userService.createUser(request(Role.AGENT), "root-sub");

        org.mockito.Mockito.verify(identityProviderClient).addToGroup("new.admin@example.test", Role.AGENT);
    }

    @Test
    void regularAdminCannotCreateAdmin() {
        when(userRepository.findByIdpSub("admin-sub"))
                .thenReturn(Optional.of(user(Role.ADMIN, "admin-sub")));

        assertThatThrownBy(() -> userService.createUser(request(Role.ADMIN), "admin-sub"))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(identityProviderClient);
    }

    @Test
    void regularAdminCanCreateAgent() {
        when(userRepository.findByIdpSub("admin-sub"))
                .thenReturn(Optional.of(user(Role.ADMIN, "admin-sub")));
        when(userRepository.existsByEmail("new.admin@example.test")).thenReturn(false);
        when(identityProviderClient.createUser("new.admin@example.test", "New", "Admin", Role.AGENT))
                .thenReturn("new-agent-sub");
        when(userRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        userService.createUser(request(Role.AGENT), "admin-sub");

        org.mockito.Mockito.verify(identityProviderClient).addToGroup("new.admin@example.test", Role.AGENT);
    }

    @Test
    void cannotCreateRootAdmin() {
        when(userRepository.findByIdpSub("root-sub"))
                .thenReturn(Optional.of(user(Role.ROOT_ADMIN, "root-sub")));

        assertThatThrownBy(() -> userService.createUser(request(Role.ROOT_ADMIN), "root-sub"))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(identityProviderClient);
    }

    @Test
    void cannotDisableRootAdmin() {
        User root = user(Role.ROOT_ADMIN, "root-sub");
        when(userRepository.findById(root.getId())).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> userService.disableUser(root.getId()))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(identityProviderClient);
    }

    private static CreateUserRequest request(Role role) {
        return new CreateUserRequest("New", "Admin", "new.admin@example.test", role);
    }

    private static User user(Role role, String sub) {
        return new User(UUID.randomUUID(), "Example", "User", "example@example.test", role, sub);
    }
}
