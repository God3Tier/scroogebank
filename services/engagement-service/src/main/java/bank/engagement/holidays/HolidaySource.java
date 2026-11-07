package bank.engagement.holidays;

import java.util.List;

/** An official list of one country's public holidays (ADR-0057). */
interface HolidaySource {

    String countryCode();

    /** Where the list comes from, stored with each row it adds. */
    String sourceUrl();

    /** Every holiday the source lists; throws {@link HolidaySourceException} when it cannot answer. */
    List<OfficialHoliday> fetch();
}
