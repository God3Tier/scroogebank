package bank.alert.ingestion;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCoreSearchProviderTest {

    @Test
    void mapsOnlyPublicBoundedFieldsIntoTheGatewayPayload() {
        AtomicReference<AgentCoreSearchPayload> captured = new AtomicReference<>();
        AgentCoreSearchProvider provider = new AgentCoreSearchProvider(payload -> {
            captured.set(payload);
            return new AgentCoreSearchResponse(List.of());
        }, List.of("mas.gov.sg", "sgx.com"));

        provider.search(new PublicSearchRequest("SGX earnings", List.of("sgx.com"),
                Instant.parse("2026-09-27T00:00:00Z"), Instant.parse("2026-09-27T04:00:00Z"), 10));

        assertThat(captured.get().toolVersion()).isEqualTo("1.2.0");
        assertThat(captured.get().domains()).containsExactly("sgx.com");
        assertThat(captured.get().maximumResults()).isEqualTo(10);
        assertThat(captured.get().toString()).doesNotContain("client", "account", "holding");
    }

    @Test
    void cannotExpandTheAdministratorDomainAllowlist() {
        AgentCoreSearchProvider provider = new AgentCoreSearchProvider(
                payload -> new AgentCoreSearchResponse(List.of()), List.of("sgx.com"));

        assertThatThrownBy(() -> provider.search(new PublicSearchRequest("public terms",
                List.of("publisher.invalid"), Instant.parse("2026-09-27T00:00:00Z"),
                Instant.parse("2026-09-27T04:00:00Z"), 10)))
                .isInstanceOf(SearchProviderException.class)
                .satisfies(error -> assertThat(((SearchProviderException) error).retryable()).isFalse());
    }
}
