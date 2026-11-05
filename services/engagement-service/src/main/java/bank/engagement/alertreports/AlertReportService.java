package bank.engagement.alertreports;

import bank.engagement.alertreports.AlertReportModels.BatchResult;
import bank.engagement.alertreports.AlertReportModels.DeliveryRequest;
import bank.engagement.alertreports.AlertReportStore.Batch;
import bank.engagement.alertreports.AlertReportStore.Claimed;
import bank.engagement.audit.AuditAction;
import bank.engagement.audit.AuditTrail;
import bank.engagement.integration.clients.ClientDirectory;
import bank.engagement.integration.clients.ClientNotFoundException;
import bank.engagement.integration.clients.ClientRecord;
import bank.engagement.integration.clients.ClientServiceUnavailableException;
import bank.engagement.integration.identity.AgentDirectory;
import bank.engagement.integration.identity.AgentProfile;
import bank.engagement.integration.sources.ReportPackages;
import bank.engagement.integration.sources.ReportPackages.Package;
import bank.engagement.integration.sources.ReportPackages.Recipient;
import bank.engagement.integration.sources.ReportPackages.Source;
import bank.engagement.integration.sources.SourceNotFoundException;
import bank.engagement.integration.sources.SourceUnavailableException;
import bank.engagement.integration.sources.StaleReportVersionException;
import bank.engagement.notifications.ClientMailSender;
import bank.engagement.notifications.MailProperties;
import bank.engagement.notifications.MailSendException;
import bank.engagement.notifications.MailUnavailableException;
import bank.engagement.notifications.OutgoingMail;
import bank.engagement.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.HexFormat;

/**
 * Emails an event report to the clients an agent chose, after the agent approved it (ADR-0084).
 *
 * <p>One request is one approval. It stores an immutable snapshot of the report and one delivery row per client,
 * then sends each client an individually addressed message. The service has no way to read a client address
 * except with the agent's own token, so the address is read for each message while the agent's request is open and
 * is never stored. Recipients not reached within the request's time budget, or after a failure that may pass,
 * stay QUEUED; sending the same request again (same idempotency key) carries on with those and never repeats one
 * already sent. A report version goes to a client at most once.
 */
@Service
public class AlertReportService {

    private static final Logger log = LoggerFactory.getLogger(AlertReportService.class);
    private static final Duration LEASE = Duration.ofMinutes(2);

    /** What the batch keeps of the report: exactly what the agent approved, nothing added. */
    record Snapshot(JsonNode report, List<Source> sources) {
    }

    private final AlertReportProperties properties;
    private final AlertReportStore store;
    private final ReportPackages packages;
    private final ClientDirectory clients;
    private final AgentDirectory agents;
    private final ClientMailSender sender;
    private final MailProperties mail;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AlertReportService(AlertReportProperties properties, AlertReportStore store, ReportPackages packages,
            ClientDirectory clients, AgentDirectory agents, ClientMailSender sender, MailProperties mail,
            AuditTrail audit, TransactionTemplate transactions, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.packages = packages;
        this.clients = clients;
        this.agents = agents;
        this.sender = sender;
        this.mail = mail;
        this.audit = audit;
        this.transactions = transactions;
        this.mapper = mapper;
        this.clock = clock;
    }

