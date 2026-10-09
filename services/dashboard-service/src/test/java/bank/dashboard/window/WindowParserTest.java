package bank.dashboard.window;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.exception.InvalidWindowException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowParserTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-20T10:15:00Z"), ZoneOffset.UTC);

    private final WindowParser parser = new WindowParser(
            new DashboardProperties(
                    new DashboardProperties.Window(30, 365),
                    new DashboardProperties.Cache(60, 1000),
                    new DashboardProperties.Upstream(2000, 3000, "", "", "")),
            FIXED);

    @Test
    void defaultsToTheLast30DaysInclusive() {
        DashboardWindow window = parser.parse(null, null);

        assertThat(window.to()).isEqualTo(LocalDate.of(2026, 9, 20));
        // 30 days counting both endpoints, not 31. An off-by-one here would
        // quietly shift every windowed figure the dashboard reports.
        assertThat(window.from()).isEqualTo(LocalDate.of(2026, 8, 22));
        assertThat(window.days()).isEqualTo(30);
    }

    @Test
    void todayIsTheSingaporeDate() {
        // 20:00 UTC on 19 September is already 20 September in Singapore,
        // which is how every service counts days.
        Clock lateEvening = Clock.fixed(Instant.parse("2026-09-19T20:00:00Z"), ZoneOffset.UTC);
        WindowParser singapore = new WindowParser(new DashboardProperties(
                new DashboardProperties.Window(30, 365),
                new DashboardProperties.Cache(60, 1000),
                new DashboardProperties.Upstream(2000, 3000, "", "", "")), lateEvening);

        assertThat(singapore.parse(null, null).to()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void honoursBothEndpointsWhenGiven() {
        DashboardWindow window = parser.parse("2026-01-01", "2026-01-31");

        assertThat(window.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(window.to()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(window.days()).isEqualTo(31);
    }

    @Test
    void treatsASingleDayAsAWindowOfOne() {
        assertThat(parser.parse("2026-05-05", "2026-05-05").days()).isEqualTo(1);
    }

    @Test
    void defaultsFromRelativeToAnExplicitTo() {
        DashboardWindow window = parser.parse(null, "2026-03-31");

        assertThat(window.from()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(window.to()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void rejectsAnInvertedWindow() {
        assertThatThrownBy(() -> parser.parse("2026-09-20", "2026-01-01"))
                .isInstanceOf(InvalidWindowException.class)
                .hasMessageContaining("must not be after");
    }

    @Test
    void rejectsAnUnparseableDate() {
        assertThatThrownBy(() -> parser.parse("last-tuesday", null))
                .isInstanceOf(InvalidWindowException.class)
                .hasMessageContaining("'from'");
    }

    @Test
    void rejectsAWindowLongerThanTheMaximum() {
        // The guard exists so one request cannot ask an upstream to aggregate
        // an unbounded range.
        assertThatThrownBy(() -> parser.parse("2020-01-01", "2026-09-20"))
                .isInstanceOf(InvalidWindowException.class)
                .hasMessageContaining("365");
    }

    @Test
    void acceptsAWindowExactlyAtTheMaximum() {
        assertThat(parser.parse("2026-01-01", "2026-12-31").days()).isEqualTo(365);
    }
}
