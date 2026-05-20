package org.pgsg.gateway.auth;

public class AuthDto {
    public record TokenVerifyRequest(String accessToken) {}

    public record TokenVerifyData(boolean isVerifiedToken) {}
}
