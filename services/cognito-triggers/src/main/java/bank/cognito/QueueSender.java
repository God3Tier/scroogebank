package bank.cognito;

/** Sends one message body to the audit queue, and throws if the queue did not accept it. */
@FunctionalInterface
interface QueueSender {
    void send(String body);
}
