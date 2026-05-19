package io.github.swagger2repeater.request;

import burp.IBurpExtenderCallbacks;
import io.github.swagger2repeater.model.ApiOperation;
import io.github.swagger2repeater.model.AuthConfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public final class BurpRepeaterDispatcher {
    private final IBurpExtenderCallbacks callbacks;
    private final RepeaterRequestBuilder requestBuilder;
    private final Object repeaterApi;
    private final Method createTabGroupMethod;
    private final Method repeaterSendMethod;
    private final Map<String, Object> groupHandles = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> usedCaptionsByGroup = new ConcurrentHashMap<>();

    public BurpRepeaterDispatcher(IBurpExtenderCallbacks callbacks, RepeaterRequestBuilder requestBuilder) {
        this.callbacks = callbacks;
        this.requestBuilder = requestBuilder;
        this.repeaterApi = resolveRepeaterApi(callbacks);
        this.createTabGroupMethod = findMethod(repeaterApi, "createTabGroup", String.class);
        this.repeaterSendMethod = findSendMethod(repeaterApi);
    }

    public String dispatch(ApiOperation operation, AuthConfig authConfig) {
        RepeaterRequestBuilder.BuiltRequest builtRequest = requestBuilder.build(operation, authConfig);
        String caption = computeUniqueCaption(operation);
        // produce a new BuiltRequest with the computed caption
        RepeaterRequestBuilder.BuiltRequest requestWithCaption = new RepeaterRequestBuilder.BuiltRequest(
                builtRequest.host(), builtRequest.port(), builtRequest.useHttps(), builtRequest.request(), caption);

        Object target = resolveTarget(operation.groupName());

        if (target != null && invokeSend(target, requestWithCaption)) {
            return caption;
        }

        callbacks.sendToRepeater(requestWithCaption.host(), requestWithCaption.port(), requestWithCaption.useHttps(), requestWithCaption.request(), requestWithCaption.tabCaption());
        return caption;
    }

    public String dispatch(ApiOperation operation, AuthConfig authConfig, String forcedCaption) {
        RepeaterRequestBuilder.BuiltRequest builtRequest = requestBuilder.build(operation, authConfig);
        String group = operation.groupName() == null || operation.groupName().isBlank() ? "_default" : operation.groupName();
        String candidate = forcedCaption == null || forcedCaption.isBlank() ? builtRequest.tabCaption() : forcedCaption;

        // ensure uniqueness within group
        Set<String> used = usedCaptionsByGroup.computeIfAbsent(group, k -> ConcurrentHashMap.newKeySet());
        String caption;
        synchronized (used) {
            caption = candidate;
            if (used.contains(caption)) {
                int i = 2;
                while (used.contains(caption)) {
                    caption = candidate + "-" + i;
                    i++;
                }
            }
            used.add(caption);
        }

        RepeaterRequestBuilder.BuiltRequest requestWithCaption = new RepeaterRequestBuilder.BuiltRequest(
                builtRequest.host(), builtRequest.port(), builtRequest.useHttps(), builtRequest.request(), caption);

        Object target = resolveTarget(operation.groupName());

        if (target != null && invokeSend(target, requestWithCaption)) {
            return caption;
        }

        callbacks.sendToRepeater(requestWithCaption.host(), requestWithCaption.port(), requestWithCaption.useHttps(), requestWithCaption.request(), requestWithCaption.tabCaption());
        return caption;
    }

    public String dispatch(ApiOperation operation, AuthConfig authConfig, String forcedCaption, java.util.Map<String, String> extraHeaders) {
        RepeaterRequestBuilder.BuiltRequest builtRequest = requestBuilder.build(operation, authConfig, extraHeaders);
        String group = operation.groupName() == null || operation.groupName().isBlank() ? "_default" : operation.groupName();
        String candidate = forcedCaption == null || forcedCaption.isBlank() ? builtRequest.tabCaption() : forcedCaption;

        // ensure uniqueness within group
        Set<String> used = usedCaptionsByGroup.computeIfAbsent(group, k -> ConcurrentHashMap.newKeySet());
        String caption;
        synchronized (used) {
            caption = candidate;
            if (used.contains(caption)) {
                int i = 2;
                while (used.contains(caption)) {
                    caption = candidate + "-" + i;
                    i++;
                }
            }
            used.add(caption);
        }

        RepeaterRequestBuilder.BuiltRequest requestWithCaption = new RepeaterRequestBuilder.BuiltRequest(
                builtRequest.host(), builtRequest.port(), builtRequest.useHttps(), builtRequest.request(), caption);

        Object target = resolveTarget(operation.groupName());

        if (target != null && invokeSend(target, requestWithCaption)) {
            return caption;
        }

        callbacks.sendToRepeater(requestWithCaption.host(), requestWithCaption.port(), requestWithCaption.useHttps(), requestWithCaption.request(), requestWithCaption.tabCaption());
        return caption;
    }

    private String computeUniqueCaption(ApiOperation operation) {
        String group = operation.groupName() == null || operation.groupName().isBlank() ? "_default" : operation.groupName();
        String path = operation.path() == null ? "" : operation.path();
        // strip query and trailing slash
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String base;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            base = path.substring(lastSlash + 1);
        } else if (!path.isBlank()) {
            base = path;
        } else if (operation.baseUrl() != null && !operation.baseUrl().isBlank()) {
            base = operation.baseUrl();
        } else {
            base = operation.endpointName();
        }

        // normalize
        if (base.isBlank()) base = "root";

        Set<String> used = usedCaptionsByGroup.computeIfAbsent(group, k -> ConcurrentHashMap.newKeySet());

        synchronized (used) {
            String candidate = base;
            if (!used.contains(candidate)) {
                used.add(candidate);
                return candidate;
            }

            // fall back to numeric suffix
            int i = 2;
            while (true) {
                candidate = base + "-" + i;
                if (!used.contains(candidate)) {
                    used.add(candidate);
                    return candidate;
                }
                i++;
            }
        }
    }

    private Object resolveTarget(String groupName) {
        if (groupName == null || groupName.isBlank() || repeaterApi == null || createTabGroupMethod == null) {
            return repeaterApi;
        }

        return groupHandles.computeIfAbsent(groupName, this::createGroupHandle);
    }

    private Object createGroupHandle(String groupName) {
        try {
            Object groupHandle = createTabGroupMethod.invoke(repeaterApi, groupName);
            return groupHandle != null ? groupHandle : repeaterApi;
        } catch (IllegalAccessException | InvocationTargetException exception) {
            callbacks.printError("Unable to create Repeater tab group '" + groupName + "': " + unwrapMessage(exception));
            return repeaterApi;
        }
    }

    private boolean invokeSend(Object target, RepeaterRequestBuilder.BuiltRequest builtRequest) {
        Method sendMethod = target == repeaterApi ? repeaterSendMethod : findSendMethod(target);
        if (sendMethod == null && target == repeaterApi) {
            sendMethod = findSendMethod(callbacks);
        }

        if (sendMethod == null) {
            return false;
        }

        try {
            if (sendMethod.getParameterCount() == 5) {
                sendMethod.invoke(target, builtRequest.host(), builtRequest.port(), builtRequest.useHttps(), builtRequest.request(), builtRequest.tabCaption());
                return true;
            }
            if (sendMethod.getParameterCount() == 4) {
                sendMethod.invoke(target, builtRequest.host(), builtRequest.port(), builtRequest.useHttps(), builtRequest.request());
                return true;
            }
            return false;
        } catch (IllegalAccessException | InvocationTargetException exception) {
            callbacks.printError("Unable to send request to Repeater: " + unwrapMessage(exception));
            return false;
        }
    }

    private Object resolveRepeaterApi(IBurpExtenderCallbacks callbacks) {
        Method repeaterMethod = findMethod(callbacks, "repeater");
        if (repeaterMethod == null) {
            return null;
        }

        try {
            return repeaterMethod.invoke(callbacks);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            callbacks.printError("Unable to access Burp Repeater API: " + unwrapMessage(exception));
            return null;
        }
    }

    private Method findSendMethod(Object target) {
        if (target == null) {
            return null;
        }

        for (Method method : target.getClass().getMethods()) {
            if (!"sendToRepeater".equals(method.getName())) {
                continue;
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 5
                    && parameterTypes[0] == String.class
                    && parameterTypes[1] == int.class
                    && parameterTypes[2] == boolean.class
                    && parameterTypes[3] == byte[].class
                    && parameterTypes[4] == String.class) {
                return method;
            }

            if (parameterTypes.length == 4
                    && parameterTypes[0] == String.class
                    && parameterTypes[1] == int.class
                    && parameterTypes[2] == boolean.class
                    && parameterTypes[3] == byte[].class) {
                return method;
            }
        }

        return null;
    }

    private Method findMethod(Object target, String methodName, Class<?>... parameterTypes) {
        if (target == null) {
            return null;
        }

        try {
            return target.getClass().getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private String unwrapMessage(Exception exception) {
        Throwable cause = exception instanceof InvocationTargetException invocationTargetException && invocationTargetException.getCause() != null
                ? invocationTargetException.getCause()
                : exception;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}