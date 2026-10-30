package bank.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class CorruptedAuditLogProxyTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private MockRestServiceServer server;
    private CorruptedAuditLogProxy proxy;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        proxy = new CorruptedAuditLogProxy(builder, new ObjectMapper());
        ReflectionTestUtils.setField(proxy, "auditorBaseUrl", "http://auditor");
    }

    @Test
    void passesTheAuditorsErrorOnWithItsStatus() {
        server.expect(requestTo("http://auditor/api/corrupted-logs/" + ID))
                .andExpect(method(DELETE))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                // The Auditor answers errors in JSON; a plain-text one used to
                // reach the admin as a 500, because this proxy reads JSON.
                .andRespond(withStatus(NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"corrupted audit log not found\"}\n"));

        var response = proxy.deleteCorruptedLog(ID, "admin-token");

        assertThat(response.getStatusCode()).isEqualTo(NOT_FOUND);
        assertThat(response.getBody().get("error").asText()).isEqualTo("corrupted audit log not found");
        server.verify();
    }
}