    public BatchResult deliver(String owner, String idempotencyKey, DeliveryRequest request) {
        if (!properties.enabled()) {
            throw ApiException.unavailable("Sending alert reports is switched off");
        }
        String requestHash = requestHash(request);
        Optional<Batch> existing = store.findBatch(owner, idempotencyKey);
        Batch batch;
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(requestHash)) {
                throw ApiException.conflict("This Idempotency-Key was already used for a different request");
            }
            batch = existing.get();
        } else {
            agent(owner);
            Package report = fetch(request);
            try {
                batch = create(owner, idempotencyKey, requestHash, request, report);
            } catch (DuplicateKeyException race) {
                batch = store.findBatch(owner, idempotencyKey).orElseThrow(
                        () -> ApiException.conflict("Another request is sending these reports; try again"));
            }
            log.info("alert report batch created batch_id={} recipients={}", batch.id(), request.alertIds().size());
        }
        process(owner, batch);
        return new BatchResult(batch.id(), batch.eventId(), batch.reportVersion(), store.statuses(batch.id()));
    }

    private AgentProfile agent(String owner) {
        return agents.find(owner).filter(profile -> profile.enabled() && profile.hasEmail())
                .orElseThrow(() -> ApiException.unavailable("Your email address could not be looked up; nothing was sent"));
    }

    private Package fetch(DeliveryRequest request) {
        try {
            return packages.fetch(request.eventId(), request.expectedReportVersion(), List.copyOf(new LinkedHashSet<>(request.alertIds())));
        } catch (SourceNotFoundException ex) {
            throw ApiException.notFound("This event is not available to you");
        } catch (StaleReportVersionException ex) {
            throw ApiException.conflict("The report changed since you looked; reload and review it again");
        } catch (SourceUnavailableException ex) {
            throw ApiException.unavailable("Alerts are unavailable; nothing was queued");
        }
    }

    private Batch create(String owner, String key, String requestHash, DeliveryRequest request, Package report) {
        Instant now = clock.instant();
        Snapshot snapshot = new Snapshot(report.report(), report.sources());
        String snapshotJson = json(snapshot);
        String note = request.agentNote() == null || request.agentNote().isBlank() ? null : request.agentNote().strip();
        Batch batch = new Batch(UUID.randomUUID(), requestHash, request.eventId(), report.reportId(), report.version(),
                report.reportHash(), snapshotJson, note);
        return transactions.execute(status -> {
            store.insertBatch(batch, owner, key, note == null ? null : sha256(note), now);
            Map<UUID, Recipient> byAlert = new LinkedHashMap<>();
            report.recipients().forEach(r -> byAlert.putIfAbsent(r.alertId(), r));
            int queued = 0;
            int position = 0;
            for (UUID alertId : new LinkedHashSet<>(request.alertIds())) {
                position++;
                Recipient r = byAlert.get(alertId);
                if (r == null || !r.authorised()) {
                    store.insertDelivery(position, batch.id(), batch.reportId(), alertId, null, null, null, "NOT_AUTHORISED",
                            "NOT_INFORMED_OR_NOT_YOURS", now);
                } else if (store.alreadyDelivered(batch.reportId(), alertId)) {
                    store.insertDelivery(position, batch.id(), batch.reportId(), alertId, r.clientRef(), r.instrumentId(), r.issuer(),
                            "FAILED", "ALREADY_DELIVERED", now);
                } else {
                    store.insertDelivery(position, batch.id(), batch.reportId(), alertId, r.clientRef(), r.instrumentId(), r.issuer(),
                            "QUEUED", null, now);
                    queued++;
                }
            }
            Map<String, Object> data = data(batch);
            data.put("requested", new LinkedHashSet<>(request.alertIds()).size());
            data.put("hasNote", note != null);
            data.put("noteHash", note == null ? null : sha256(note));
            audit.record(AuditAction.ALERT_REPORT_APPROVED, owner, data);
            Map<String, Object> queuedData = data(batch);
            queuedData.put("queued", queued);
            audit.record(AuditAction.ALERT_REPORT_QUEUED, owner, queuedData);
            return batch;
        });
    }

    /** Sends the batch's queued recipients one at a time until none is left or the time budget is used. */
    private void process(String owner, Batch batch) {
        Instant deadline = clock.instant().plus(properties.processingBudget());
        if (store.queued(batch.id()) > 0 && batch.snapshot() != null) {
            AgentProfile agent = agent(owner);
            Snapshot snapshot = parse(batch.snapshot());
            // One attempt per recipient per request: a recipient that failed in a way that may pass waits for the next request.
            java.util.Set<UUID> tried = new java.util.HashSet<>();
            Claimed next;
            while (clock.instant().isBefore(deadline)
                    && (next = store.claimNext(batch.id(), clock.instant(), LEASE, tried).orElse(null)) != null) {
                tried.add(next.id());
                deliverOne(owner, agent, batch, snapshot, next);
            }
        }
        if (store.queued(batch.id()) == 0) {
            store.clearContent(batch.id(), clock.instant());
        }
    }

    private void deliverOne(String owner, AgentProfile agent, Batch batch, Snapshot snapshot, Claimed delivery) {
        Instant now = clock.instant();
        ClientRecord client;
        try {
            client = clients.findOwned(UUID.fromString(delivery.clientRef().strip()));
        } catch (ClientNotFoundException ex) {
            finish(owner, batch, delivery, "NOT_AUTHORISED", "CLIENT_NOT_YOURS", now);
            return;
        } catch (ClientServiceUnavailableException ex) {
            retry(owner, batch, delivery, "CLIENT_SERVICE_UNAVAILABLE", now);
            return;
        } catch (IllegalArgumentException ex) {
            finish(owner, batch, delivery, "NOT_AUTHORISED", "NOT_A_CLIENT_RECORD", now);
            return;
        }
        if (client.email() == null || client.email().isBlank()) {
            finish(owner, batch, delivery, "FAILED", "MISSING_EMAIL", now);
            return;
        }
        AlertReportRenderer.Rendered email = AlertReportRenderer.render(snapshot.report(), snapshot.sources(),
                delivery.issuer(), client.firstName(), agent.displayName(), agent.email(), batch.agentNote());
        try {
            String messageId = sender.send(new OutgoingMail(agent.displayName() + " via Scrooge Bank", mail.fromAddress(),
                    client.email(), agent.email(), agent.email(), email.subject(), email.text(), email.html(), null));
            store.markSent(delivery.id(), messageId, clock.instant());
            Map<String, Object> data = deliveryData(batch, delivery, "SENT");
            data.put("sesMessageId", messageId);
            audit.record(AuditAction.ALERT_REPORT_SENT, owner, data);
        } catch (MailUnavailableException ex) {
            retry(owner, batch, delivery, "MAIL_NOT_CONFIGURED", now);
        } catch (MailSendException ex) {
            log.warn("alert report {} not sent to alert {}: {}", batch.reportId(), delivery.alertId(), ex.code());
            if (ex.retryable()) {
                retry(owner, batch, delivery, ex.code(), now);
            } else {
                finish(owner, batch, delivery, "FAILED", ex.code(), now);
            }
        }
    }

    private void retry(String owner, Batch batch, Claimed delivery, String code, Instant now) {
        String status = store.retryLater(delivery.id(), code, properties.maxAttempts(), now);
        if ("FAILED".equals(status)) {
            audit.record(AuditAction.ALERT_REPORT_FAILED, owner, withError(deliveryData(batch, delivery, "FAILED"), code));
        }
    }

    private void finish(String owner, Batch batch, Claimed delivery, String status, String code, Instant now) {
        store.markFinal(delivery.id(), status, code, now);
        audit.record(AuditAction.ALERT_REPORT_FAILED, owner, withError(deliveryData(batch, delivery, status), code));
    }

    private static Map<String, Object> withError(Map<String, Object> data, String code) {
        data.put("errorCode", code);
        return data;
    }

    private static Map<String, Object> data(Batch batch) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", batch.id().toString());
        data.put("eventId", batch.eventId().toString());
        data.put("reportId", batch.reportId().toString());
        data.put("reportVersion", batch.reportVersion());
        data.put("reportHash", batch.reportHash());
        return data;
    }

    private static Map<String, Object> deliveryData(Batch batch, Claimed delivery, String status) {
        Map<String, Object> data = data(batch);
        data.put("alertId", delivery.alertId().toString());
        data.put("clientId", delivery.clientRef());
        data.put("status", status);
        return data;
    }

    private Snapshot parse(String snapshot) {
        try {
            JsonNode node = mapper.readTree(snapshot);
            return new Snapshot(node.get("report"), mapper.readValue(node.get("sources").traverse(mapper),
                    new TypeReference<List<Source>>() { }));
        } catch (java.io.IOException ex) {
            throw ApiException.unavailable("The stored report could not be read; nothing was sent");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String requestHash(DeliveryRequest request) {
        List<String> alertIds = new LinkedHashSet<>(request.alertIds()).stream().map(UUID::toString).sorted().toList();
        String note = request.agentNote() == null ? "" : request.agentNote().strip();
        return sha256(request.eventId() + "|" + request.expectedReportVersion() + "|" + String.join(",", alertIds) + "|" + note);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
