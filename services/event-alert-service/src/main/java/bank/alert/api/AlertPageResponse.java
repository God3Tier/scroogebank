package bank.alert.api;

import java.util.List;

public record AlertPageResponse(List<AlertSummaryResponse> items, String nextCursor) {

    public AlertPageResponse {
        items = List.copyOf(items);
    }
}
