package bank.alert.fixture;

import bank.alert.domain.HoldingSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Component
public class SyntheticFixtureLoader {

    private static final String DEFAULT_FIXTURE = "fixtures/holdings-snapshot.v1.json";

    private final ObjectMapper objectMapper;

    public SyntheticFixtureLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SyntheticHoldingFixture loadDefault() {
        try (InputStream input = new ClassPathResource(DEFAULT_FIXTURE).getInputStream()) {
            SyntheticHoldingFixture fixture = objectMapper.readValue(input, SyntheticHoldingFixture.class);
            if (fixture.contractVersion() != 1) {
                throw new IllegalStateException("unsupported holdings fixture contract version");
            }
            return fixture;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load synthetic holdings fixture", exception);
        }
    }

    public List<HoldingSnapshot> toSnapshots(SyntheticHoldingFixture fixture) {
        return fixture.positions().stream()
                .map(position -> new HoldingSnapshot(
                        position.snapshotId(),
                        position.clientRef(),
                        position.accountRef(),
                        position.assignedAgentSubject(),
                        position.instrumentId(),
                        position.quantity(),
                        position.unitPrice(),
                        position.valuationCurrency(),
                        position.fxToSgd(),
                        position.valueSgd(),
                        position.valuedAt(),
                        position.freshness(),
                        position.sourceVersion()))
                .toList();
    }
}
