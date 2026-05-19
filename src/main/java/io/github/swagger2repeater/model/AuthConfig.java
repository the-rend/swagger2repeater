package io.github.swagger2repeater.model;

public record AuthConfig(
        AuthType type,
        String headerName,
        String headerValue,
        String username,
        String password
) {
    public static AuthConfig none() {
        return new AuthConfig(AuthType.NONE, "", "", "", "");
    }
}