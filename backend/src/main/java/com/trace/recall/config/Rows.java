package com.trace.recall.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据库行（snake_case）到前端视图（camelCase）的统一转换，并解析 JSONB 文本字段。 */
public final class Rows {

    private Rows() {}

    public static Map<String, Object> camelize(Map<String, Object> row, String... jsonbFields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String key = camel(e.getKey());
            Object value = e.getValue();
            for (String jf : jsonbFields) {
                if (key.equals(jf) && value != null) {
                    // PGobject（JSONB 列）与 String 统一先取文本再解析
                    String s = String.valueOf(value).trim();
                    if (s.startsWith("[")) value = Json.readArray(s);
                    else if (s.startsWith("{")) value = Json.readMap(s);
                }
            }
            out.put(key, value);
        }
        return out;
    }

    public static List<Map<String, Object>> camelize(List<Map<String, Object>> rows, String... jsonbFields) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(camelize(r, jsonbFields));
        return out;
    }

    private static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') upper = true;
            else if (upper) { sb.append(Character.toUpperCase(c)); upper = false; }
            else sb.append(c);
        }
        return sb.toString();
    }
}
