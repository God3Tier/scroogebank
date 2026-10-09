package aggregate

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"client-profile-management-service/src/internal/models"

	_ "github.com/lib/pq"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

var (
	agentA = models.Caller{Subject: "dash-agent-a"}
	agentB = models.Caller{Subject: "dash-agent-b"}
	admin  = models.Caller{Subject: "dash-admin", Admin: true}
	// September 2031, Singapore days.
	september = models.Window{From: time.Date(2031, 9, 1, 0, 0, 0, 0, time.UTC), To: time.Date(2031, 9, 30, 0, 0, 0, 0, time.UTC)}
)

func TestClientStatsForAnAgentCountsOnlyTheirClients(t *testing.T) {
	repo := New(openFixtureDB(t, "aggregate_clients.sql"))

	stats, err := repo.ClientStats(context.Background(), agentA, september, false)

	require.NoError(t, err)
	// 501, 502 and 503; deleted 504 is left out.
	assert.Equal(t, int64(3), stats.Total)
	assert.Equal(t, map[string]int64{"UNVERIFIED": 2, "VERIFIED": 1}, stats.ByVerificationStatus)
	assert.Equal(t, int64(2), stats.CreatedInWindow)
	assert.Equal(t, []models.DailyCount{{Date: "2031-09-02", Count: 2}}, stats.DailyCreated)
	assert.Nil(t, stats.ByAgent)
}

func TestClientStatsUsesSingaporeDaysAtTheWindowEdges(t *testing.T) {
	repo := New(openFixtureDB(t, "aggregate_clients.sql"))

	stats, err := repo.ClientStats(context.Background(), agentB, september, false)

	require.NoError(t, err)
	// 505 at midnight is 3 September; 506 is the window's last second; 507 is after it.
	assert.Equal(t, int64(3), stats.Total)
	assert.Equal(t, int64(2), stats.CreatedInWindow)
	assert.Equal(t, []models.DailyCount{{Date: "2031-09-03", Count: 1}, {Date: "2031-09-30", Count: 1}}, stats.DailyCreated)
}

func TestClientStatsForAnAdminGroupsByAgent(t *testing.T) {
	repo := New(openFixtureDB(t, "aggregate_clients.sql"))

	stats, err := repo.ClientStats(context.Background(), admin, september, true)

	require.NoError(t, err)
	assert.GreaterOrEqual(t, stats.Total, int64(6))
	assert.Equal(t, int64(4), stats.CreatedInWindow)
	byAgent := map[string]models.AgentClientCounts{}
	for _, row := range stats.ByAgent {
		byAgent[row.AgentID] = row
	}
	assert.Equal(t, models.AgentClientCounts{AgentID: "dash-agent-a", ClientsOnboarded: 2, Verified: 1, Unverified: 1}, byAgent["dash-agent-a"])
	assert.Equal(t, models.AgentClientCounts{AgentID: "dash-agent-b", ClientsOnboarded: 2, Verified: 1, Unverified: 1}, byAgent["dash-agent-b"])
}

func TestStaleUnverifiedClientsListsTheOldestFirst(t *testing.T) {
	repo := New(openFixtureDB(t, "aggregate_clients.sql"))
	before := time.Date(2031, 9, 10, 0, 0, 0, 0, time.UTC)

	items, err := repo.StaleUnverifiedClients(context.Background(), agentA, before, 1)

	require.NoError(t, err)
	// 503 and 501 are unverified and older; verified 502 and deleted 504 are not listed.
	assert.Equal(t, int64(2), items.StaleUnverifiedTotal)
	require.Len(t, items.StaleUnverified, 1)
	assert.Equal(t, "00000000-0000-0000-0000-000000000503", items.StaleUnverified[0].ClientID)
	assert.Equal(t, "Cai", items.StaleUnverified[0].FirstName)
}

