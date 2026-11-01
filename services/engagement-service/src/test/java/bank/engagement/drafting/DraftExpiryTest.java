package bank.engagement.drafting;

import bank.engagement.integration.clients.ClientRecord;
import bank.engagement.testsupport.PostgresIntegrationTest;
import bank.engagement.testsupport.Tokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Open drafts left alone for 14 days are discarded and cleared (ADR-0068 decision 4). AI is off. */
class DraftExpiryTest extends PostgresIntegrationTest {

    private static final UUID CLIENT = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Autowired
    private DraftExpiry expiry;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        when(clients.findOwned(CLIENT))
                .thenReturn(new ClientRecord(CLIENT, "Synthetic", "synthetic.client@example.test", "Singapore"));
    }

    private String create() throws Exception {
        String response = mvc.perform(post("/api/engagement/drafts").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"%s\",\"purpose\":\"CHECK_IN\",\"tone\":\"WARM\"}".formatted(CLIENT)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode draft = json.readTree(response);
        return draft.get("id").asText();
    }

    private String draftStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM email_drafts WHERE id = ?::uuid", String.class, id);
    }

    @Test
    void anOpenDraftLeftForFourteenDaysIsDiscardedAndCleared() throws Exception {
        String old = create();
        String sending = create();
        jdbc.update("UPDATE email_drafts SET status = 'SENDING' WHERE id = ?::uuid", sending);
        clock.advance(Duration.ofDays(10));
        String recent = create();

        clock.advance(Duration.ofDays(4).plusMinutes(1));
        assertThat(expiry.expire()).isEqualTo(1);

        assertThat(draftStatus(old)).isEqualTo("DISCARDED");
        assertThat(jdbc.queryForObject("SELECT body_template FROM email_drafts WHERE id = ?::uuid", String.class, old))
                .isNull();
        assertThat(draftStatus(sending)).isEqualTo("SENDING");
        assertThat(draftStatus(recent)).isEqualTo("DRAFT");
        assertThat(audits("engagement.draft-discard")).singleElement().asString()
                .contains("\"reason\":\"expired\"").contains("\"actorId\":\"system\"").contains(old);
        assertThat(expiry.expire()).isZero();
    }

    @Test
    void manyDraftsExpireInBatches() throws Exception {
        for (int i = 0; i < DraftExpiry.BATCH_SIZE + 3; i++) {
            jdbc.update("""
                    INSERT INTO email_drafts (id, owner_agent_sub, client_id, purpose, tone, subject_template,
                        body_template, source, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'CHECK_IN', 'WARM', 'Hi', 'Hi {{client_first_name}}', 'TEMPLATE', 'DRAFT',
                        now() - interval '30 days', now() - interval '30 days')
                    """, UUID.randomUUID(), Tokens.AGENT, CLIENT);
        }
        clock.set(java.time.Instant.now());

        assertThat(expiry.expire()).isEqualTo(DraftExpiry.BATCH_SIZE + 3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_drafts WHERE body_template IS NULL", Integer.class))
                .isEqualTo(DraftExpiry.BATCH_SIZE + 3);
    }
}
