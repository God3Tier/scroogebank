package bank.dashboard;

import org.junit.jupiter.api.Test;
import bank.dashboard.testsupport.DashboardTest;

@DashboardTest
class DashboardServiceApplicationTests {

    /**
     * Guards the one property that makes this service cheap to run and cheap
     * to reason about: it starts with no database. Every other service in the
     * CRM needs a reachable Postgres before its context will come up.
     */
    @Test
    void contextLoadsWithoutADatasource() {
    }
}