func TestStaleUnverifiedClientsIsAnEmptyListWhenNothingIsStale(t *testing.T) {
	repo := New(openFixtureDB(t, "aggregate_clients.sql"))
	before := time.Date(2031, 9, 1, 0, 0, 0, 0, time.UTC)

	items, err := repo.StaleUnverifiedClients(context.Background(), agentB, before, 50)

	require.NoError(t, err)
	assert.Equal(t, int64(0), items.StaleUnverifiedTotal)
	assert.NotNil(t, items.StaleUnverified)
	assert.Empty(t, items.StaleUnverified)
}

func TestAnAgentWithoutASubjectIsRefused(t *testing.T) {
	repo := New(openFixtureDB(t, "aggregate_clients.sql"))

	_, err := repo.ClientStats(context.Background(), models.Caller{}, september, false)

	assert.ErrorIs(t, err, errNoSubject)
}

// June 2032, and a group cut-off of 1 January 2032 (team_comparison.sql).
var (
	june2032    = models.Window{From: time.Date(2032, 6, 1, 0, 0, 0, 0, time.UTC), To: time.Date(2032, 6, 30, 0, 0, 0, 0, time.UTC)}
	teamCutOff  = time.Date(2032, 1, 1, 0, 0, 0, 0, time.FixedZone("SGT", 8*60*60))
	medianOfTwo = 2.0
)

func TestTeamOnboardingGivesTheCallersCountAndTheGroupMedian(t *testing.T) {
	repo := New(openFixtureDB(t, "team_comparison.sql"))

	team, err := repo.TeamOnboarding(context.Background(), "team-1", june2032, teamCutOff)

	require.NoError(t, err)
	assert.Equal(t, int64(3), team.ClientsOnboarded)
	// team-1 to team-5 onboarded 3, 1, 2, 0 and 4 in June. team-4 counts
	// with zero; team-6 (too old) and team-7 (deleted) are not in the group.
	assert.Equal(t, int64(5), team.CohortSize)
	assert.Equal(t, &medianOfTwo, team.Median)
}

func TestTeamOnboardingForAnAgentOutsideTheGroup(t *testing.T) {
	repo := New(openFixtureDB(t, "team_comparison.sql"))

	team, err := repo.TeamOnboarding(context.Background(), "team-6", june2032, teamCutOff)

	require.NoError(t, err)
	assert.Equal(t, int64(0), team.ClientsOnboarded)
	assert.Equal(t, int64(5), team.CohortSize)
}

func TestTeamOnboardingWithNoGroupHasNoMedian(t *testing.T) {
	repo := New(openFixtureDB(t, "team_comparison.sql"))
	noOneSince := time.Date(2040, 1, 1, 0, 0, 0, 0, time.UTC)

	team, err := repo.TeamOnboarding(context.Background(), "team-1", june2032, noOneSince)

	require.NoError(t, err)
	assert.Equal(t, int64(0), team.CohortSize)
	assert.Nil(t, team.Median)
}

func TestTeamOnboardingNeedsTheCaller(t *testing.T) {
	repo := New(openFixtureDB(t, "team_comparison.sql"))

	_, err := repo.TeamOnboarding(context.Background(), "", june2032, teamCutOff)

	assert.ErrorIs(t, err, errNoSubject)
}

func openFixtureDB(t *testing.T, name string) *sql.DB {
	t.Helper()
	dsn := "host=" + envOrDefault("DB_HOST", "localhost") + " port=" + envOrDefault("DB_PORT", "5433") +
		" user=" + envOrDefault("DB_USER", "postgres") + " password=" + envOrDefault("DB_PASSWORD", "postgres") +
		" dbname=" + envOrDefault("DB_NAME", "myapp") + " sslmode=disable"
	db, err := sql.Open("postgres", dsn)
	require.NoError(t, err)
	t.Cleanup(func() { _ = db.Close() })
	require.NoError(t, db.Ping(), "connect to the test database")

	_, currentFile, _, ok := runtime.Caller(0)
	require.True(t, ok, "locate test fixtures")
	fixture, err := os.ReadFile(filepath.Join(filepath.Dir(currentFile), "testcase", name))
	require.NoError(t, err)
	_, err = db.Exec(string(fixture))
	require.NoError(t, err)
	return db
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
