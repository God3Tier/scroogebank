package bank.alert.api;

import bank.alert.service.SearchScheduleService;
import bank.alert.service.SearchScheduleService.Schedule;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** When the next automatic search is (ADR-0081). */
@RestController
@RequestMapping("/api/alerts/search-schedule")
public class SearchScheduleController {

    private final SearchScheduleService schedule;

    public SearchScheduleController(SearchScheduleService schedule) {
        this.schedule = schedule;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    public Schedule current() {
        return schedule.current();
    }
}
