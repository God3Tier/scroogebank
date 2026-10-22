package bank.engagement.holidays;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HolidaySyncConfiguration {

    /** Its own client with timeouts, so a slow data.gov.sg cannot hold a sync open. */
    @Bean
    HolidaySource holidaySource(RestClient.Builder builder, ObjectMapper objectMapper,
            HolidaySyncProperties properties) {
        RestClient restClient = builder.clone()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(properties.timeout())
                                .withReadTimeout(properties.timeout())))
                .build();
        return new DataGovSgHolidaySource(restClient, objectMapper, properties);
    }
}
