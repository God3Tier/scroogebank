package bank.user.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserAuditPayload(
        @JsonProperty("resource_type") String resourceType,
        @JsonProperty("resource_id") String resourceId,
        @JsonProperty("endpoint") String endpoint,
        @JsonProperty("http_method") String httpMethod,
        @JsonProperty("status_code") int statusCode,
        @JsonProperty("datetime") String datetime,
        @JsonProperty("user_role") String userRole
) {
}
