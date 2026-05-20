package org.pgsg.gateway.config;

import org.pgsg.config.security.jwt.JwtProperties;
import org.pgsg.config.security.jwt.JwtTokenProvider;
import org.pgsg.config.security.token.TokenProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    public TokenProvider tokenProvider(JwtProperties jwtProperties) {
        return new JwtTokenProvider(jwtProperties);
    }
}
