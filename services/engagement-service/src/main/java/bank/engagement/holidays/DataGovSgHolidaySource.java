package bank.engagement.holidays;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MOM's Singapore public holidays from data.gov.sg's open {@code datastore_search}
 * API (ADR-0057), read a page at a time. Each record has {@code date}
 * ({@code YYYY-MM-DD}) and {@code holiday}; observed days are separate rows
 * named "… (Observed)". Nothing is sent to data.gov.sg but the dataset ID.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
class DataGovSgHolidaySource implements HolidaySource {

    static final int PAGE_SIZE = 100;
    /** About 14 rows a year; far more means the dataset is not what it should be. */
    static final int MAX_ROWS = 2_000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HolidaySyncProperties properties;

    @Override
    public String countryCode() {
        return "SG";
    }

    @Override
    public String sourceUrl() {
        return "https://data.gov.sg/datasets/" + properties.datasetId() + "/view";
    }

    @Override
    public List<OfficialHoliday> fetch() {
        List<OfficialHoliday> holidays = new ArrayList<>();
        int skipped = 0;
        int offset = 0;
        int total;
        do {
            JsonNode result = page(offset);
            total = result.path("total").asInt(-1);
            JsonNode records = result.path("records");
            if (total < 0 || total > MAX_ROWS || !records.isArray()) {
                throw new HolidaySourceException("Unexpected data.gov.sg response: total " + total);
            }
            for (JsonNode record : records) {
                var parsed = OfficialHoliday.parse(record.path("date").asText(null), record.path("holiday").asText(null));
                if (parsed.isPresent()) {
                    holidays.add(parsed.get());
                } else {
                    skipped++;
                }
            }
            if (records.isEmpty()) {
                break;
            }
            offset += records.size();
        } while (offset < total);
        if (skipped > 0) {
            log.warn("holiday_sync skipped {} unreadable rows from data.gov.sg", skipped);
        }
        return holidays;
    }

    private JsonNode page(int offset) {
        String body;
        try {
            body = restClient.get()
                    .uri(properties.baseUrl() + "/api/action/datastore_search?resource_id={id}&limit={limit}&offset={offset}",
                            properties.datasetId(), PAGE_SIZE, offset)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            throw new HolidaySourceException("data.gov.sg could not be read: " + ex.getClass().getSimpleName(), ex);
        }
        try {
            JsonNode root = objectMapper.readTree(body == null ? "" : body);
            if (root == null || !root.path("success").asBoolean(false)) {
                throw new HolidaySourceException("data.gov.sg did not report success");
            }
            return root.path("result");
        } catch (IOException ex) {
            throw new HolidaySourceException("data.gov.sg returned something that is not JSON", ex);
        }
    }
}
