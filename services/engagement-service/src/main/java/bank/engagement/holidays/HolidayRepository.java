package bank.engagement.holidays;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    List<Holiday> findByCountryCodeAndHolidayDateBetweenOrderByHolidayDateAscNameAsc(
            String countryCode, LocalDate from, LocalDate to);

    @Query("SELECT max(h.holidayDate) FROM Holiday h WHERE h.countryCode = :countryCode")
    Optional<LocalDate> findLastDate(String countryCode);
}
