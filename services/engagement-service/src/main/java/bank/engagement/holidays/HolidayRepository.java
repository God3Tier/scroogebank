package bank.engagement.holidays;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    List<Holiday> findByCountryCodeAndHolidayDateBetweenOrderByHolidayDateAscNameAsc(
            String countryCode, LocalDate from, LocalDate to);
}
