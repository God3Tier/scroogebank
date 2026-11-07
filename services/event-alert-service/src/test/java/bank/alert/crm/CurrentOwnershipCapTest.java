package bank.alert.crm;

import bank.alert.repository.AuditOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Plan section 5 / acceptance 8: more than 1,000 referenced clients fail explicitly, never truncate. */
class CurrentOwnershipCapTest {

    @Test
    void moreThanTheBoundedPopulationFailsInsteadOfUsingATruncatedAuthorisationSet() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CrmDirectory> provider = mock(ObjectProvider.class);
        CrmDirectory directory = mock(CrmDirectory.class);
        when(provider.getIfAvailable()).thenReturn(directory);
        List<String> refs = new ArrayList<>();
        for (int i = 0; i <= CurrentOwnership.MAX_REFERENCED_CLIENTS; i++) refs.add(UUID.randomUUID().toString());
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(refs);
        CurrentOwnership ownership = new CurrentOwnership(jdbc, provider,
                Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneOffset.UTC),
                mock(AuditOutboxRepository.class), new ObjectMapper(), 100);

        assertThatThrownBy(ownership::refresh).isInstanceOf(DirectoryUnavailableException.class);

        verify(directory, never()).accounts(org.mockito.ArgumentMatchers.any());
    }
}
