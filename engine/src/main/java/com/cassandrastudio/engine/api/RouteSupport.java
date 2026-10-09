package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;

/** Small helpers shared by the feature route classes. */
public final class RouteSupport {
    private RouteSupport() {}

    /** The {id} path parameter: the connection id. */
    public static String id(Context ctx) {
        return ctx.pathParam("id");
    }

    /** The body as a JSON object ({} when empty); 400 when it is not an object. */
    public static JsonNode body(Context ctx) {
        try {
            JsonNode n = Json.MAPPER.readTree(ctx.body().isBlank() ? "{}" : ctx.body());
            if (n == null || !n.isObject()) throw ApiException.badRequest("Body must be a JSON object");
            return n;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Body is not valid JSON");
        }
    }

    /** {@code confirmed} / {@code confirmName} from a request body, for {@link ActionGuard#check}. */
    public static ActionGuard.Confirmation confirmation(JsonNode body) {
        return new ActionGuard.Confirmation(body.path("confirmed").asBoolean(false),
                body.hasNonNull("confirmName") ? body.get("confirmName").asText() : null);
    }

    public static String text(JsonNode body, String field) {
        JsonNode v = body.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    public static String requiredText(JsonNode body, String field) {
        String v = text(body, field);
        if (v == null) throw ApiException.badRequest(field + " is required");
        return v;
    }

    public static Long longParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        if (v == null || v.isBlank()) return null;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " must be a number");
        }
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
