package io.github.swagger2repeater.parser;

import io.github.swagger2repeater.model.ApiOperation;
import io.github.swagger2repeater.model.ApiParameter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class OpenApiParser {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    public List<ApiOperation> parse(String document) throws IOException {
        JsonNode root = parseTree(document);
        String baseUrl = detectBaseUrl(root);
        List<ApiOperation> operations = new ArrayList<>();

        JsonNode paths = root.path("paths");
        if (!paths.isObject()) {
            return operations;
        }

        Iterator<Map.Entry<String, JsonNode>> pathEntries = paths.fields();
        while (pathEntries.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathEntries.next();
            String path = pathEntry.getKey();
            JsonNode methods = pathEntry.getValue();

            for (String method : METHODS) {
                JsonNode operationNode = methods.get(method);
                if (operationNode == null || !operationNode.isObject()) {
                    continue;
                }

                operations.add(new ApiOperation(
                        method.toUpperCase(),
                        path,
                    extractGroupName(operationNode),
                    extractEndpointName(path, method, operationNode),
                        text(operationNode, "summary"),
                        text(operationNode, "description"),
                        baseUrl,
                        extractHeaders(root, operationNode),
                        extractQueryParameters(root, operationNode),
                    extractContentType(root, operationNode),
                        extractExampleBody(root, operationNode)
                ));
            }
        }

        return operations;
    }

    private JsonNode parseTree(String document) throws IOException {
        try {
            return JSON.readTree(document);
        } catch (IOException jsonFailure) {
            return YAML.readTree(document);
        }
    }

    private String detectBaseUrl(JsonNode root) {
        if (root.hasNonNull("servers") && root.path("servers").isArray() && root.path("servers").size() > 0) {
            JsonNode server = root.path("servers").get(0);
            return text(server, "url");
        }

        String scheme = root.path("schemes").isArray() && root.path("schemes").size() > 0 ? root.path("schemes").get(0).asText() : "http";
        String host = root.path("host").asText("");
        String basePath = root.path("basePath").asText("");
        if (host.isBlank()) {
            return "";
        }
        return scheme + "://" + host + basePath;
    }

    private Map<String, String> extractHeaders(JsonNode root, JsonNode operationNode) {
        Map<String, String> headers = new LinkedHashMap<>();
        collectParameters(root, operationNode).forEach(parameter -> {
            if ("header".equals(parameter.path("in").asText())) {
                headers.put(parameter.path("name").asText(), parameter.path("example").asText(""));
            }
        });

        String contentType = extractContentType(root, operationNode);
        if (!contentType.isBlank()) {
            headers.putIfAbsent("Content-Type", contentType);
        }

        return headers;
    }

    private List<ApiParameter> extractQueryParameters(JsonNode root, JsonNode operationNode) {
        List<ApiParameter> parameters = new ArrayList<>();
        collectParameters(root, operationNode).forEach(parameter -> {
            if ("query".equals(parameter.path("in").asText())) {
                parameters.add(new ApiParameter(
                        parameter.path("name").asText(),
                        "query",
                        parameter.path("required").asBoolean(false),
                        parameter.path("example").asText(defaultExample(parameter.path("name").asText()))
                ));
            }
        });
        return parameters;
    }

    private String extractExampleBody(JsonNode root, JsonNode operationNode) {
        JsonNode bodyParameter = findBodyParameter(root, operationNode);
        if (bodyParameter != null) {
            JsonNode schema = bodyParameter.path("schema");
            String sample = sampleSchema(root, schema);
            if (!sample.isBlank()) {
                return sample;
            }
        }

        JsonNode requestBody = operationNode.path("requestBody");
        if (!requestBody.isObject()) {
            return "";
        }

        JsonNode content = requestBody.path("content");
        if (!content.isObject()) {
            return "";
        }

        Iterator<String> mediaTypes = content.fieldNames();
        if (!mediaTypes.hasNext()) {
            return "";
        }

        JsonNode mediaType = content.get(mediaTypes.next());
        String example = extractMediaTypeExample(mediaType);
        if (!example.isBlank()) {
            return example;
        }

        JsonNode schema = mediaType.path("schema");
        String sample = sampleSchema(root, schema);
        if (!sample.isBlank()) {
            return sample;
        }

        return "";
    }

    private String extractContentType(JsonNode root, JsonNode operationNode) {
        JsonNode requestBody = operationNode.path("requestBody");
        if (requestBody.isObject()) {
            JsonNode content = requestBody.path("content");
            if (content.isObject() && content.fieldNames().hasNext()) {
                return content.fieldNames().next();
            }
        }

        JsonNode bodyParameter = findBodyParameter(root, operationNode);
        if (bodyParameter != null && bodyParameter.path("in").asText().equals("body")) {
            return "application/json";
        }

        JsonNode formData = findFormParameters(root, operationNode);
        if (formData != null) {
            return "application/x-www-form-urlencoded";
        }

        return "";
    }

    private JsonNode findBodyParameter(JsonNode root, JsonNode operationNode) {
        for (JsonNode parameter : collectParameters(root, operationNode)) {
            if ("body".equals(parameter.path("in").asText())) {
                return parameter;
            }
        }
        return null;
    }

    private JsonNode findFormParameters(JsonNode root, JsonNode operationNode) {
        for (JsonNode parameter : collectParameters(root, operationNode)) {
            if ("formData".equals(parameter.path("in").asText())) {
                return parameter;
            }
        }
        return null;
    }

    private String extractMediaTypeExample(JsonNode mediaType) {
        JsonNode example = mediaType.path("example");
        if (!example.isMissingNode() && !example.isNull()) {
            return stringifyNode(example);
        }

        JsonNode examples = mediaType.path("examples");
        if (examples.isObject() && examples.fieldNames().hasNext()) {
            JsonNode firstExample = examples.elements().next();
            JsonNode value = firstExample.path("value");
            if (!value.isMissingNode() && !value.isNull()) {
                return stringifyNode(value);
            }
        }

        return "";
    }

    private String sampleSchema(JsonNode root, JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return "";
        }

        JsonNode resolved = resolveSchema(root, schema, 0);
        Object sample = sampleValue(root, resolved, 0);
        if (sample == null) {
            return "";
        }

        return stringifyValue(sample);
    }

    private JsonNode resolveSchema(JsonNode root, JsonNode schema, int depth) {
        if (schema == null || depth > 5) {
            return schema;
        }

        JsonNode ref = schema.get("$ref");
        if (ref != null && ref.isTextual()) {
            JsonNode resolved = resolveSchemaRef(root, ref.asText());
            if (resolved != null) {
                return resolveSchema(root, resolved, depth + 1);
            }
        }

        if (schema.has("allOf") && schema.path("allOf").isArray() && schema.path("allOf").size() > 0) {
            return resolveSchema(root, schema.path("allOf").get(0), depth + 1);
        }

        if (schema.has("oneOf") && schema.path("oneOf").isArray() && schema.path("oneOf").size() > 0) {
            return resolveSchema(root, schema.path("oneOf").get(0), depth + 1);
        }

        if (schema.has("anyOf") && schema.path("anyOf").isArray() && schema.path("anyOf").size() > 0) {
            return resolveSchema(root, schema.path("anyOf").get(0), depth + 1);
        }

        return schema;
    }

    private Object sampleValue(JsonNode root, JsonNode schema, int depth) {
        if (schema == null || depth > 5) {
            return "example";
        }

        JsonNode example = schema.get("example");
        if (example != null && !example.isNull()) {
            return fromJsonNode(example);
        }

        JsonNode ref = schema.get("$ref");
        if (ref != null && ref.isTextual()) {
            JsonNode resolved = resolveSchemaRef(root, ref.asText());
            if (resolved != null) {
                return sampleValue(root, resolved, depth + 1);
            }
        }

        JsonNode typeNode = schema.get("type");
        String type = typeNode != null && typeNode.isTextual() ? typeNode.asText() : "";

        if ((type.isEmpty() || "object".equals(type)) && schema.has("properties")) {
            Map<String, Object> object = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = schema.path("properties").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                object.put(field.getKey(), sampleValue(root, field.getValue(), depth + 1));
            }
            return object;
        }

        if ("array".equals(type)) {
            JsonNode items = schema.path("items");
            return List.of(sampleValue(root, items, depth + 1));
        }

        if (schema.has("enum") && schema.path("enum").isArray() && schema.path("enum").size() > 0) {
            return fromJsonNode(schema.path("enum").get(0));
        }

        if ("integer".equals(type) || "number".equals(type)) {
            return 1;
        }

        if ("boolean".equals(type)) {
            return Boolean.TRUE;
        }

        if ("string".equals(type) || type.isEmpty()) {
            String format = schema.path("format").asText("");
            if ("date-time".equals(format)) {
                return "2026-05-19T00:00:00Z";
            }
            if ("date".equals(format)) {
                return "2026-05-19";
            }
            if ("uuid".equals(format)) {
                return "00000000-0000-0000-0000-000000000000";
            }
            return schema.path("title").asText("example");
        }

        return "example";
    }

    private JsonNode resolveSchemaRef(JsonNode root, String refText) {
        if (refText.startsWith("#/components/schemas/")) {
            return root.path("components").path("schemas").path(refText.substring("#/components/schemas/".length()));
        }

        if (refText.startsWith("#/definitions/")) {
            return root.path("definitions").path(refText.substring("#/definitions/".length()));
        }

        return null;
    }

    private Object fromJsonNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(fromJsonNode(item)));
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), fromJsonNode(entry.getValue())));
            return values;
        }
        return node.asText();
    }

    private String stringifyNode(JsonNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            return node.toString();
        }
    }

    private String stringifyValue(Object value) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException e) {
            return String.valueOf(value);
        }
    }

    private String extractGroupName(JsonNode operationNode) {
        JsonNode tags = operationNode.path("tags");
        if (tags.isArray() && tags.size() > 0) {
            String tag = tags.get(0).asText("");
            if (!tag.isBlank()) {
                return tag;
            }
        }

        return "default";
    }

    private String extractEndpointName(String path, String method, JsonNode operationNode) {
        String operationId = text(operationNode, "operationId");
        if (!operationId.isBlank()) {
            return operationId;
        }

        String summary = text(operationNode, "summary");
        if (!summary.isBlank()) {
            return summary;
        }

        return method.toLowerCase() + "_" + path.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    private List<JsonNode> collectParameters(JsonNode root, JsonNode operationNode) {
        List<JsonNode> parameters = new ArrayList<>();

        JsonNode rootParameters = root.path("parameters");
        if (rootParameters.isArray()) {
            rootParameters.forEach(parameters::add);
        }

        JsonNode opParameters = operationNode.path("parameters");
        if (opParameters.isArray()) {
            opParameters.forEach(parameter -> {
                JsonNode resolved = resolveParameter(root, parameter);
                parameters.add(resolved == null ? parameter : resolved);
            });
        }

        return parameters;
    }

    private JsonNode resolveParameter(JsonNode root, JsonNode parameter) {
        JsonNode ref = parameter.get("$ref");
        if (ref == null || !ref.isTextual()) {
            return parameter;
        }

        String refText = ref.asText();
        if (!refText.startsWith("#/parameters/")) {
            return parameter;
        }

        JsonNode parameters = root.path("parameters");
        if (!parameters.isArray()) {
            return parameter;
        }

        String refName = refText.substring("#/parameters/".length());
        for (JsonNode candidate : parameters) {
            if (refName.equals(candidate.path("name").asText())) {
                return candidate;
            }
        }

        return parameter;
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : "";
    }

    private String defaultExample(String name) {
        return switch (name.toLowerCase()) {
            case "id" -> "123";
            case "limit" -> "10";
            case "offset" -> "0";
            default -> "example";
        };
    }
}