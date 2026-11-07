package bank.engagement.drafting;

import bank.engagement.security.CurrentUser;
import bank.engagement.web.ApiException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/engagement/drafts")
@RequiredArgsConstructor
public class DraftController {

    private final DraftService drafts;
    private final NewsDraftService news;
    private final CurrentUser currentUser;

    @GetMapping
    public List<DraftView> list(
            @RequestParam(required = false) DraftStatus status,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(defaultValue = "50") int limit) {
        if (status == DraftStatus.SENDING) {
            throw ApiException.badRequest("'status' must be DRAFT, SENT or DISCARDED");
        }
        return drafts.list(currentUser.subject(), status, clientId, limit);
    }

    @PostMapping
    public ResponseEntity<DraftView> create(@Valid @RequestBody DraftInput input) {
        DraftView created = drafts.create(currentUser.subject(), input);
        return ResponseEntity.created(URI.create("/api/engagement/drafts/" + created.id())).body(created);
    }

    @GetMapping("/{draftId}")
    public DraftView get(@PathVariable UUID draftId) {
        return drafts.get(currentUser.subject(), draftId);
    }

    @PutMapping("/{draftId}")
    public DraftView update(@PathVariable UUID draftId, @Valid @RequestBody DraftUpdate input) {
        return drafts.update(currentUser.subject(), draftId, input);
    }

    @DeleteMapping("/{draftId}")
    public ResponseEntity<Void> discard(@PathVariable UUID draftId) {
        drafts.discard(currentUser.subject(), draftId);
        return ResponseEntity.noContent().build();
    }

    public record NewsRequest(@jakarta.validation.constraints.NotNull UUID eventId,
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(max = 200)
            List<@jakarta.validation.constraints.NotNull UUID> alertIds) {
    }

    public record OpenAlerts(List<UUID> alertIds) {
    }

    /** "Notify selected clients of news": the approved report goes on each chosen client's News Alert draft. */
    @PostMapping("/news")
    public NewsDraftService.CreateResult createNews(@Valid @RequestBody NewsRequest request) {
        return news.create(currentUser.subject(), request.eventId(), request.alertIds());
    }

    /** The alerts on the agent's open News Alert drafts, so Event Alerts can stop them being dismissed there. */
    @GetMapping("/news/open-alerts")
    public OpenAlerts openAlerts() {
        return new OpenAlerts(news.openAlerts(currentUser.subject()));
    }

    @DeleteMapping("/{draftId}/reports/{alertId}")
    public DraftView removeReport(@PathVariable UUID draftId, @PathVariable UUID alertId) {
        news.removeReport(currentUser.subject(), draftId, alertId);
        return drafts.news(currentUser.subject(), draftId);
    }

    /** Builds the summary again from the reports, or with {@code keep=true} keeps the agent's text. No model call. */
    @PostMapping("/{draftId}/summary/rebuild")
    public DraftView rebuildSummary(@PathVariable UUID draftId, @RequestParam(defaultValue = "false") boolean keep) {
        news.rebuild(currentUser.subject(), draftId, keep);
        return drafts.news(currentUser.subject(), draftId);
    }

    /** "Preview PDF": exactly the file the client would get. */
    @GetMapping(value = "/{draftId}/pdf", produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> previewPdf(@PathVariable UUID draftId) {
        var pdf = news.preview(currentUser.subject(), draftId);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.inline().filename(pdf.fileName()).build().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(pdf.bytes());
    }

    /** "Retry status update": moves the clients of a sent News Alert draft to Sent Out. */
    @PostMapping("/{draftId}/news-status")
    public DraftView retryNewsStatus(@PathVariable UUID draftId) {
        news.retrySentOut(currentUser.subject(), draftId);
        return drafts.news(currentUser.subject(), draftId);
    }

    @PostMapping("/{draftId}/send")
    public DraftView send(@PathVariable UUID draftId, @Valid @RequestBody SendRequest request) {
        return drafts.send(currentUser.subject(), draftId, request.version());
    }
}
