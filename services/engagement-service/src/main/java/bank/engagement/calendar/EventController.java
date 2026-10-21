package bank.engagement.calendar;

import bank.engagement.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/engagement/events")
public class EventController {

    private final EventService events;
    private final CurrentUser currentUser;

    public EventController(EventService events, CurrentUser currentUser) {
        this.events = events;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<EventView> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "false") boolean includeCancelled) {
        return events.list(currentUser.subject(), from.toInstant(), to.toInstant(), includeCancelled);
    }

    @PostMapping
    public ResponseEntity<EventView> create(@Valid @RequestBody EventInput input) {
        EventView created = events.create(currentUser.subject(), input);
        return ResponseEntity.created(URI.create("/api/engagement/events/" + created.id())).body(created);
    }

    @GetMapping("/{eventId}")
    public EventView get(@PathVariable UUID eventId) {
        return events.get(currentUser.subject(), eventId);
    }

    @PutMapping("/{eventId}")
    public EventView update(@PathVariable UUID eventId, @Valid @RequestBody EventUpdate input) {
        return events.update(currentUser.subject(), eventId, input);
    }

    @DeleteMapping("/{eventId}")
    public ResponseEntity<Void> cancel(@PathVariable UUID eventId) {
        events.cancel(currentUser.subject(), eventId);
        return ResponseEntity.noContent().build();
    }
}
