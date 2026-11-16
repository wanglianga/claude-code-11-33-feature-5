package com.trace.recall;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 基础域端到端：六条强制规则、两次核验与发布差异、外部接口脱敏。 */
class RecallServiceE2ETest extends BaseE2ESupport {

    @Test
    @SuppressWarnings("unchecked")
    void fullBaseFlow() {
        // ---------- 核验 #1：QC-REV-1，截止 2026-11-10 18:00 ----------
        long run1 = createRun("QC-REV-1", "2026-11-10T18:00:00+08:00");
        Map<String, Object> d1 = runDetail(run1);
        assertEquals(6, ((List<Object>) d1.get("packages")).size(), "截止 #1 应锁定 6 个来源包");

        // 1) 身份歧义：RM-A02 双供应商批号保留 ≥2 歧义候选，且谱系中不存在供应商批号合并边
        List<Map<String, Object>> identity = (List<Map<String, Object>>) d1.get("identityRecords");
        long ambiguousCandidates = identity.stream()
                .flatMap(r -> ((List<Map<String, Object>>) r.get("candidates")).stream())
                .filter(c -> Boolean.TRUE.equals(c.get("ambiguous"))).count();
        assertTrue(ambiguousCandidates >= 2, "歧义候选应 ≥2");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) d1.get("edges");
        assertTrue(edges.stream().noneMatch(e -> "SUP-LOT-22".equals(e.get("upstreamBatch"))
                || "SUP-LOT-23".equals(e.get("upstreamBatch"))), "谱系中不得出现供应商批号合并边");

        // 2) RM-A04 超额投料：确认下界 60、未归因差 15；冻结边 ≥3；孙代 FG-C03 确认量为 0
        List<Map<String, Object>> quantities = (List<Map<String, Object>>) d1.get("quantities");
        Map<String, Object> rmA04 = findBy(quantities, "batchCode", "RM-A04");
        assertEquals(60.0, num(rmA04.get("confirmedLower")), 1e-6);
        assertEquals(15.0, num(rmA04.get("unattributedDelta")), 1e-6);
        long frozen = edges.stream().filter(e -> "FROZEN".equals(e.get("confidenceStatus"))).count();
        assertTrue(frozen >= 3, "冻结边应 ≥3（FEED-02/FEED-03/REL-02/REL-03）");
        assertEquals(0.0, num(findBy(quantities, "batchCode", "FG-C03").get("confirmedLower")), 1e-6);

