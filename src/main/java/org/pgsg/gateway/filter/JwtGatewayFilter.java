package org.pgsg.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.pgsg.common.response.CommonResponse;
import org.pgsg.config.security.jwt.JwtUtils;
import org.pgsg.config.security.token.TokenProvider;
import org.pgsg.config.security.token.TokenType;
import org.pgsg.gateway.auth.AuthProvider;
import org.pgsg.gateway.cache.CacheUtil;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@Component
public class JwtGatewayFilter implements GlobalFilter, Ordered {

	private static final String HEADER_TRACE_ID = "X-Trace-Id";
	private static final List<PathPattern> WHITELIST = Stream.of(
					"/api/v1/auth/login",
					"/api/v1/auth/signup",
					"/api/v1/auth/reissue",
					"/actuator/health",
					"/actuator/health/**",
					"/actuator/info",
					"/actuator/prometheus",
					"/actuator/prometheus/**",
					"/actuator/metrics",
					"/actuator/metrics/**"
			).map(PathPatternParser.defaultInstance::parse)
			.toList();

	private final Tracer tracer;
	private final TokenProvider jwtTokenProvider;
	private final AuthProvider authProvider;
	private final ObjectMapper objectMapper;
	private final Cache<String, Claims> claimsCache;

	public JwtGatewayFilter(Tracer tracer, TokenProvider jwtTokenProvider,
							AuthProvider authProvider, ObjectMapper objectMapper,
							CacheUtil cacheUtil) {
		this.tracer = tracer;
		this.jwtTokenProvider = jwtTokenProvider;
		this.authProvider = authProvider;
		this.objectMapper = objectMapper;
		this.claimsCache = cacheUtil.getClaimsCache();
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		ServerHttpRequest request = exchange.getRequest();
		String path = request.getURI().getPath();
		String traceId = resolveTraceId();

		// 헤더 초기화: x-user-* 제거 + traceId 주입
		ServerHttpRequest sanitized = request.mutate()
				.headers(headers -> {
					headers.keySet().removeIf(key -> key.toLowerCase().startsWith("x-user-"));
					headers.set(HEADER_TRACE_ID, traceId);
				})
				.build();

		log.debug("[JwtGatewayFilter] 요청 수신: {} {}", request.getMethod(), path);

		// 화이트리스트 통과
		if (isWhitelisted(path)) {
			return chain.filter(exchange.mutate().request(sanitized).build());
		}

		// 토큰 누락
		String accessToken = JwtUtils.resolveToken(
				request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));

		if (accessToken == null) {
			log.warn("[JwtGatewayFilter] Access 토큰 누락 - 차단 (TraceID: {})", traceId);
			return onAuthError(exchange, "Access 토큰이 필요합니다.", traceId);
		}

