package com.trace.recall.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;
import java.util.Map;

/** JSONB 载荷的统一读写（规范化键序，便于哈希与比对）。 */
public final class Json {
    private static final ObjectMapper M = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Json() {}

    public static String write(Object value) {
        try {
            return M.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> readMap(String json) {
        try {
            return json == null ? Map.of() : M.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public static List<Map<String, Object>> readList(String json) {
        try {
            return json == null ? List.of() : M.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /** 通用数组（元素可能是字符串或对象）。 */
    public static List<Object> readArray(String json) {
        try {
            return json == null ? List.of() : M.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> readStringList(String json) {
        try {
            return json == null ? List.of() : M.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public static ObjectMapper mapper() {
        return M;
    }
}
