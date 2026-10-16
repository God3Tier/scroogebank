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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.OK;

class TransactionProxyTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final String TOKEN = "Bearer test-token";

    private MockRestServiceServer server;
    private TransactionProxy proxy;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        proxy = new TransactionProxy(builder, new ObjectMapper());
        ReflectionTestUtils.setField(proxy, "accountServiceBaseUrl", "http://account-service");
        ReflectionTestUtils.setField(proxy, "receiverBaseUrl", "http://receiver");
    }

    @Test
    void checksAccountOwnershipThenFetchesTransactionsWithoutForwardingTokenToReceiver() {
        server.expect(requestTo("http://account-service/api/accounts/" + ACCOUNT_ID))
                .andExpect(method(GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andRespond(withSuccess("{\"id\":\"" + ACCOUNT_ID + "\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://receiver/api/accounts/" + ACCOUNT_ID + "/transactions"))
                .andExpect(method(GET))
                .andExpect(request -> assertThat(request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)).isFalse())
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        var response = proxy.getTransactions(ACCOUNT_ID, TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(OK);
        assertThat(response.getBody().isArray()).isTrue();
        server.verify();
    }

    @Test
    void returnsOwnershipFailureWithoutCallingReceiver() {
        server.expect(requestTo("http://account-service/api/accounts/" + ACCOUNT_ID))
                .andExpect(header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andRespond(withStatus(FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"forbidden\"}"));

        var response = proxy.getTransactions(ACCOUNT_ID, TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(FORBIDDEN);
        assertThat(response.getBody().get("error").asText()).isEqualTo("forbidden");
        server.verify();
    }

    @Test
    void listsTransactionsFromTheReceiverWithTheGivenParametersAndNoToken() {
        server.expect(requestTo("http://receiver/api/transactions?cursor=abc_-1&size=50&status=Pending"))
                .andExpect(method(GET))
                .andExpect(request -> assertThat(request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)).isFalse())
                .andRespond(withSuccess("{\"content\":[],\"nextCursor\":null}", MediaType.APPLICATION_JSON));

        var response = proxy.listTransactions("abc_-1", 50, "Pending");

        assertThat(response.getStatusCode()).isEqualTo(OK);
        assertThat(response.getBody().get("content").isArray()).isTrue();
        server.verify();
    }

    @Test
    void leavesOutParametersThatAreNotGiven() {
        server.expect(requestTo("http://receiver/api/transactions"))
                .andRespond(withSuccess("{\"content\":[],\"nextCursor\":null}", MediaType.APPLICATION_JSON));

        proxy.listTransactions(null, null, null);

        server.verify();
    }

    @Test
    void encodesParametersSoTheyCannotAddOthers() {
        server.expect(requestTo("http://receiver/api/transactions?status=Pending%26size%3D1000"))
                .andRespond(withStatus(BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"bad status\"}"));

        var response = proxy.listTransactions(null, null, "Pending&size=1000");

        assertThat(response.getStatusCode()).isEqualTo(BAD_REQUEST);
        server.verify();
    }
}
