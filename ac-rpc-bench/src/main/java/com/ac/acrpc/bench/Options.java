package com.ac.acrpc.bench;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal "--key value" command-line parser.
 */
final class Options {

    private final Map<String, String> values = new LinkedHashMap<>();

    Options(String[] args, int from) {
        for (int i = from; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Expected '--key value', got: " + arg);
            }
            values.put(arg.substring(2), args[++i]);
        }
    }

    String get(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    String require(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required option --" + key);
        }
        return value;
    }

    int getInt(String key, int defaultValue) {
        return Integer.parseInt(get(key, String.valueOf(defaultValue)));
    }

    double getDouble(String key, double defaultValue) {
        return Double.parseDouble(get(key, String.valueOf(defaultValue)));
    }

    /** Options of the form --label.name value, returned as name -> value. */
    Map<String, String> withPrefix(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                result.put(k.substring(prefix.length()), v);
            }
        });
        return result;
    }
}
