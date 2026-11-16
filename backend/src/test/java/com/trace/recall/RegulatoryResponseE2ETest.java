package com.trace.recall;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * feature-5 监管答复验收：单机构单元、重复回报、来源失效、非常规模板问题，
 * 以及覆盖、最小化（信息损失）、不可披露理由、授权收回与审计保留。
 */
class RegulatoryResponseE2ETest extends BaseE2ESupport {

    @BeforeEach
    void publishTwoReleases() {
        publish(createRun("QC-REV-1", "2026-11-10T18:00:00+08:00"));
        publish(createRun("QC-REV-2", "2026-11-14T18:00:00+08:00"));
    }

    @SuppressWarnings("unchecked")
    private long createResponse(Map<String, Object> body) {
        Map<String, Object> res = rest.postForObject("/api/regulatory/responses", body, Map.class);
        return ((Number) res.get("responseId")).longValue();
    }

    private Map<String, Object> detail(long id) {
        return rest.getForObject("/api/regulatory/responses/" + id, Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void templateProjection_singleInstitutionCellsExplicitlyWithheld() {
        // 常见问询：预批准模板投影（区域×月份×证据层）
        long id = createResponse(Map.of(
                "inquiryType", "TEMPLATE", "templateCode", "TPL-REG-GRADE",
                "questionCodes", List.of("Q-001"),
                "responseDate", "2026-11-15T18:00:00+08:00", "createdBy", "验收测试"));
        Map<String, Object> d = detail(id);
        List<Map<String, Object>> cells = (List<Map<String, Object>>) d.get("cells");
        assertEquals(4, cells.size());

        // 单机构单元（华南=HOSP-丁、华北=HOSP-丙、华东 CONFIRMED=HOSP-甲）必须明确不披露
        Map<String, Object> south = cells.stream().filter(c -> "华南".equals(((Map<String, Object>) c.get("dimensions")).get("region")))
                .findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, south.get("disclosed"));
        assertNull(south.get("confirmedQty"), "不披露单元的数量不得出现在输出中");
        assertNull(south.get("reportCount"));
        assertTrue(String.valueOf(south.get("nonDisclosureReason")).contains("明确不披露"));
        assertTrue(String.valueOf(south.get("nonDisclosureReason")).contains("最小单元格"));
        long withheld = cells.stream().filter(c -> Boolean.FALSE.equals(c.get("disclosed"))).count();
        assertEquals(3, withheld, "三个单机构单元应全部明确不披露（模板过度隐藏）");

        // 双机构单元（华东 POSSIBLE：HOSP-甲+HOSP-乙）正常披露
        Map<String, Object> east = cells.stream().filter(c -> {
            Map<String, Object> dims = (Map<String, Object>) c.get("dimensions");
            return "华东".equals(dims.get("region")) && "POSSIBLE".equals(dims.get("evidenceGrade"));
        }).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, east.get("disclosed"));
        assertEquals(30.0, num(east.get("pendingQty")), 1e-6);
        assertEquals(2, ((Number) east.get("reportCount")).intValue());

        // 重复回报：RPT-003 并入 RPT-001；去重数 6 < 原始 7
        Map<String, Object> snapshot = (Map<String, Object>) d.get("snapshot");
        assertEquals(7, ((Number) snapshot.get("rawReportCount")).intValue());
        assertEquals(6, ((Number) snapshot.get("dedupReportCount")).intValue());
        List<Map<String, Object>> merged = (List<Map<String, Object>>) snapshot.get("mergedReports");
        assertEquals(1, merged.size());
        assertTrue(((List<String>) merged.get(0).get("mergedDuplicates")).contains("RPT-003"));

        // 未定义字段保持待复核
        List<Map<String, Object>> undefined = (List<Map<String, Object>>) snapshot.get("undefinedFields");
        assertEquals(2, undefined.size());
        assertTrue(undefined.stream().allMatch(u -> "PENDING_REVIEW".equals(u.get("status"))));

        // 核验器：去重数、证据分布、数量合计、未定义字段全部核平
        List<Map<String, Object>> recon = (List<Map<String, Object>>) d.get("reconciliation");
        assertEquals(4, recon.size());
        assertTrue(recon.stream().allMatch(r -> "BALANCED".equals(r.get("status"))),
                "全部核平指标应 BALANCED: " + recon);
    }

