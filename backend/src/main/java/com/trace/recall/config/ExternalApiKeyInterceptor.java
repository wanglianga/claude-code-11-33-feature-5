package com.trace.recall.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 外部处置系统接口的 API Key 拦截器：仅放行持有有效 X-API-Key 的请求。
 * 外部系统只能拿到脱敏摘要，原始载荷与敏感字段不经过此通道。
 */
@Component
public class ExternalApiKeyInterceptor implements HandlerInterceptor {

    private final JdbcTemplate jdbc;
    private final String configuredKey;

    public ExternalApiKeyInterceptor(JdbcTemplate jdbc,
                                     @Value("${app.external.api-key:}") String configuredKey) {
        this.jdbc = jdbc;
        this.configuredKey = configuredKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String key = request.getHeader("X-API-Key");
        if (key == null || key.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "缺少 X-API-Key 请求头");
            return false;
        }
        List<String> found = jdbc.query("SELECT api_key FROM api_client WHERE api_key = ? AND enabled = TRUE",
                (rs, i) -> rs.getString(1), key);
        boolean ok = !found.isEmpty() || (!configuredKey.isBlank() && configuredKey.equals(key));
        if (!ok) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "API Key 无效或已停用");
            return false;
        }
        return true;
    }
}
