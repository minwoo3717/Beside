package com.example.mockbackend.service;

import com.example.mockbackend.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The product events the server stores (docs/METRICS.md §6.1, openapi ProductEvent): the 12 names, the screens, the
 * platforms and, per name, the props it keeps. Everything else is dropped, so no free text, path, address, token or
 * file name can reach the table even if a client sends one. Adding a name or a prop: this class, the openapi enum and
 * the METRICS table together (the server drops names it does not know, so an app-only change stores nothing).
 */
public final class ProductEventCatalog {
    /** Same order as METRICS §6.1 and the openapi enum. */
    public static final List<String> NAMES = List.of("screen_view", "photos_selected", "generate_tap", "job_cancel_tap",
            "job_failed_shown", "model_ready", "fur_toggle", "ar_issue_shown", "model_placed", "animation_play",
            "photo_saved", "start_over");
    /** Unity DesignedUIController.Page names in lower case, and the web share viewer. */
    public static final Set<String> SCREENS = Set.of("home", "confirm", "progress", "preview", "ar", "error", "settings", "share");
    public static final List<String> PLATFORMS = List.of("android", "web");
    public static final String SCREEN_VIEW = "screen_view";
    /** Codes the app shows besides ERROR_CODES.md (Unity ErrorMessages, BesideApiClient.ApiError). */
    static final Set<String> APP_ERROR_CODES = Set.of(
            "NETWORK_ERROR", "BAD_RESPONSE", "DOWNLOAD_FAILED", "FILE_CORRUPT", "LOAD_FAILED", "WAIT_TIMEOUT");
    private static final Pattern APP_VERSION = Pattern.compile("[0-9A-Za-z._+-]{1,32}");
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** One allowed prop: an enum (values), a whole number in [min, max], or a boolean. */
    record Prop(String key, Set<String> values, long min, long max, boolean bool) {
        static Prop oneOf(String key, String... values) {
            return new Prop(key, new LinkedHashSet<>(Arrays.asList(values)), 0, 0, false);
        }

        static Prop oneOf(String key, Set<String> values) {
            return new Prop(key, values, 0, 0, false);
        }

        static Prop number(String key, long min, long max) {
            return new Prop(key, null, min, max, false);
        }

        static Prop flag(String key) {
            return new Prop(key, null, 0, 0, true);
        }

        /** The value to store, or null when the value is not allowed. */
        Object accept(JsonNode value) {
            if (bool) {
                return value.isBoolean() ? value.booleanValue() : null;
            }
            if (values != null) {
                return value.isTextual() && values.contains(value.textValue()) ? value.textValue() : null;
            }
            if (value.isIntegralNumber() && value.canConvertToLong()) {
                long number = value.longValue();
                return number >= min && number <= max ? number : null;
            }
            return null;
        }
    }

    private static final Map<String, Map<String, Prop>> PROPS = props();

    private ProductEventCatalog() {
    }

    private static Map<String, Map<String, Prop>> props() {
        Set<String> errorCodes = Stream.concat(Arrays.stream(ErrorCode.values()).map(Enum::name), APP_ERROR_CODES.stream())
                .collect(Collectors.toUnmodifiableSet());
        Map<String, Map<String, Prop>> props = new LinkedHashMap<>();
        props.put("screen_view", byKey());
        props.put("photos_selected", byKey(Prop.oneOf("source", "gallery", "camera"), Prop.number("count", 0, 10)));
        props.put("generate_tap", byKey(Prop.oneOf("mode", "new", "resume", "retry"), Prop.number("photoCount", 0, 10)));
        props.put("job_cancel_tap", byKey(Prop.oneOf("stage", "uploading", "queued", "processing", "downloading", "loading"),
                Prop.number("elapsedSec", 0, 86_400)));
        props.put("job_failed_shown", byKey(Prop.oneOf("code", errorCodes), Prop.flag("retryable")));
        props.put("model_ready", byKey(Prop.flag("cached"), Prop.number("loadMs", 0, 3_600_000), Prop.number("waitMs", 0, 3_600_000)));
        props.put("fur_toggle", byKey(Prop.flag("on")));
        props.put("ar_issue_shown", byKey(Prop.oneOf("issue", "CameraDenied", "Unsupported", "NeedsInstall", "SessionError", "NoPlane")));
        props.put("model_placed", byKey(Prop.oneOf("method", "tap", "center"), Prop.number("secSinceArEnter", 0, 86_400)));
        props.put("animation_play", byKey(Prop.oneOf("clip", "Walk", "Sit", "TailWag", "HeadTilt", "Idle")));
        props.put("photo_saved", byKey());
        props.put("start_over", byKey(Prop.oneOf("from", "preview", "ar", "error")));
        if (!props.keySet().equals(new LinkedHashSet<>(NAMES))) {
            throw new IllegalStateException("every product event name needs its props entry");
        }
        return Map.copyOf(props);
    }

    private static Map<String, Prop> byKey(Prop... props) {
        Map<String, Prop> byKey = new LinkedHashMap<>();
        for (Prop prop : props) {
            byKey.put(prop.key(), prop);
        }
        return byKey;
    }

    public static boolean isName(String name) {
        return name != null && PROPS.containsKey(name);
    }

    public static boolean isScreen(String screen) {
        return screen != null && SCREENS.contains(screen);
    }

    public static boolean isPlatform(String platform) {
        return platform != null && PLATFORMS.contains(platform);
    }

    public static boolean isAppVersion(String version) {
        return version != null && APP_VERSION.matcher(version).matches();
    }

    /** A canonical 36-character UUID (any case), as the app's Guid.ToString() and crypto.randomUUID() write it. */
    public static boolean isUuid(String value) {
        return value != null && UUID.matcher(value).matches();
    }

    /** The props of a known event after the checks, and how many keys or values were dropped. */
    public record CleanProps(Map<String, Object> kept, int dropped) {
    }

    /**
     * Keeps only the keys of this event's list whose value is allowed, in the list's order. A props value that is not
     * an object counts as one dropped value. Null or absent props: nothing kept, nothing dropped.
     */
    public static CleanProps clean(String name, JsonNode props) {
        if (props == null || props.isNull() || props.isMissingNode()) {
            return new CleanProps(Map.of(), 0);
        }
        if (!props.isObject()) {
            return new CleanProps(Map.of(), 1);
        }
        Map<String, Prop> allowed = PROPS.get(name);
        Map<String, Object> accepted = new LinkedHashMap<>();
        int dropped = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            Prop prop = allowed.get(field.getKey());
            Object value = prop == null ? null : prop.accept(field.getValue());
            if (value == null) {
                dropped++;
            } else {
                accepted.put(field.getKey(), value);
            }
        }
        Map<String, Object> ordered = new LinkedHashMap<>();
        allowed.keySet().forEach(key -> {
            if (accepted.containsKey(key)) {
                ordered.put(key, accepted.get(key));
            }
        });
        return new CleanProps(ordered, dropped);
    }
}
