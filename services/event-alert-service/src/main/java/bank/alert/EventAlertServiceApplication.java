package bank.alert;

import bank.alert.worker.WorkerMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class EventAlertServiceApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(EventAlertServiceApplication.class);
        application.addListeners(new WorkerMode());
        ConfigurableApplicationContext context = application.run(args);
        if (WorkerMode.isWorker(context.getEnvironment())) {
            // WorkerRunner has run its one window; exit with its code.
            System.exit(SpringApplication.exit(context));
        }
    }
}