        // 3) 数量冲突：HOSP-乙 节点同时保留物流 30 与机构 46 两个数字
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) d1.get("gaps");
        assertTrue(gaps.stream().anyMatch(g -> "QUANTITY_CONFLICT".equals(g.get("gapType"))
                && "FG-C02".equals(g.get("subjectCode"))));
        Map<String, Object> trace = rest.getForObject("/api/runs/" + run1 + "/batches/FG-C02/trace", Map.class);
        Map<String, Object> quantity = (Map<String, Object>) trace.get("quantity");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) ((Map<String, Object>) quantity.get("detail")).get("nodes");
        Map<String, Object> nodeYi = findBy(nodes, "node", "HOSP-乙");
        assertEquals(30.0, num(nodeYi.get("logisticsSignedQty")), 1e-6);
        assertEquals(46.0, num(nodeYi.get("institutionReceivedQty")), 1e-6);
        assertEquals(Boolean.TRUE, nodeYi.get("quantityConflict"));

        // 4) BOX-9 重复：两条运输边均降级 SUSPECTED
        assertEquals("SUSPECTED", findBy(edges, "edgeCode", "SHIP-02").get("confidenceStatus"));
        assertEquals("SUSPECTED", findBy(edges, "edgeCode", "SHIP-03").get("confidenceStatus"));
        assertTrue(gaps.stream().anyMatch(g -> "DUPLICATE_BOX".equals(g.get("gapType"))));

        // 5) 截止 #1 在 PARSE-2.0 生效前：不应出现 PARSE_DIFF 缺口
        assertTrue(gaps.stream().noneMatch(g -> "PARSE_DIFF".equals(g.get("gapType"))));

        // ---------- 核验 #2：QC-REV-2，截止 2026-11-14 18:00 ----------
        long run2 = createRun("QC-REV-2", "2026-11-14T18:00:00+08:00");
        Map<String, Object> d2 = runDetail(run2);
        assertEquals(8, ((List<Object>) d2.get("packages")).size(), "截止 #2 应锁定 8 个来源包");
        List<Map<String, Object>> gaps2 = (List<Map<String, Object>>) d2.get("gaps");
        assertTrue(gaps2.stream().anyMatch(g -> "PARSE_DIFF".equals(g.get("gapType"))),
                "PARSE-2.0 于 11-11 生效后，按 1.9 解析的物流包应标记解析差异");
        List<Map<String, Object>> edges2 = (List<Map<String, Object>>) d2.get("edges");
        // PARSE_DIFF 只降级 LOGISTICS 边；MES 的 FEEDING 边不得受影响
        assertTrue(edges2.stream().filter(e -> "FEEDING".equals(e.get("relationType")))
                .noneMatch(e -> String.valueOf(e.get("blockReason")).contains("PARSE_DIFF")));
        assertTrue(String.valueOf(findBy(edges2, "edgeCode", "SHIP-01").get("blockReason")).contains("PARSE_DIFF"));
        // V2 新增受影响：RM-A01 → IM-B40 → FG-C04 确认 50
        List<Map<String, Object>> quantities2 = (List<Map<String, Object>>) d2.get("quantities");
        assertEquals(50.0, num(findBy(quantities2, "batchCode", "FG-C04").get("confirmedLower")), 1e-6);

        // ---------- 发布两个版本 ----------
        publish(run1);
        publish(run2);
        List<Map<String, Object>> releases = rest.getForObject("/api/releases", List.class);
        assertEquals(2, releases.size());
        Map<String, Object> rel1 = releases.get(0);
        Map<String, Object> rel2 = releases.get(1);
        assertEquals("SUPERSEDED", rel1.get("status"), "旧版本只读保留");
        assertEquals("PUBLISHED", rel2.get("status"));
        String diffType = (String) rel2.get("diffType");
        assertTrue(diffType.startsWith("MIXED"), "三类变化应同时可区分: " + diffType);
        assertTrue(diffType.contains("INSPECTION_CHANGED"));
        assertTrue(diffType.contains("NEW_LOGISTICS_EVIDENCE"));
        assertTrue(diffType.contains("PARSE_RULE_REVISED"));
        List<Map<String, Object>> conclusions = (List<Map<String, Object>>) rel2.get("affectedConclusions");
        assertTrue(conclusions.stream().anyMatch(c -> "RM-A01".equals(c.get("batchCode"))
                && "NEW_AFFECTED_BATCH".equals(c.get("changeType"))));
        assertTrue(conclusions.stream().anyMatch(c -> "FG-C01".equals(c.get("batchCode"))
                && "NO_LONGER_AFFECTED".equals(c.get("changeType"))));

        // ---------- 外部接口：无 Key/错 Key 401；正确 Key 仅白名单字段 ----------
        ResponseEntity<String> noKey = rest.getForEntity("/external/releases", String.class);
        assertEquals(401, noKey.getStatusCode().value());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "wrong-key");
        ResponseEntity<String> wrong = rest.exchange("/external/releases", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(401, wrong.getStatusCode().value());

        headers.set("X-API-Key", "ext-trace-key-2026");
        ResponseEntity<Map> ok = rest.exchange("/external/releases/REL-002", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertEquals(200, ok.getStatusCode().value());
        String body = ok.getBody().toString();
        for (String forbidden : List.of("payload", "sensitiveContacts", "contactPerson", "phone", "wardContact", "driverPhone")) {
            assertFalse(body.contains(forbidden), "外部响应不得包含敏感字段: " + forbidden);
        }
        Set<String> whitelist = Set.of("batchCode", "nodeName", "confirmedQty", "pendingQty", "possibleQty",
                "lowerBound", "upperBound", "unit", "evidenceGrade", "traceLink", "conflictFlag");
        List<Map<String, Object>> items = (List<Map<String, Object>>) ok.getBody().get("items");
        assertFalse(items.isEmpty());
        for (Map<String, Object> item : items) {
            assertTrue(whitelist.containsAll(item.keySet()), "发布项仅允许白名单字段: " + item.keySet());
        }
        Set<String> grades = new HashSet<>();
        items.forEach(i -> grades.add((String) i.get("evidenceGrade")));
        assertTrue(grades.contains("CONFIRMED") && grades.contains("POSSIBLE"), "证据等级应覆盖确认与可能两类");
    }
}
