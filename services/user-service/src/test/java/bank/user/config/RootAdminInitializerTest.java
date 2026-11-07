package bank.user.config;

import bank.user.audit.AuditEventPublisher;
import bank.user.domain.Role;
import bank.user.domain.User;
import bank.user.identity.IdentityProviderClient;
import bank.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RootAdminInitializerTest {
    @Test
    void createsApplicationRootWithCognitoAdminMembership() {
        UserRepository repository = mock(UserRepository.class);
        IdentityProviderClient idp = mock(IdentityProviderClient.class);
        AuditEventPublisher audit = mock(AuditEventPublisher.class);
        when(repository.existsByEmail("root@example.test")).thenReturn(false);
        when(idp.createUser("root@example.test", "Root", "Admin", Role.ADMIN)).thenReturn("root-sub");

        new RootAdminInitializer(repository, idp, audit, "root@example.test").run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ROOT_ADMIN);
        assertThat(saved.getValue().isDeletable()).isFalse();
        verify(idp).addToGroup("root@example.test", Role.ADMIN);
        verify(audit).publish("CREATE", "system:root-admin-initializer", "USER",
                saved.getValue().getId().toString(), "/internal/root-admin", "SYSTEM", 201, "ROOT_ADMIN");
    }
}