    @Test
    @SuppressWarnings("unchecked")
    void complexInquiry_equivalentSolutionsAndConstraintConflicts() {
        // 非常规模板问题：Q-003 需要机构单体维度与联系人 → 模板直接拒绝
        ResponseEntity<Map> rejected = rest.postForEntity("/api/regulatory/responses", Map.of(
                "inquiryType", "TEMPLATE", "templateCode", "TPL-REG-GRADE",
                "questionCodes", List.of("Q-003"),
                "responseDate", "2026-11-15T18:00:00+08:00", "createdBy", "验收测试"), Map.class);
        assertEquals(400, rejected.getStatusCode().value());
        assertTrue(String.valueOf(rejected.getBody().get("message")).contains("非常规模板问题"));

        // 复杂问询 Q-003：约束冲突必须解释（机构维度不允许 + 联系人属禁止字段）
        long id3 = createResponse(Map.of(
                "inquiryType", "COMPLEX", "questionCodes", List.of("Q-003"),
                "responseDate", "2026-11-15T18:00:00+08:00", "createdBy", "验收测试"));
        Map<String, Object> d3 = detail(id3);
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) ((Map<String, Object>) d3.get("response")).get("constraintConflicts");
        assertEquals(2, conflicts.size());
        assertTrue(conflicts.stream().anyMatch(c -> "DIMENSION_NOT_ALLOWED".equals(c.get("type"))));
        assertTrue(conflicts.stream().anyMatch(c -> "MEASURE_FORBIDDEN".equals(c.get("type"))));

