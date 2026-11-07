package bank.cognito;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Lambda entry point for the user pool's pre token generation trigger, V2_0 events (ADR-0076).
 *
 * <p>The pool's MFA is optional so that the evaluation accounts can sign in without it; this
 * trigger enforces it for everyone else. It adds the crm/api scope, which every API Gateway route
 * requires, only for a user in the MFA_EXEMPT group or with a TOTP authenticator enrolled. A user
 * without one still signs in, so the app can enrol one, but cannot call the API until a token is
 * issued after enrolment.
 *
 * <p>Unlike the audit trigger, it fails closed: if the MFA settings cannot be read, it throws, and
 * Cognito fails the sign-in or token refresh.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TokenScopeHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    static final String API_SCOPE = "crm/api";
    static final String EXEMPT_GROUP = "MFA_EXEMPT";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<MfaLookup> lookupFactory;
    private final PrintStream out;
    private MfaLookup lookup;

    /** As Lambda creates it. The SDK client is built by the first lookup, not here. */
    public TokenScopeHandler() {
        this(() -> CognitoMfaLookup.fromEnvironment(System::getenv), System.out);
    }

    /** Sets the response's scopes and groups, and returns the event. */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        Map<String, Object> request = (Map<String, Object>) event.getOrDefault("request", Map.of());
        Map<String, Object> groupConfiguration =
                (Map<String, Object>) request.getOrDefault("groupConfiguration", Map.of());
        List<String> groups = (List<String>) groupConfiguration.getOrDefault("groupsToOverride", List.of());

        boolean exempt = groups.contains(EXEMPT_GROUP);
        boolean allowed;
        try {
            allowed = exempt || lookup().hasSoftwareToken((String) event.get("userPoolId"), (String) event.get("userName"));
        } catch (RuntimeException | LinkageError e) {
            log("ERROR", "MFA_LOOKUP_FAILED", event, false, exempt, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }

        // Cognito drops the groups claim when groupOverrideDetails is missing, so the groups are
        // always copied across, without MFA_EXEMPT: no service needs to see it.
        Map<String, Object> groupOverride = new LinkedHashMap<>(groupConfiguration);
        groupOverride.put("groupsToOverride", groups.stream().filter(g -> !EXEMPT_GROUP.equals(g)).toList());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("accessTokenGeneration", Map.of("scopesToAdd", allowed ? List.of(API_SCOPE) : List.of()));
        details.put("groupOverrideDetails", groupOverride);
        event.put("response", Map.of("claimsAndScopeOverrideDetails", details));

        log("INFO", "TOKEN_SCOPE", event, allowed, exempt, null);
        return event;
    }

    private MfaLookup lookup() {
        if (lookup == null) {
            lookup = lookupFactory.get();
        }
        return lookup;
    }

    /** One JSON line with no user attribute in it: the user is named by the pool's username. */
    private void log(String level, String msg, Map<String, Object> event, boolean apiAccess, boolean exempt, String error) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("level", level);
        line.put("msg", msg);
        line.put("trigger_source", event.get("triggerSource"));
        line.put("user_name", event.get("userName"));
        line.put("api_access", apiAccess);
        line.put("mfa_exempt", exempt);
        if (error != null) {
            line.put("error", error);
        }
        try {
            out.println(JSON.writeValueAsString(line));
        } catch (Exception | LinkageError e) {
            out.println("{\"level\":\"" + level + "\",\"msg\":\"" + msg + "\"}");
        }
    }
}
