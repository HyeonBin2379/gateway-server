package org.pgsg.gateway;

import org.pgsg.config.AppCtx;
import org.pgsg.gateway.config.GatewayAppCtx;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ImportAutoConfiguration(exclude = AppCtx.class)
@Import(GatewayAppCtx.class)
public class GatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(GatewayApplication.class, args);
	}
}