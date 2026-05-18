package org.pgsg.gateway.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class CacheUtil {

	private final Cache<String, Boolean> tokenVerifyCache;
	private final Cache<String, Claims> claimsCache;

	public CacheUtil() {
		this.tokenVerifyCache = Caffeine.newBuilder()
				.expireAfterWrite(30, TimeUnit.SECONDS)
				.maximumSize(10_000)
				.build();

		this.claimsCache = Caffeine.newBuilder()
				.expireAfterWrite(30, TimeUnit.SECONDS)
				.maximumSize(10_000)
				.build();
	}

	public Cache<String, Boolean> getTokenVerifyCache() {
		return tokenVerifyCache;
	}

	public Cache<String, Claims> getClaimsCache() {
		return claimsCache;
	}
}