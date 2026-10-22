package bank.user.activity;

import io.grpc.ConnectivityState;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the auditor channel against a real gRPC server on a local port. */
class ActivityConfigTest {

    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void startServer() throws IOException {
        server = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create()).build().start();
    }

    @AfterEach
    void stopAll() {
        if (channel != null) {
            channel.shutdownNow();
        }
        server.shutdownNow();
    }

    @Test
    void channelConnectsBeforeAnyCall() throws Exception {
        channel = new ActivityConfig().auditorChannel("localhost:" + server.getPort(), "");

        assertThat(awaitState(channel, ConnectivityState.READY, 5, TimeUnit.SECONDS)).isTrue();
    }

    /** Waits for the state without asking the channel to connect itself. */
    private static boolean awaitState(ManagedChannel channel, ConnectivityState wanted, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        ConnectivityState state = channel.getState(false);
        while (state != wanted) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            CountDownLatch changed = new CountDownLatch(1);
            channel.notifyWhenStateChanged(state, changed::countDown);
            changed.await(left, TimeUnit.NANOSECONDS);
            state = channel.getState(false);
        }
        return true;
    }
}
