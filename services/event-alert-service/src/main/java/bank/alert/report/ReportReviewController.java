package bank.alert.report;

import bank.alert.report.ReportReviewService.ApprovalView;
import bank.alert.report.ReportReviewService.ApprovedItem;
import bank.alert.report.ReportReviewService.CheckItem;
import bank.alert.report.ReportReviewService.PackageItem;
import bank.alert.report.ReportReviewService.PdfBundle;
import bank.alert.report.ReportReviewService.ReviewView;
import bank.alert.report.ReportReviewService.SentOutItem;
import bank.alert.report.ReportReviewService.SentOutRow;
import bank.alert.report.ReportReviewService.SinglePdf;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Reviewing reports for news emails (news-alert-drafts plan, sections 5.2 to 5.8 and 7.1). The Event Alerts page
 * edits and approves; engagement-service, with the agent's own token, builds and sends the email from the
 * approvals. Nothing here sends anything.
 */
@RestController
@RequestMapping("/api/alerts")
@Validated
public class ReportReviewController {

    public record EditRequest(@Min(1) int expectedReportVersion, @NotNull JsonNode sections) {
    }

    public record ApprovalRequest(@Min(1) int expectedReportVersion) {
    }

    public record PackageRequest(@NotNull UUID eventId, @NotEmpty @Size(max = 200) List<@NotNull UUID> alertIds) {
    }

    public record PdfRequest(
            @NotEmpty @Size(max = ReportReviewService.MAX_DOCUMENT_REPORTS) List<@NotNull UUID> approvalIds,
            @Size(max = 100) String preparedBy) {
    }

    public record AlertIdsRequest(@NotEmpty @Size(max = 200) List<@NotNull UUID> alertIds) {
    }

    public record SentOutRequest(@NotEmpty @Size(max = 200) List<@NotNull UUID> alertIds, @NotNull UUID draftId,
            @NotBlank @Size(max = 200) String sesMessageId) {
    }

    private final ReportReviewService reviews;

    public ReportReviewController(ReportReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/events/{eventId}/report/review")
    @PreAuthorize("hasRole('AGENT')")
    public ReviewView review(@PathVariable UUID eventId, Authentication authentication) {
        return reviews.review(eventId, authentication.getName());
    }

    @PutMapping("/events/{eventId}/report/edit")
    @PreAuthorize("hasRole('AGENT')")
    public ReviewView saveEdit(@PathVariable UUID eventId, @Valid @RequestBody EditRequest request,
            Authentication authentication) {
        return reviews.saveEdit(eventId, authentication.getName(), request.expectedReportVersion(), request.sections());
    }

    @DeleteMapping("/events/{eventId}/report/edit")
    @PreAuthorize("hasRole('AGENT')")
    public ReviewView discardEdit(@PathVariable UUID eventId, Authentication authentication) {
        return reviews.discardEdit(eventId, authentication.getName());
    }

    @PostMapping("/events/{eventId}/report/approval")
    @PreAuthorize("hasRole('AGENT')")
    public ApprovalView approve(@PathVariable UUID eventId, @Valid @RequestBody ApprovalRequest request,
            Authentication authentication) {
        return reviews.approve(eventId, authentication.getName(), request.expectedReportVersion());
    }

    @GetMapping(value = "/events/{eventId}/report/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    public ResponseEntity<byte[]> reportPdf(@PathVariable UUID eventId,
            @RequestParam(required = false) @Min(1) Integer version, Authentication authentication) {
        SinglePdf pdf = reviews.singlePdf(eventId, version, authentication.getName(), admin(authentication));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pdf.fileName()).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.pdf());
    }

    @GetMapping("/approved-reports")
    @PreAuthorize("hasRole('AGENT')")
    public List<ApprovedItem> approved(@RequestParam(required = false) @Size(max = 64) String clientRef,
            Authentication authentication) {
        return reviews.approved(authentication.getName(), clientRef);
    }

    @PostMapping("/approved-reports/package")
    @PreAuthorize("hasRole('AGENT')")
    public List<PackageItem> packageFor(@Valid @RequestBody PackageRequest request, Authentication authentication) {
        return reviews.packageFor(authentication.getName(), request.eventId(), request.alertIds());
    }

    @PostMapping("/approved-reports/pdf")
    @PreAuthorize("hasRole('AGENT')")
    public PdfBundle combinedPdf(@Valid @RequestBody PdfRequest request, Authentication authentication) {
        return reviews.combinedPdf(authentication.getName(), request.approvalIds(), request.preparedBy());
    }

    @PostMapping("/send-check")
    @PreAuthorize("hasRole('AGENT')")
    public List<CheckItem> sendCheck(@Valid @RequestBody AlertIdsRequest request, Authentication authentication) {
        return reviews.sendCheck(authentication.getName(), request.alertIds());
    }

    @PostMapping("/sent-out")
    @PreAuthorize("hasRole('AGENT')")
    public List<SentOutItem> sentOut(@Valid @RequestBody SentOutRequest request, Authentication authentication) {
        return reviews.sentOut(authentication.getName(), request.alertIds(), request.draftId(), request.sesMessageId());
    }

    @GetMapping("/sent-out")
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    public List<SentOutRow> sentOutList(@RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit,
            Authentication authentication) {
        return reviews.sentOutList(authentication.getName(), admin(authentication), limit);
    }

    private static boolean admin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
