package com.cpg.gateway.web;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ordered maps for JSON views and event payloads (unlike {@code Map.of}, null values are allowed). */
public final class JsonMaps {

    private JsonMaps() {
    }

    public static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }
}