		return authenticate(exchange, sanitized, chain, accessToken, traceId);
	}

	private Mono<Void> authenticate(ServerWebExchange exchange, ServerHttpRequest sanitized,
									GatewayFilterChain chain, String accessToken, String traceId) {
		// [Step 1] 로컬 검증 - 만료되지 않았더라도 검증은 수행
		Claims cachedClaims = claimsCache.getIfPresent(accessToken);
		Mono<Boolean> localValidation = (cachedClaims != null && !isExpired(cachedClaims))
				? Mono.just(true)
				: Mono.fromCallable(() -> jwtTokenProvider.validateToken(accessToken));

		return localValidation
				.flatMap(valid -> {
					if (!valid) {
						return Mono.error(new InsufficientAuthenticationException("유효하지 않거나 만료된 토큰입니다."));
					}
					// [Step 2] 블랙리스트 검증 (항상 수행)
					return authProvider.verifyToken(accessToken);
				})
				.flatMap(verified -> {
					if (!verified) {
						return Mono.error(new InsufficientAuthenticationException("이미 로그아웃되었거나 사용할 수 없는 토큰입니다."));
					}
					// [Step 3] Claims 파싱 (캐시 HIT 시 생략)
					Claims cached = claimsCache.getIfPresent(accessToken);
					if (cached != null) {
						log.debug("[JwtGatewayFilter] 캐시 HIT (TraceID: {})", traceId);
						return Mono.just(cached);
					}
					return Mono.fromCallable(() -> jwtTokenProvider.parseClaims(accessToken))
							.doOnNext(claims -> claimsCache.put(accessToken, claims));
				})
				.flatMap(claims -> {
					String tokenType = claims.get(JwtUtils.CLAIM_TOKEN_TYPE, String.class);
					if (!TokenType.ACCESS.matches(tokenType)) {
						log.warn("[JwtGatewayFilter] 허용되지 않은 토큰 타입 ({}) - 차단 (TraceID: {})", tokenType, traceId);
						return Mono.error(new InsufficientAuthenticationException("Access 토큰이 필요합니다."));
					}
					// [Step 4] 사용자 헤더 주입
					ServerHttpRequest mutated = injectUserHeaders(sanitized, claims);
					log.debug("[JwtGatewayFilter] 인증 성공 (TraceID: {})", traceId);
					return chain.filter(exchange.mutate().request(mutated).build());
				})
				.onErrorResume(InsufficientAuthenticationException.class,
						e -> onAuthError(exchange, e.getMessage(), traceId))
				.onErrorResume(JwtException.class, e -> {
					log.error("[JwtGatewayFilter] JWT 예외: {} (TraceID: {})", e.getMessage(), traceId);
					return onAuthError(exchange, "토큰 인증 중 오류가 발생했습니다.", traceId);
				})
				.onErrorResume(IllegalArgumentException.class, e -> {
					log.error("[JwtGatewayFilter] 잘못된 인자: {} (TraceID: {})", e.getMessage(), traceId);
					return onAuthError(exchange, "토큰 인증 중 오류가 발생했습니다.", traceId);
				});
	}

	private boolean isExpired(Claims claims) {
		Date expiration = claims.getExpiration();
		return expiration != null && expiration.before(new Date());
	}

	private Mono<Void> onAuthError(ServerWebExchange exchange, String message, String traceId) {
		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(HttpStatus.UNAUTHORIZED);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

		CommonResponse<Object> errorResponse = new CommonResponse<>(
				false,
				message,
				null,
				traceId
		);

		try {
			byte[] body = objectMapper.writeValueAsBytes(errorResponse);
			return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
		} catch (Exception e) {
			log.error("[JwtGatewayFilter] JSON 직렬화 오류 (TraceID: {})", traceId, e);
			return Mono.error(e);
		}
	}

	private ServerHttpRequest injectUserHeaders(ServerHttpRequest request, Claims claims) {
		Boolean enabled = claims.get(JwtUtils.CLAIM_ENABLED, Boolean.class);
		return request.mutate()
				.header(JwtUtils.HEADER_USER_ID, claims.getSubject())
				.header(JwtUtils.HEADER_USERNAME, claims.get(JwtUtils.CLAIM_USERNAME, String.class))
				.header(JwtUtils.HEADER_ROLES, claims.get(JwtUtils.CLAIM_USER_ROLE, String.class))
				.header(JwtUtils.HEADER_USER_NAME, encodeValue(claims.get(JwtUtils.CLAIM_NAME, String.class)))
				.header(JwtUtils.HEADER_USER_NICKNAME, encodeValue(claims.get(JwtUtils.CLAIM_NICKNAME, String.class)))
				.header(JwtUtils.HEADER_ENABLED, enabled != null ? enabled.toString() : "false")
				.build();
	}

	private boolean isWhitelisted(String path) {
		PathContainer pathContainer = PathContainer.parsePath(path);
		return WHITELIST.stream().anyMatch(pattern -> pattern.matches(pathContainer));
	}

	private String resolveTraceId() {
		if (tracer.currentSpan() != null) {
			return Objects.requireNonNull(tracer.currentSpan()).context().traceId();
		}
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private String encodeValue(String value) {
		return Optional.ofNullable(value)
				.map(v -> URLEncoder.encode(v, StandardCharsets.UTF_8))
				.orElse(null);
	}

	@Override
	public int getOrder() {
		return SecurityWebFiltersOrder.AUTHORIZATION.getOrder() + 1;
	}
}
