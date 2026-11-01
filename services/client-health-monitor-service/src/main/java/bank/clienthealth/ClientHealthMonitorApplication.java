package bank.clienthealth;

import bank.clienthealth.runtime.ClientHealthRuntimeMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class ClientHealthMonitorApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ClientHealthMonitorApplication.class);
        application.addListeners(new ClientHealthRuntimeMode());
        ConfigurableApplicationContext context = application.run(args);
        if (ClientHealthRuntimeMode.isAnalysis(context.getEnvironment())) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
