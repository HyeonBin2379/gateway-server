package org.pgsg.gateway.client;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.pgsg.common.response.CommonResponse;
import org.pgsg.gateway.auth.AuthDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

//@FeignClient(name = "user-service", fallbackFactory = AuthClientFallbackFactory.class)
@Component
public class AuthClient {

	private final WebClient webClient;

	public AuthClient(WebClient.Builder builder) {
		// WebClient 커넥션 풀 타임아웃 설정 추가
		HttpClient httpClient = HttpClient.create()
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
				.responseTimeout(Duration.ofSeconds(5))
				.doOnConnected(conn -> conn
						.addHandlerLast(new ReadTimeoutHandler(5))
						.addHandlerLast(new WriteTimeoutHandler(5)));

		this.webClient = builder
				.baseUrl("lb://user-service")
				.clientConnector(new ReactorClientHttpConnector(httpClient))
				.build();
	}

	public Mono<CommonResponse<AuthDto.TokenVerifyData>> verifyToken(AuthDto.TokenVerifyRequest request) {
		return webClient.post()
				.uri("/internal/v1/auth/verify")
				.bodyValue(request)
				.retrieve()
				.bodyToMono(new ParameterizedTypeReference<CommonResponse<AuthDto.TokenVerifyData>>() {})
				.onErrorReturn(new CommonResponse<>(false, "인증 서비스 장애", new AuthDto.TokenVerifyData(false), null));
	}
}
