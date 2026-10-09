package bank.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TransactionStatsProxyTest {

    private static final String TOKEN = "Bearer test-token";
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);
    private static final String STATS = "{\"total\":3}";

    private MockRestServiceServer server;
    private TransactionStatsProxy proxy;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        proxy = new TransactionStatsProxy(builder, new ObjectMapper());
        ReflectionTestUtils.setField(proxy, "accountServiceBaseUrl", "http://account-service");
        ReflectionTestUtils.setField(proxy, "receiverBaseUrl", "http://receiver");
    }

    @Test
    void anAgentsTotalsCoverOnlyTheAccountsTheyOwn() {
        server.expect(requestTo("http://account-service/aggregates/accounts/ids"))
                .andExpect(method(GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andRespond(withSuccess("{\"accountIds\":[\"a1\",\"a2\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://receiver/aggregates/transactions/stats"))
                .andExpect(method(POST))
                .andExpect(request -> assertThat(request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)).isFalse())
                .andExpect(content().json("{\"from\":\"2026-09-01\",\"to\":\"2026-09-30\",\"accountIds\":[\"a1\",\"a2\"]}", JsonCompareMode.STRICT))
                .andRespond(withSuccess(STATS, MediaType.APPLICATION_JSON));

        var response = proxy.getStats(FROM, TO, false, TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(OK);
        assertThat(response.getBody().get("total").asInt()).isEqualTo(3);
        server.verify();
    }

    @Test
    void anAgentWithNoAccountsStillAsksTheReceiverWithAnEmptyList() {
        server.expect(requestTo("http://account-service/aggregates/accounts/ids"))
                .andRespond(withSuccess("{\"accountIds\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://receiver/aggregates/transactions/stats"))
                .andExpect(content().json("{\"from\":\"2026-09-01\",\"to\":\"2026-09-30\",\"accountIds\":[]}", JsonCompareMode.STRICT))
                .andRespond(withSuccess(STATS, MediaType.APPLICATION_JSON));

        assertThat(proxy.getStats(FROM, TO, false, TOKEN).getStatusCode()).isEqualTo(OK);
        server.verify();
    }

    @Test
    void anAdminsTotalsCoverEveryTransactionWithoutAskingAccountService() {
        server.expect(requestTo("http://receiver/aggregates/transactions/stats"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"from\":\"2026-09-01\",\"to\":\"2026-09-30\",\"all\":true}", JsonCompareMode.STRICT))
                .andRespond(withSuccess(STATS, MediaType.APPLICATION_JSON));

        assertThat(proxy.getStats(FROM, TO, true, TOKEN).getStatusCode()).isEqualTo(OK);
        server.verify();
    }

    @Test
    void anAccountServiceRefusalStopsBeforeTheReceiver() {
        server.expect(requestTo("http://account-service/aggregates/accounts/ids"))
                .andRespond(withStatus(UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid token\"}"));

        var response = proxy.getStats(FROM, TO, false, TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(UNAUTHORIZED);
        server.verify();
    }

    @Test
    void anAnswerWithoutAnAccountListIsABadGateway() {
        server.expect(requestTo("http://account-service/aggregates/accounts/ids"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        var response = proxy.getStats(FROM, TO, false, TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(BAD_GATEWAY);
        server.verify();
    }
}
