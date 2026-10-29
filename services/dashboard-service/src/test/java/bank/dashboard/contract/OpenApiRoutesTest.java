package bank.dashboard.contract;

import org.junit.jupiter.api.Test;

/** Routes no test calls must still be in the OpenAPI contract (ADR-0063). */
class OpenApiRoutesTest {

    @Test
    void everyRouteIsInTheContract() {
        new OpenApiContract().assertListsEveryRoute("bank.dashboard");
    }
}
