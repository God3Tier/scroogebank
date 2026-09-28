package bank.alert.ai;

import java.time.Duration;

public interface AiModelGateway {
    String invoke(AiModelRequest request, Duration timeout);
}
