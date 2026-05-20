package org.pgsg.gateway.auth;

import reactor.core.publisher.Mono;

public interface AuthProvider {

	Mono<Boolean> verifyToken(String accessToken);
}
