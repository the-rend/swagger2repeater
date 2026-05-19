package io.github.swagger2repeater.model;

import java.util.List;
import java.util.Map;

public record ApiOperation(
        String method,
        String path,
        String groupName,
        String endpointName,
        String summary,
        String description,
        String baseUrl,
        Map<String, String> headers,
        List<ApiParameter> queryParameters,
        String contentType,
        String exampleBody
) {
}