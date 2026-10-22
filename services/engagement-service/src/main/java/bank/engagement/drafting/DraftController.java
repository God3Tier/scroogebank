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

    @PostMapping("/{draftId}/send")
    public DraftView send(@PathVariable UUID draftId, @Valid @RequestBody SendRequest request) {
        return drafts.send(currentUser.subject(), draftId, request.version());
    }
}
