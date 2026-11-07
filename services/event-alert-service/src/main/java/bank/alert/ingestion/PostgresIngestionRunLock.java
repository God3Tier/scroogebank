package bank.alert.ingestion;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * A PostgreSQL session advisory lock. It belongs to one database connection, so
 * that connection is held for the whole run. If the process dies the connection
 * closes and the database releases the lock, so a crash cannot leave it stuck.
 */
@Component
public class PostgresIngestionRunLock implements IngestionRunLock {
    /** Arbitrary constant shared by every alert-service process: "alert-ingestion". */
    static final long LOCK_KEY = 0x616C657274L;

    private final DataSource dataSource;

    public PostgresIngestionRunLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Hold> tryAcquire() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (!call(connection, "SELECT pg_try_advisory_lock(?)")) {
                connection.close();
                return Optional.empty();
            }
            return Optional.of(new ConnectionHold(connection));
        } catch (SQLException | RuntimeException exception) {
            discard(connection);
            throw new IllegalStateException("could not take the ingestion run lock", exception);
        }
    }

    private static boolean call(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /**
     * Drops a connection whose lock state is unknown. Aborting makes the pool
     * discard the physical connection; merely returning it would keep any lock.
     */
    private static void discard(Connection connection) {
        if (connection == null) return;
        try {
            connection.abort(Runnable::run);
        } catch (SQLException ignored) {
            // The original failure is the useful one.
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Already discarded.
        }
    }

    private static final class ConnectionHold implements Hold {
        private final Connection connection;

        private ConnectionHold(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() {
            boolean released = false;
            try {
                released = call(connection, "SELECT pg_advisory_unlock(?)");
            } catch (SQLException ignored) {
                // Handled below: the connection is discarded, which frees the lock.
            }
            if (released) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // The lock is already released.
                }
            } else {
                discard(connection);
            }
        }
    }
}
