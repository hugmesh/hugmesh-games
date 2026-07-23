package games.hugmesh.rpgbuilder.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient bffWebClient(@Value("${app.bff.base-url}") String bffBaseUrl) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> ((ClientCodecConfigurer) c).defaultCodecs().maxInMemorySize(256 * 1024 * 1024))
                .build();
        return WebClient.builder()
                .baseUrl(bffBaseUrl)
                .exchangeStrategies(strategies)
                .build();
    }
}
