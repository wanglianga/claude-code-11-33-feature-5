package com.trace.recall.regulatory;

import com.trace.recall.config.Rows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 授权：控制监管答复的下载与重新生成。
 * 授权收回后，之后的下载和重新生成一律失效；审计轨迹保留不删。
 */
@Service
public class AuthorizationService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final RegulatoryService regulatory;

    public AuthorizationService(JdbcTemplate jdbc, AuditService audit, RegulatoryService regulatory) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.regulatory = regulatory;
    }

    @Transactional
    public Map<String, Object> grant(long responseId, String grantee, String createdBy) {
        requireGenerated(responseId);
        String authCode = "AUTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("INSERT INTO response_authorization(auth_code, response_id, grantee, status, created_by) VALUES (?,?,?,'ACTIVE',?)",
                authCode, responseId, grantee, createdBy);
        audit.audit(responseId, "AUTH_GRANTED", createdBy, "授权 " + authCode + " 予 " + grantee);
        return Map.of("authCode", authCode, "responseId", responseId, "grantee", grantee, "status", "ACTIVE");
    }

    @Transactional
    public Map<String, Object> revoke(String authCode, String reason, String actor) {
        Map<String, Object> auth = authOf(authCode);
        if ("REVOKED".equals(auth.get("status"))) throw new IllegalStateException("授权 " + authCode + " 已收回");
        jdbc.update("UPDATE response_authorization SET status='REVOKED', revoked_at=now(), revoke_reason=? WHERE auth_code=?",
                reason, authCode);
        long responseId = ((Number) auth.get("response_id")).longValue();
        audit.audit(responseId, "AUTH_REVOKED", actor,
                "授权 " + authCode + " 收回：" + reason + "；之后下载与重新生成失效，审计轨迹保留");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authCode", authCode);
        out.put("status", "REVOKED");
        out.put("revokeReason", reason);
        return out;
    }

    /** 下载：仅当答复处于已生成且授权有效；收回后一律 403。 */
    public Map<String, Object> download(long responseId, String authCode) {
        requireGenerated(responseId);
        requireActiveAuth(responseId, authCode);
        audit.audit(responseId, "DOWNLOAD", authCode, "凭授权 " + authCode + " 下载答复 " + responseId);
        return regulatory.responseDetail(responseId);
    }

    /** 重新生成：同样要求授权有效；旧答复置 SUPERSEDED，新答复取新快照。 */
    @Transactional
    public Map<String, Object> regenerate(long responseId, String authCode, String createdBy) {
        Map<String, Object> response = requireGenerated(responseId);
        requireActiveAuth(responseId, authCode);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("inquiryType", response.get("inquiry_type"));
        req.put("templateCode", response.get("template_code"));
        req.put("questionCodes", com.trace.recall.config.Json.readStringList(String.valueOf(response.get("question_codes"))));
        java.time.OffsetDateTime responseDate = jdbc.queryForObject(
                "SELECT response_date FROM regulatory_response WHERE id = ?", java.time.OffsetDateTime.class, responseId);
        req.put("responseDate", responseDate.toString());
        req.put("createdBy", createdBy);
        Map<String, Object> created = regulatory.createResponse(req);
        jdbc.update("UPDATE regulatory_response SET status='SUPERSEDED' WHERE id = ?", responseId);
        long newId = ((Number) created.get("responseId")).longValue();
        audit.audit(responseId, "REGENERATED", createdBy,
                "凭授权 " + authCode + " 重新生成：旧答复 " + response.get("response_code") + " 置 SUPERSEDED，新答复 " + created.get("responseCode"));
        audit.audit(newId, "REGENERATED", createdBy, "由答复 " + response.get("response_code") + " 重新生成");
        return created;
    }

    public List<Map<String, Object>> authorizationsOf(long responseId) {
        return Rows.camelize(jdbc.queryForList(
                "SELECT * FROM response_authorization WHERE response_id = ? ORDER BY created_at", responseId));
    }

    private Map<String, Object> requireGenerated(long responseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM regulatory_response WHERE id = ?", responseId);
        if (rows.isEmpty()) throw new NoSuchElementException("监管答复不存在: " + responseId);
        Map<String, Object> r = rows.get(0);
        if (!"GENERATED".equals(r.get("status"))) {
            throw new IllegalStateException("答复 " + r.get("response_code") + " 状态为 " + r.get("status") + "，不可下载/重新生成");
        }
        return r;
    }

    private void requireActiveAuth(long responseId, String authCode) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM response_authorization WHERE auth_code = ? AND response_id = ?", authCode, responseId);
        if (rows.isEmpty()) throw new SecurityException("授权无效：" + authCode);
        if (!"ACTIVE".equals(rows.get(0).get("status"))) {
            throw new SecurityException("授权 " + authCode + " 已收回，下载与重新生成失效（审计轨迹保留）");
        }
    }

    private Map<String, Object> authOf(String authCode) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM response_authorization WHERE auth_code = ?", authCode);
        if (rows.isEmpty()) throw new NoSuchElementException("授权不存在: " + authCode);
        return rows.get(0);
    }
}
