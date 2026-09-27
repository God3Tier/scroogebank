package bank.alert.service;

import bank.alert.api.AlertPageResponse;
import bank.alert.api.AlertSummaryResponse;
import bank.alert.domain.AlertStatus;
import bank.alert.repository.AlertListRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class AlertQueryService {

    private final AlertListRepository repository;

    public AlertQueryService(AlertListRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AlertPageResponse listAlerts(
            String actorSubject,
            boolean admin,
            AlertStatus status,
            BigDecimal minimumScore,
            String encodedCursor,
            int limit) {
        Cursor cursor = decodeCursor(encodedCursor);
        List<AlertSummaryResponse> rows = new ArrayList<>(repository.findVisible(
                actorSubject, admin, status, minimumScore,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id(),
                limit + 1));

        String nextCursor = null;
        if (rows.size() > limit) {
            rows.remove(rows.size() - 1);
            AlertSummaryResponse last = rows.get(rows.size() - 1);
            nextCursor = encodeCursor(last.createdAt(), last.id());
        }
        return new AlertPageResponse(rows, nextCursor);
    }

    static String encodeCursor(Instant createdAt, UUID id) {
        String value = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid alert cursor");
            }
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid alert cursor", exception);
        }
    }

    record Cursor(Instant createdAt, UUID id) {
    }
}
