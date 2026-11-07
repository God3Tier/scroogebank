package bank.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import lombok.experimental.UtilityClass;

/** Runs the handler once on a Cognito event file, for trying it locally (make invoke). */
@UtilityClass
public class Invoke {

    public void main(String[] args) throws IOException {
        Map<String, Object> event = new ObjectMapper().readValue(new File(args[0]), new TypeReference<>() {
        });
        new Handler().handleRequest(event, null);
    }
}