        // 复杂问询 Q-004：最细可行聚合 + 等价解（区域级 vs 全国级信息损失相同）
        long id4 = createResponse(Map.of(
                "inquiryType", "COMPLEX", "questionCodes", List.of("Q-004"),
                "responseDate", "2026-11-15T18:00:00+08:00", "createdBy", "验收测试"));
        Map<String, Object> d4 = detail(id4);
        Map<String, Object> resp4 = (Map<String, Object>) d4.get("response");
        assertEquals(List.of("region", "evidenceGrade"), resp4.get("dimensions"), "应选定最细维度");
        assertEquals(3.0, num(resp4.get("informationLoss")), 1e-6);
        List<Map<String, Object>> equivalents = (List<Map<String, Object>>) resp4.get("equivalentSolutions");
        assertEquals(1, equivalents.size(), "应存在一个等价解（上卷全国）");
        assertTrue(((List<String>) equivalents.get(0).get("dimensions")).contains("national"));
        assertTrue(String.valueOf(equivalents.get(0).get("explanation")).contains("等价"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void authorizationRevocation_invalidatesDownloadAndRegenerate_butKeepsAudit() {
        long id = createResponse(Map.of(
                "inquiryType", "TEMPLATE", "templateCode", "TPL-REG-GRADE",
                "questionCodes", List.of("Q-001"),
                "responseDate", "2026-11-15T18:00:00+08:00", "createdBy", "验收测试"));

        // 发放授权 → 下载成功
        Map<String, Object> auth = rest.postForObject("/api/regulatory/responses/" + id + "/authorizations",
                Map.of("grantee", "监管对接人", "createdBy", "验收测试"), Map.class);
        String authCode = (String) auth.get("authCode");
        ResponseEntity<Map> dl = rest.getForEntity(
                "/api/regulatory/responses/" + id + "/download?authCode=" + authCode, Map.class);
        assertEquals(200, dl.getStatusCode().value());

        // 授权收回 → 之后下载与重新生成一律 403
        rest.postForObject("/api/regulatory/authorizations/" + authCode + "/revoke",
                Map.of("reason", "监管授权收回", "actor", "验收测试"), Map.class);
        ResponseEntity<Map> dl2 = rest.getForEntity(
                "/api/regulatory/responses/" + id + "/download?authCode=" + authCode, Map.class);
        assertEquals(403, dl2.getStatusCode().value());
        ResponseEntity<Map> regenDenied = rest.postForEntity("/api/regulatory/responses/" + id + "/regenerate",
                Map.of("authCode", authCode, "createdBy", "验收测试"), Map.class);
        assertEquals(403, regenDenied.getStatusCode().value());

        // 审计轨迹保留：发放、下载、收回全部可查
        List<Map<String, Object>> audit = rest.getForObject("/api/regulatory/audit?responseId=" + id, List.class);
        assertTrue(audit.stream().anyMatch(a -> "AUTH_GRANTED".equals(a.get("action"))));
        assertTrue(audit.stream().anyMatch(a -> "DOWNLOAD".equals(a.get("action"))));
        assertTrue(audit.stream().anyMatch(a -> "AUTH_REVOKED".equals(a.get("action"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void regenerate_withValidAuthorization_supersedesOldResponse() {
        long id = createResponse(Map.of(
                "inquiryType", "COMPLEX", "questionCodes", List.of("Q-004"),
                "responseDate", "2026-11-15T18:00:00+08:00", "createdBy", "验收测试"));
        Map<String, Object> auth = rest.postForObject("/api/regulatory/responses/" + id + "/authorizations",
                Map.of("grantee", "监管对接人", "createdBy", "验收测试"), Map.class);
        Map<String, Object> regen = rest.postForObject("/api/regulatory/responses/" + id + "/regenerate",
                Map.of("authCode", auth.get("authCode"), "createdBy", "验收测试"), Map.class);
        long newId = ((Number) regen.get("responseId")).longValue();
        assertEquals("SUPERSEDED", ((Map<String, Object>) detail(id).get("response")).get("status"));
        assertEquals("GENERATED", ((Map<String, Object>) detail(newId).get("response")).get("status"));
        List<Map<String, Object>> audit = rest.getForObject("/api/regulatory/audit?responseId=" + id, List.class);
        assertTrue(audit.stream().anyMatch(a -> "REGENERATED".equals(a.get("action"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sourceRevocation_cancelsGeneration() {
        long id = createResponse(Map.of(
                "inquiryType", "COMPLEX", "questionCodes", List.of("Q-004"),
                "responseDate", "2026-11-15T18:00:00+08:00", "createdBy", "验收测试"));
        assertEquals("GENERATED", ((Map<String, Object>) detail(id).get("response")).get("status"));

        // 来源版本撤销 → 取消生成
        rest.postForObject("/api/source-packages/INS-PKG-2/revoke",
                Map.of("reason", "来源方撤销该修订", "actor", "验收测试"), Map.class);
        Map<String, Object> resp = (Map<String, Object>) detail(id).get("response");
        assertEquals("CANCELLED", resp.get("status"));
        assertTrue(String.valueOf(resp.get("cancelledReason")).contains("INS-PKG-2"));

        // 审计轨迹含来源撤销与取消生成
        List<Map<String, Object>> audit = rest.getForObject("/api/regulatory/audit?responseId=" + id, List.class);
        assertTrue(audit.stream().anyMatch(a -> "SOURCE_REVOKED".equals(a.get("action"))));
        assertTrue(audit.stream().anyMatch(a -> "RESPONSE_CANCELLED".equals(a.get("action"))));

        // 已取消的答复不可下载
        ResponseEntity<Map> dl = rest.getForEntity("/api/regulatory/responses/" + id + "/download?authCode=AUTH-X", Map.class);
        assertTrue(dl.getStatusCode().value() == 409 || dl.getStatusCode().value() == 403);
    }
}
