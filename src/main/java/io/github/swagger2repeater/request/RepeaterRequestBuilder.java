package io.github.swagger2repeater.request;

import io.github.swagger2repeater.model.ApiOperation;
import io.github.swagger2repeater.model.ApiParameter;
import io.github.swagger2repeater.model.AuthConfig;
import io.github.swagger2repeater.model.AuthType;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public final class RepeaterRequestBuilder {
    public BuiltRequest build(ApiOperation operation, AuthConfig authConfig) {
        return build(operation, authConfig, null);
    }

    public BuiltRequest build(ApiOperation operation, AuthConfig authConfig, java.util.Map<String, String> extraHeaders) {
        URI baseUri = toUri(operation.baseUrl());
        String path = buildPath(operation.path(), operation.queryParameters());

        Map<String, String> headers = new LinkedHashMap<>(operation.headers());
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        // A user-supplied Host header overrides the connection target derived from the base URL.
        String hostOverride = extractHeader(headers, "Host");

        String connectHost = host(baseUri);
        int connectPort = port(baseUri);
        boolean useHttps = isHttps(baseUri);
        String hostHeaderValue = hostHeader(baseUri);
        if (hostOverride != null && !hostOverride.isBlank()) {
            HostPort override = parseHostPort(hostOverride.trim(), useHttps);
            connectHost = override.host();
            connectPort = override.port();
            hostHeaderValue = hostOverride.trim();
        }

        byte[] request = buildRawRequest(operation, path, authConfig, headers, hostHeaderValue);
        return new BuiltRequest(connectHost, connectPort, useHttps, request, operation.endpointName());
    }

    private byte[] buildRawRequest(ApiOperation operation, String path, AuthConfig authConfig, Map<String, String> headers, String hostHeaderValue) {
        StringBuilder request = new StringBuilder();
        request.append(operation.method()).append(' ').append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(hostHeaderValue).append("\r\n");

        applyAuth(headers, authConfig);
        headers.forEach((name, value) -> request.append(name).append(": ").append(value == null || value.isBlank() ? defaultHeaderValue(name) : value).append("\r\n"));

        if (!operation.exampleBody().isBlank()) {
            if (!hasHeader(headers, "Content-Type")) {
                request.append("Content-Type: ").append(defaultContentType(operation)).append("\r\n");
            }
            byte[] bodyBytes = operation.exampleBody().getBytes(StandardCharsets.UTF_8);
            request.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            request.append("\r\n").append(operation.exampleBody());
        } else {
            request.append("\r\n");
        }

        return request.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void applyAuth(Map<String, String> headers, AuthConfig authConfig) {
        if (authConfig == null || authConfig.type() == null || authConfig.type() == AuthType.NONE) {
            return;
        }

        switch (authConfig.type()) {
            case BEARER -> headers.put("Authorization", "Bearer " + safe(authConfig.headerValue()));
            case BASIC -> {
                String token = safe(authConfig.username()) + ":" + safe(authConfig.password());
                headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
            }
            case HEADER -> headers.put(safe(authConfig.headerName()), safe(authConfig.headerValue()));
            case NONE -> {
            }
        }
    }

    private String buildPath(String path, java.util.List<ApiParameter> queryParameters) {
        if (queryParameters.isEmpty()) {
            return path;
        }

        StringJoiner query = new StringJoiner("&");
        for (ApiParameter parameter : queryParameters) {
            query.add(parameter.name() + "=" + parameter.exampleValue());
        }
        return path + "?" + query;
    }

    private URI toUri(String baseUrl) {
        try {
            if (baseUrl == null || baseUrl.isBlank()) {
                return new URI("http://localhost");
            }
            if (baseUrl.startsWith("/")) {
                return new URI("http://localhost" + baseUrl);
            }
            if (!baseUrl.contains("://")) {
                return new URI("http://" + baseUrl);
            }
            return new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid base URL: " + baseUrl, e);
        }
    }

    private String host(URI baseUri) {
        return baseUri.getHost() == null || baseUri.getHost().isBlank() ? "localhost" : baseUri.getHost();
    }

    private String hostHeader(URI baseUri) {
        String host = host(baseUri);
        int port = baseUri.getPort();
        if (port == -1) {
            return host;
        }
        return host + ":" + port;
    }

    private int port(URI baseUri) {
        if (baseUri.getPort() != -1) {
            return baseUri.getPort();
        }
        return isHttps(baseUri) ? 443 : 80;
    }

    private boolean isHttps(URI baseUri) {
        return "https".equalsIgnoreCase(baseUri.getScheme());
    }

    private String defaultHeaderValue(String name) {
        return "Authorization".equalsIgnoreCase(name) ? "Bearer example-token" : "example";
    }

    private String defaultContentType(ApiOperation operation) {
        return operation.contentType() == null || operation.contentType().isBlank() ? "application/json" : operation.contentType();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private HostPort parseHostPort(String hostHeader, boolean useHttps) {
        int defaultPort = useHttps ? 443 : 80;
        // IPv6 literal, e.g. [::1]:8080 or [::1]
        if (hostHeader.startsWith("[")) {
            int close = hostHeader.indexOf(']');
            if (close > 0) {
                String host = hostHeader.substring(0, close + 1);
                String rest = hostHeader.substring(close + 1);
                if (rest.startsWith(":")) {
                    return new HostPort(host, parsePort(rest.substring(1), defaultPort));
                }
                return new HostPort(host, defaultPort);
            }
            return new HostPort(hostHeader, defaultPort);
        }
        int colon = hostHeader.lastIndexOf(':');
        if (colon >= 0) {
            return new HostPort(hostHeader.substring(0, colon), parsePort(hostHeader.substring(colon + 1), defaultPort));
        }
        return new HostPort(hostHeader, defaultPort);
    }

    private int parsePort(String value, int defaultPort) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 && parsed <= 65535 ? parsed : defaultPort;
        } catch (NumberFormatException exception) {
            return defaultPort;
        }
    }

    private record HostPort(String host, int port) {
    }

    private String extractHeader(Map<String, String> headers, String headerName) {
        String found = null;
        var iterator = headers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (headerName.equalsIgnoreCase(entry.getKey())) {
                found = entry.getValue();
                iterator.remove();
            }
        }
        return found;
    }

    private boolean hasHeader(Map<String, String> headers, String headerName) {
        for (String name : headers.keySet()) {
            if (headerName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public record BuiltRequest(String host, int port, boolean useHttps, byte[] request, String tabCaption) {
    }
}