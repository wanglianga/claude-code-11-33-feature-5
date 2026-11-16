package com.trace.recall.web;

import com.trace.recall.regulatory.AuditService;
import com.trace.recall.regulatory.AuthorizationService;
import com.trace.recall.regulatory.RegulatoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 监管答复工作台：问题册/策略册/模板元数据、答复生成（模板/复杂）、
 * 授权（发放/收回）、下载、重新生成、审计轨迹。
 */
@RestController
@RequestMapping("/api/regulatory")
public class RegulatoryController {

    private final RegulatoryService regulatory;
    private final AuthorizationService authorization;
    private final AuditService audit;

    public RegulatoryController(RegulatoryService regulatory, AuthorizationService authorization, AuditService audit) {
        this.regulatory = regulatory;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return regulatory.meta();
    }

    @PostMapping("/responses")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return regulatory.createResponse(body);
    }

    @GetMapping("/responses")
    public List<Map<String, Object>> list() {
        return regulatory.listResponses();
    }

    @GetMapping("/responses/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return regulatory.responseDetail(id);
    }

    @PostMapping("/responses/{id}/authorizations")
    public Map<String, Object> grant(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String grantee = String.valueOf(body.getOrDefault("grantee", "监管对接人"));
        String createdBy = String.valueOf(body.getOrDefault("createdBy", "质量分析员"));
        return authorization.grant(id, grantee, createdBy);
    }

    @PostMapping("/authorizations/{authCode}/revoke")
    public Map<String, Object> revoke(@PathVariable String authCode, @RequestBody Map<String, Object> body) {
        String reason = String.valueOf(body.getOrDefault("reason", "授权收回"));
        String actor = String.valueOf(body.getOrDefault("actor", "质量分析员"));
        return authorization.revoke(authCode, reason, actor);
    }

    @GetMapping("/responses/{id}/authorizations")
    public List<Map<String, Object>> authorizations(@PathVariable long id) {
        return authorization.authorizationsOf(id);
    }

    /** 下载答复（需有效授权；授权收回后 403）。 */
    @GetMapping("/responses/{id}/download")
    public Map<String, Object> download(@PathVariable long id, @RequestParam String authCode) {
        return authorization.download(id, authCode);
    }

    /** 重新生成（需有效授权；旧答复置 SUPERSEDED）。 */
    @PostMapping("/responses/{id}/regenerate")
    public Map<String, Object> regenerate(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String authCode = String.valueOf(body.getOrDefault("authCode", ""));
        String createdBy = String.valueOf(body.getOrDefault("createdBy", "质量分析员"));
        return authorization.regenerate(id, authCode, createdBy);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> auditTrail(@RequestParam(required = false) Long responseId) {
        return responseId == null ? audit.all() : audit.ofResponse(responseId);
    }
}
