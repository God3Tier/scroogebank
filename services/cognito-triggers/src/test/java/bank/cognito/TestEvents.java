package bank.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/** The sample Cognito events in src/test/resources/events, and reading what the handler logs. */
@UtilityClass
class TestEvents {

    final ObjectMapper JSON = new ObjectMapper();

    /** Values in the sample events' user attributes that must never reach a message or a log line. */
    final List<String> PERSONAL_DATA = List.of("agent.one@example.test", "+6560000001", "Agent", "One");

    /** A fresh, changeable copy of a sample event, by name. */
    Map<String, Object> load(String name) {
        try (InputStream in = TestEvents.class.getResourceAsStream("/events/" + name + ".json")) {
            return JSON.readValue(in, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> attributes(Map<String, Object> event) {
        return (Map<String, Object>) ((Map<String, Object>) event.get("request")).get("userAttributes");
    }

    /** Each line of output, parsed as JSON. */
    List<JsonNode> lines(String output) {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                try {
                    lines.add(JSON.readTree(line));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return lines;
    }
}
