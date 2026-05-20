package org.pgsg.gateway.auth;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.pgsg.gateway.cache.CacheUtil;
import org.pgsg.gateway.client.AuthClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class AuthProviderImpl implements AuthProvider {

	private final Cache<String, Boolean> tokenVerifyCache;
	private final AuthClient authClient;

	public AuthProviderImpl(AuthClient authClient, CacheUtil cacheUtil) {
		this.authClient = authClient;
		this.tokenVerifyCache = cacheUtil.getTokenVerifyCache();
	}

	@Override
	public Mono<Boolean> verifyToken(String accessToken) {
		Boolean cachedResult = tokenVerifyCache.getIfPresent(accessToken);

		if (cachedResult != null) {
			return Mono.just(cachedResult);
		}

		return authClient.verifyToken(new AuthDto.TokenVerifyRequest(accessToken))
				.map(response -> response != null
						&& response.success()
						&& response.data() != null
						&& response.data().isVerifiedToken())
				.doOnNext(result -> tokenVerifyCache.put(accessToken, result))
				.onErrorReturn(false);
	}
}

