package org.pgsg.gateway.config;

import org.pgsg.common.exception.ErrorConfigProperties;
import org.pgsg.config.json.JsonConfig;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@Import({
		JsonConfig.class,
		ErrorConfigProperties.class
})
public class GatewayAppCtx {

	@Bean
	@LoadBalanced
	public WebClient.Builder webClientBuilder() {
		return WebClient.builder();
	}
}