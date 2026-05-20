package org.pgsg.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgsg.common.response.CommonResponse;
import org.pgsg.config.security.token.TokenProvider;
import org.pgsg.gateway.auth.AuthProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.pgsg.config.security.jwt.JwtUtils;
import reactor.core.publisher.Mono;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.mockito.Mockito.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "downstream.service.url=http://localhost:${wiremock.server.port}"
})
@AutoConfigureWebTestClient
@AutoConfigureWireMock(port = 0)
class JwtGatewayIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private AuthProvider authProvider;

    @TestConfiguration
    static class TestRouteConfig {
        @Bean
        public RouteLocator testRoutes(RouteLocatorBuilder builder, @Value("${downstream.service.url}") String downstreamUrl) {
            return builder.routes()
                    .route("test_route", r -> r.path("/test/**")
                            .filters(f -> f.prefixPath("/internal"))
                            .uri(downstreamUrl))
                    .route("auth_route", r -> r.path("/api/v1/auth/**")
                            .uri(downstreamUrl))
                    .build();
        }
    }

    @Test
    @DisplayName("유효한 토큰 요청 시 사용자 헤더가 정상 주입되어야 한다")
    void success_token_injection() throws JsonProcessingException {
        String token = "valid-token-final";
        String userId = "00000000-0000-0000-0000-000000000001";
        String role = "ROLE_USER";

        when(tokenProvider.validateToken(token)).thenReturn(true);
        Claims claims = Jwts.claims()
                .subject(userId)
                .add(JwtUtils.CLAIM_USER_ROLE, role)
                .add(JwtUtils.CLAIM_TOKEN_TYPE, "access")
                .add(JwtUtils.CLAIM_USERNAME, "tester")
                .build();
        when(tokenProvider.parseClaims(token)).thenReturn(claims);
        when(authProvider.verifyToken(token)).thenReturn(Mono.just(true));

        // CommonResponse를 사용하여 JSON 바디 생성
        String responseBody = objectMapper.writeValueAsString(
                new CommonResponse<>(true, "OK", Map.of("status", "passed"), "test-trace-id")
        );

        stubFor(get(urlEqualTo("/internal/test/headers"))
                .withHeader(JwtUtils.HEADER_USER_ID, equalTo(userId))
                .withHeader(JwtUtils.HEADER_ROLES, equalTo(role))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(responseBody)));

        webTestClient.get().uri("/test/headers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.status").isEqualTo("passed");
    }

    @Test
    @DisplayName("검증한 토큰이 블랙리스트에 포함되어 있다면 401 에러를 반환해야 한다")
    void fail_blacklisted_token() {
        String token = "blacklisted-token-final";
        when(tokenProvider.validateToken(token)).thenReturn(true);
        when(authProvider.verifyToken(token)).thenReturn(Mono.just(false));

        webTestClient.get().uri("/test/headers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.message").isEqualTo("이미 로그아웃되었거나 사용할 수 없는 토큰입니다.");
    }

    @Test
    @DisplayName("화이트리스트에 포함된 경로는 유효한 토큰 없이도 통과되어야 한다")
    void success_whitelist() {
        stubFor(post(urlEqualTo("/api/v1/auth/login"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        webTestClient.post().uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("화이트리스트에 포함되지 않은 경로는 유효한 토큰이 없으면 차단되어야 한다")
    void fail_nonWhitelist_noToken() {
        webTestClient.get().uri("/test/headers")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.message").isEqualTo("Access 토큰이 필요합니다.");
    }

    @Test
    @DisplayName("외부에서 주입한 보안 헤더(x-user-)는 무시되어야 한다")
    void success_spoofing_protection() {
        String token = "spoofing-check-final";
        String realUserId = "00000000-0000-0000-0000-000000000001";

        when(tokenProvider.validateToken(token)).thenReturn(true);
        Claims claims = Jwts.claims().subject(realUserId).add(JwtUtils.CLAIM_USER_ROLE, "ROLE_USER").add(JwtUtils.CLAIM_TOKEN_TYPE, "access").build();
        when(tokenProvider.parseClaims(token)).thenReturn(claims);
        when(authProvider.verifyToken(token)).thenReturn(Mono.just(true));

        stubFor(get(urlEqualTo("/internal/test/headers"))
                .withHeader(JwtUtils.HEADER_USER_ID, equalTo(realUserId))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        webTestClient.get().uri("/test/headers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(JwtUtils.HEADER_USER_ID, "99999") // 스푸핑 시도
                .exchange()
                .expectStatus().isOk();
    }
}
