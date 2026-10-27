package bank.clienthealth.task.assignment;

import bank.clienthealth.task.domain.RelationshipTask.AssignmentSnapshot;
import bank.clienthealth.task.port.ClientAssignmentPort;
import java.time.Instant;
import java.time.Duration;
import bank.clienthealth.metrics.ClientHealthMetrics;
import bank.clienthealth.metrics.ClientHealthMetrics.AssignmentOutcome;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/** Contract-B adapter. It fails closed when ownership cannot be confirmed. */
public final class HttpClientAssignmentAdapter implements ClientAssignmentPort {
    private final RestClient restClient;
    private final ClientCredentialsTokenProvider tokens;
    private final String baseUrl;
    private final ClientHealthMetrics metrics;

    public HttpClientAssignmentAdapter(
            RestClient restClient, ClientCredentialsTokenProvider tokens, String baseUrl
    ) {
        this(restClient, tokens, baseUrl, ClientHealthMetrics.noOp());
    }

    public HttpClientAssignmentAdapter(
            RestClient restClient, ClientCredentialsTokenProvider tokens, String baseUrl,
            ClientHealthMetrics metrics
    ) {
        this.restClient = restClient;
        this.tokens = tokens;
        this.baseUrl = baseUrl;
        this.metrics = metrics;
    }

    @Override
    public AssignmentSnapshot resolve(String clientId) {
        long started = System.nanoTime();
        AssignmentOutcome[] outcome = {AssignmentOutcome.UNAVAILABLE};
        try {
            if (!StringUtils.hasText(baseUrl)) {
                throw new AssignmentUnavailableException("Client-assignment base URL is not configured");
            }
            var uri = UriComponentsBuilder.fromUriString(StringUtils.trimTrailingCharacter(baseUrl, '/'))
                    .pathSegment("internal", "v1", "clients", clientId, "assignment").build().toUri();
            AssignmentResponse response = restClient.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, result) -> {
                        outcome[0] = AssignmentOutcome.NOT_FOUND;
                        throw new AssignmentRejectedException("Client assignment was not found");
                    })
                    .onStatus(status -> status.value() == 410, (request, result) -> {
                        outcome[0] = AssignmentOutcome.INACTIVE;
                        throw new AssignmentRejectedException("Client assignment is not active");
                    })
                    .onStatus(HttpStatusCode::isError,
                            (request, result) -> { throw new AssignmentUnavailableException(
                                    "Client-assignment service returned " + result.getStatusCode()); })
                    .body(AssignmentResponse.class);
            if (response == null || !"1".equals(response.schemaVersion())
                    || !clientId.equals(response.clientId())) {
                outcome[0] = AssignmentOutcome.INVALID_RESPONSE;
                throw new AssignmentUnavailableException("Client-assignment response is invalid");
            }
            if (!response.active()) outcome[0] = AssignmentOutcome.INACTIVE;
            else outcome[0] = AssignmentOutcome.SUCCESS;
            try {
                return new AssignmentSnapshot(response.clientId(), response.assignedAgentSubject(),
                        response.active(), response.assignmentVersion(), response.updatedAt());
            } catch (IllegalArgumentException invalid) {
                outcome[0] = AssignmentOutcome.INVALID_RESPONSE;
                AssignmentUnavailableException unavailable =
                        new AssignmentUnavailableException("Client-assignment response is invalid");
                unavailable.initCause(invalid);
                throw unavailable;
            }
        } catch (AssignmentRejectedException | AssignmentUnavailableException exception) {
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            AssignmentUnavailableException unavailable =
                    new AssignmentUnavailableException("Client-assignment lookup failed");
            unavailable.initCause(exception);
            throw unavailable;
        } finally {
            metrics.assignment(outcome[0], Duration.ofNanos(System.nanoTime() - started));
        }
    }

    record AssignmentResponse(
            String schemaVersion,
            String clientId,
            String assignedAgentSubject,
            boolean active,
            long assignmentVersion,
            Instant updatedAt
    ) { }
}
