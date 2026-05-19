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
        URI baseUri = toUri(operation.baseUrl());
        String path = buildPath(operation.path(), operation.queryParameters());
        byte[] request = buildRawRequest(operation, baseUri, path, authConfig);
        return new BuiltRequest(host(baseUri), port(baseUri), isHttps(baseUri), request, operation.endpointName());
    }

    public BuiltRequest build(ApiOperation operation, AuthConfig authConfig, java.util.Map<String, String> extraHeaders) {
        URI baseUri = toUri(operation.baseUrl());
        String path = buildPath(operation.path(), operation.queryParameters());
        byte[] request = buildRawRequestWithHeaders(operation, baseUri, path, authConfig, extraHeaders);
        return new BuiltRequest(host(baseUri), port(baseUri), isHttps(baseUri), request, operation.endpointName());
    }

    private byte[] buildRawRequest(ApiOperation operation, URI baseUri, String path, AuthConfig authConfig) {
        StringBuilder request = new StringBuilder();
        request.append(operation.method()).append(' ').append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(hostHeader(baseUri)).append("\r\n");

        Map<String, String> headers = new LinkedHashMap<>(operation.headers());
        headers.entrySet().removeIf(entry -> "host".equalsIgnoreCase(entry.getKey()));
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

    private byte[] buildRawRequestWithHeaders(ApiOperation operation, URI baseUri, String path, AuthConfig authConfig, java.util.Map<String, String> extraHeaders) {
        StringBuilder request = new StringBuilder();
        request.append(operation.method()).append(' ').append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(hostHeader(baseUri)).append("\r\n");

        Map<String, String> headers = new LinkedHashMap<>(operation.headers());
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        headers.entrySet().removeIf(entry -> "host".equalsIgnoreCase(entry.getKey()));
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