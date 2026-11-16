package com.trace.recall.regulatory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 审计轨迹：授权收回后轨迹仍完整保留，只追加不修改。 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void audit(Long responseId, String action, String actor, String detail) {
        jdbc.update("INSERT INTO response_audit(response_id, action, actor, detail) VALUES (?,?,?,?)",
                responseId, action, actor, detail);
    }

    public List<Map<String, Object>> ofResponse(long responseId) {
        return com.trace.recall.config.Rows.camelize(jdbc.queryForList(
                "SELECT * FROM response_audit WHERE response_id = ? OR response_id IS NULL ORDER BY id", responseId));
    }

    public List<Map<String, Object>> all() {
        return com.trace.recall.config.Rows.camelize(jdbc.queryForList("SELECT * FROM response_audit ORDER BY id"));
    }
}
