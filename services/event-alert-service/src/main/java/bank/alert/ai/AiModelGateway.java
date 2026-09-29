package bank.alert.ai;

import java.time.Duration;

public interface AiModelGateway {
    String invoke(AiModelRequest request, Duration timeout);

    default AiModelResponse invokeMeasured(AiModelRequest request, Duration timeout) {
        long started = System.nanoTime();
        String output = invoke(request, timeout);
        long latencyMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new AiModelResponse(output, 0, 0, latencyMillis, "UNKNOWN");
    }
}
