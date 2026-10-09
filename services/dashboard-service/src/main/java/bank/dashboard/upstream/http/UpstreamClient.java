package bank.dashboard.upstream.http;

import bank.dashboard.dto.PanelReason;
import bank.dashboard.exception.UpstreamUnavailableException;
import bank.dashboard.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * Fetches figures from an owning service with the caller's own token. The
 * dashboard holds no service credential, so it can read nothing the caller
 * could not read directly, and each service scopes its own answer (ADR-0033).
 *
 * <p>Every failure becomes an {@link UpstreamUnavailableException}, which
 * costs one panel rather than the page.
 */
@Component
@RequiredArgsConstructor
public class UpstreamClient {

    private final RestClient restClient;
    private final CurrentUser currentUser;

    /**
     * @param service display name for messages
     * @param baseUrl where the service is reachable; blank means it is not
     *                wired up, a supported state that degrades its panels
     * @param query   query parameters, in order
     */
    public <T> T get(String service, String baseUrl, String path, Map<String, ?> query, Class<T> type) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new UpstreamUnavailableException(PanelReason.UPSTREAM_NOT_DEPLOYED, service + " base URL is not configured");
        }
        // Built rather than concatenated: a trailing slash on the base URL
        // would otherwise give "//aggregates", and a brace in it would be
        // read as a URI template variable.
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(StringUtils.trimTrailingCharacter(baseUrl, '/')).path(path);
        query.forEach(uri::queryParam);
        URI target = uri.build().toUri();
        try {
            T body = restClient.get()
                    .uri(target)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentUser.tokenValue())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new UpstreamUnavailableException(reasonFor(response.getStatusCode()),
                                service + " returned " + response.getStatusCode());
                    })
                    .body(type);
            if (body == null) {
                throw new UpstreamUnavailableException(PanelReason.UPSTREAM_ERROR, service + " returned no body");
            }
            return body;
        } catch (UpstreamUnavailableException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new UpstreamUnavailableException(PanelReason.UPSTREAM_ERROR,
                    service + " could not be reached: " + ex.getMessage());
        }
    }

    private static PanelReason reasonFor(HttpStatusCode status) {
        return switch (status.value()) {
            // Refusing the caller is the scoping working, not a fault.
            case 401, 403 -> PanelReason.UPSTREAM_FORBIDDEN;
            // Only account-service answers 409: its window holds accounts in
            // more than one currency, so it will not add their deposits (ADR-0038).
            case 409 -> PanelReason.MIXED_CURRENCIES;
            default -> PanelReason.UPSTREAM_ERROR;
        };
    }
}
