package bank.engagement.holidays;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;
import java.util.UUID;

/** A public holiday, seeded by migrations from an official list (ADR-0054 decision 8). */
@Entity
@Immutable
@Table(name = "holidays")
public class Holiday {

    @Id
    private UUID id;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean observed;

    @Column(nullable = false)
    private String source;

    @Column(name = "dataset_version", nullable = false)
    private String datasetVersion;

    protected Holiday() {
    }

    HolidayView view() {
        return new HolidayView(id, countryCode, holidayDate, name, observed);
    }
}
