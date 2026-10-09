package bank.user.service;

import bank.user.domain.Role;
import bank.user.domain.User;
import bank.user.exception.NotFoundException;
import bank.user.identity.IdentityProviderClient;
import bank.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserLookupBySubTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private IdentityProviderClient identityProviderClient;

    @InjectMocks
    private UserService userService;

    @Test
    void findsTheUserForASub() {
        User user = new User(UUID.randomUUID(), "Alice", "Tan", "alice@scroogebank.com", Role.AGENT, "sub-alice");
        when(userRepository.findByIdpSub("sub-alice")).thenReturn(Optional.of(user));

        assertThat(userService.getUserByIdpSub("sub-alice")).isSameAs(user);
    }

    @Test
    void unknownSubIsNotFound() {
        when(userRepository.findByIdpSub("sub-unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserByIdpSub("sub-unknown"))
                .isInstanceOf(NotFoundException.class);
    }
}
