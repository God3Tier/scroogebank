package bank.user.activity;

import java.util.List;

/**
 * One page of activity, newest first. Pass nextCursor as the cursor
 * parameter to get the next page; it is null on the last page.
 */
public record ActivityPage(
        List<ActivityResponse> content,
        Long nextCursor
) {
}
