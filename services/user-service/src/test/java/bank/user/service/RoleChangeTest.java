package bank.user.service;

import bank.user.domain.Role;
import bank.user.domain.User;
import bank.user.exception.ForbiddenOperationException;
import bank.user.exception.NotFoundException;
import bank.user.identity.IdentityProviderClient;
import bank.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleChangeTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private IdentityProviderClient identityProviderClient;

    @InjectMocks
    private UserService userService;

    @Test
    void promotesAgentByAddingNewGroupBeforeRemovingOld() {
        User user = user(Role.AGENT);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        User result = userService.changeRole(user.getId(), Role.ADMIN);

        assertThat(result.getRole()).isEqualTo(Role.ADMIN);
        InOrder order = inOrder(identityProviderClient);
        order.verify(identityProviderClient).addToGroup("alice@scroogebank.com", Role.ADMIN);
        order.verify(identityProviderClient).removeFromGroup("alice@scroogebank.com", Role.AGENT);
        order.verifyNoMoreInteractions();
    }

    @Test
    void demotesAdmin() {
        User user = user(Role.ADMIN);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(userService.changeRole(user.getId(), Role.AGENT).getRole()).isEqualTo(Role.AGENT);
        InOrder order = inOrder(identityProviderClient);
        order.verify(identityProviderClient).addToGroup("alice@scroogebank.com", Role.AGENT);
        order.verify(identityProviderClient).removeFromGroup("alice@scroogebank.com", Role.ADMIN);
    }

    @Test
    void sameRoleIsANoOp() {
        User user = user(Role.AGENT);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(userService.changeRole(user.getId(), Role.AGENT).getRole()).isEqualTo(Role.AGENT);
        verifyNoInteractions(identityProviderClient);
    }

    @Test
    void rootAdminRoleCannotBeChanged() {
        User root = user(Role.ADMIN);
        root.setDeletable(false);
        when(userRepository.findById(root.getId())).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> userService.changeRole(root.getId(), Role.AGENT))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(root.getRole()).isEqualTo(Role.ADMIN);
        verifyNoInteractions(identityProviderClient);
    }

    @Test
    void unknownUserIsNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changeRole(id, Role.ADMIN))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(identityProviderClient);
    }

    private static User user(Role role) {
        return new User(UUID.randomUUID(), "Alice", "Tan", "alice@scroogebank.com", role, "sub-alice");
    }
}
