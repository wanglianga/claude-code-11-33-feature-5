package com.trace.recall.release;

import com.trace.recall.config.Json;
import com.trace.recall.config.Rows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 范围发布器：来源断言与发布范围分别版本化。
 * 检验更正/新凭证/解析修订只生成新候选版本，旧版本只读保留（SUPERSEDED），已发布结果不被改写。
 */
@Service
public class ReleaseService {

    private final JdbcTemplate jdbc;

    public ReleaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> publish(long runId) {
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM verification_run WHERE id = ?", runId);
        if ("PUBLISHED".equals(run.get("status"))) throw new IllegalStateException("本核验已发布，不能重复发布");
        Map<String, Object> snapshot = Json.readMap(String.valueOf(run.get("input_snapshot")));

        // 当前生效发布（前驱）
        List<Map<String, Object>> current = jdbc.queryForList(
                "SELECT * FROM release_version WHERE status = 'PUBLISHED' ORDER BY version_no DESC LIMIT 1");
        Map<String, Object> predecessor = current.isEmpty() ? null : current.get(0);

        int versionNo = predecessor == null ? 1 : ((Number) predecessor.get("version_no")).intValue() + 1;
        long releaseId = jdbc.queryForObject(
                "INSERT INTO release_version(release_code, version_no, run_id, status, predecessor_release_code, basis) VALUES ('PENDING', ?, ?, 'PUBLISHED', ?, '{}'::jsonb) RETURNING id",
                Long.class, versionNo, runId, predecessor == null ? null : predecessor.get("release_code"));
        String releaseCode = String.format("REL-%03d", releaseId);
        jdbc.update("UPDATE release_version SET release_code = ? WHERE id = ?", releaseCode, releaseId);

        // ---- 发布项：成品批次 × 机构/节点，数量按节点分摊 ----
        List<Map<String, Object>> quantities = jdbc.queryForList(
                "SELECT q.* FROM quantity_state q JOIN batch_master b ON q.batch_code = b.batch_code "
                        + "WHERE q.run_id = ? AND b.batch_type = 'FINISHED' ORDER BY q.batch_code", runId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> q : quantities) {
            String batch = (String) q.get("batch_code");
            double confirmed = ((Number) q.get("confirmed_lower")).doubleValue();
            double pending = ((Number) q.get("pending_qty")).doubleValue();
            List<Map<String, Object>> shipments = jdbc.queryForList(
                    "SELECT downstream_batch, quantity FROM lineage_edge WHERE run_id = ? AND relation_type='SHIPMENT' AND upstream_batch = ? ORDER BY edge_code",
                    runId, batch);
            double totalShipped = shipments.stream().mapToDouble(s -> ((Number) s.get("quantity")).doubleValue()).sum();
            if (shipments.isEmpty() || totalShipped <= 0) {
                items.add(item(releaseId, batch, null, confirmed, pending, (String) q.get("unit"), runId));
            } else {
                for (Map<String, Object> s : shipments) {
                    double share = ((Number) s.get("quantity")).doubleValue() / totalShipped;
                    items.add(item(releaseId, batch, (String) s.get("downstream_batch"),
                            confirmed * share, pending * share, (String) q.get("unit"), runId));
                }
            }
        }
        for (Map<String, Object> it : items) {
            jdbc.update("INSERT INTO release_item(release_id, batch_code, node_name, confirmed_qty, pending_qty, possible_qty, lower_bound, upper_bound, unit, evidence_grade, trace_link, conflict_flag) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    releaseId, it.get("batchCode"), it.get("nodeName"), it.get("confirmedQty"), it.get("pendingQty"),
                    it.get("possibleQty"), it.get("lowerBound"), it.get("upperBound"), it.get("unit"),
                    it.get("evidenceGrade"), it.get("traceLink"), it.get("conflictFlag"));
        }

        // ---- 差异：三类变化可区分 ----
        List<Map<String, Object>> diffDetail = new ArrayList<>();
        List<String> diffTypes = new ArrayList<>();
        List<Map<String, Object>> affected = new ArrayList<>();
        if (predecessor == null) {
            diffTypes.add("NONE");
            diffDetail.add(Map.of("type", "NONE", "description", "首次发布"));
        } else {
            Map<String, Object> prevRun = jdbc.queryForMap(
                    "SELECT * FROM verification_run WHERE id = ?", predecessor.get("run_id"));
            Map<String, Object> prevSnapshot = Json.readMap(String.valueOf(prevRun.get("input_snapshot")));

            if (!Objects.equals(run.get("inspection_correction_version"), prevRun.get("inspection_correction_version"))) {
                diffTypes.add("INSPECTION_CHANGED");
                diffDetail.add(Map.of("type", "INSPECTION_CHANGED", "description", "检验结论变了",
                        "from", prevRun.get("inspection_correction_version"), "to", run.get("inspection_correction_version")));
            }
            Set<String> prevPkgs = snapshotPackageCodes(prevSnapshot);
            Set<String> nowPkgs = snapshotPackageCodes(snapshot);
            Set<String> newPkgs = new LinkedHashSet<>(nowPkgs);
            newPkgs.removeAll(prevPkgs);
            List<String> newLogistics = new ArrayList<>();
            List<String> newOther = new ArrayList<>();
            for (String code : newPkgs) {
                String system = jdbc.queryForObject("SELECT source_system FROM source_package WHERE package_code = ?", String.class, code);
                if ("LOGISTICS".equals(system) || "INSTITUTION".equals(system)) newLogistics.add(code);
                else newOther.add(code);
            }
            if (!newLogistics.isEmpty()) {
                diffTypes.add("NEW_LOGISTICS_EVIDENCE");
                diffDetail.add(Map.of("type", "NEW_LOGISTICS_EVIDENCE", "description", "新物流/机构凭证补到（迟到回报只进入新版本）",
                        "packages", newLogistics));
            }
            // 解析规则修订：两次截止之间有新解析规则生效，或包的解析状态由 PARSED 变为 PARSE_DIFF
            boolean parseRevised = jdbc.queryForObject(
                    "SELECT count(*) FROM parse_rule WHERE effective_at > ? AND effective_at <= ?", Integer.class,
                    prevRun.get("cutoff_at"), run.get("cutoff_at")) > 0;
            if (parseRevised) {
                diffTypes.add("PARSE_RULE_REVISED");
                diffDetail.add(Map.of("type", "PARSE_RULE_REVISED", "description", "解析规则修订：哈希有效但字段语义变化，需重新审阅关系边，不回填旧范围"));
            }
            if (!newOther.isEmpty()) {
                diffTypes.add("NEW_SOURCE_PACKAGE");
                diffDetail.add(Map.of("type", "NEW_SOURCE_PACKAGE", "description", "新增来源包", "packages", newOther));
            }
            if (diffTypes.isEmpty()) {
                diffTypes.add("NONE");
                diffDetail.add(Map.of("type", "NONE", "description", "输入依据无变化"));
            }

            // 受影响结论变化（批次集合与数量区间）
            Map<String, Map<String, Object>> prevQ = quantitiesByBatch((Long) predecessor.get("run_id"));
            Map<String, Map<String, Object>> nowQ = quantitiesByBatch(runId);
            for (String b : nowQ.keySet()) {
                if (!prevQ.containsKey(b)) {
                    affected.add(Map.of("batchCode", b, "changeType", "NEW_AFFECTED_BATCH",
                            "previous", "", "current", intervalOf(nowQ.get(b))));
                } else if (!sameInterval(prevQ.get(b), nowQ.get(b))) {
                    affected.add(Map.of("batchCode", b, "changeType", "QUANTITY_INTERVAL_CHANGED",
                            "previous", intervalOf(prevQ.get(b)), "current", intervalOf(nowQ.get(b))));
                }
            }
            for (String b : prevQ.keySet()) {
                if (!nowQ.containsKey(b)) {
                    affected.add(Map.of("batchCode", b, "changeType", "NO_LONGER_AFFECTED",
                            "previous", intervalOf(prevQ.get(b)), "current", ""));
                }
            }
        }

        String diffType = diffTypes.size() == 1 ? diffTypes.get(0) : "MIXED:" + String.join("+", diffTypes);
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("correctionVersion", run.get("inspection_correction_version"));
        basis.put("ruleVersion", run.get("rule_version"));
        basis.put("cutoffAt", Objects.toString(run.get("cutoff_at")));
        basis.put("packages", snapshot.get("packages"));
        jdbc.update("UPDATE release_version SET diff_type = ?, diff_detail = ?::jsonb, basis = ?::jsonb, affected_conclusions = ?::jsonb WHERE id = ?",
                diffType, Json.write(diffDetail), Json.write(basis), Json.write(affected), releaseId);

        // 旧版本只读保留
        if (predecessor != null) {
            jdbc.update("UPDATE release_version SET status = 'SUPERSEDED' WHERE id = ?", predecessor.get("id"));
            jdbc.update("UPDATE verification_run SET status = 'SUPERSEDED' WHERE id = ?", predecessor.get("run_id"));
        }
        jdbc.update("UPDATE verification_run SET status = 'PUBLISHED' WHERE id = ?", runId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("releaseId", releaseId);
        out.put("releaseCode", releaseCode);
        out.put("versionNo", versionNo);
        out.put("diffType", diffType);
        out.put("items", items.size());
        return out;
    }

    private Map<String, Object> item(long releaseId, String batch, String node, double confirmed, double pending,
                                     String unit, long runId) {
        double c = round3(confirmed);
        double p = round3(pending);
        String grade = c > 0 && p == 0 ? "CONFIRMED" : (c == 0 && p > 0 ? "POSSIBLE" : "PENDING");
        boolean conflict = node != null && !jdbc.queryForList(
                "SELECT id FROM evidence_gap WHERE run_id = ? AND gap_type = 'QUANTITY_CONFLICT' AND subject_code = ? AND node_name = ?",
                runId, batch, node).isEmpty();
        Map<String, Object> it = new LinkedHashMap<>();
        it.put("batchCode", batch);
        it.put("nodeName", node);
        it.put("confirmedQty", c);
        it.put("pendingQty", p);
        it.put("possibleQty", round3(c + p));
        it.put("lowerBound", c);
        it.put("upperBound", round3(c + p));
        it.put("unit", unit);
        it.put("evidenceGrade", grade);
        it.put("traceLink", "/runs/" + runId + "/batches/" + batch);
        it.put("conflictFlag", conflict);
        return it;
    }

    private Set<String> snapshotPackageCodes(Map<String, Object> snapshot) {
        Set<String> codes = new LinkedHashSet<>();
        Object packages = snapshot.get("packages");
        if (packages instanceof List<?> list) {
            for (Object p : list) {
                if (p instanceof Map<?, ?> m) codes.add(Objects.toString(m.get("packageCode")));
            }
        }
        return codes;
    }

    private Map<String, Map<String, Object>> quantitiesByBatch(long runId) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> q : jdbc.queryForList("SELECT * FROM quantity_state WHERE run_id = ?", runId)) {
            map.put((String) q.get("batch_code"), q);
        }
        return map;
    }

    private boolean sameInterval(Map<String, Object> a, Map<String, Object> b) {
        return Objects.equals(a.get("confirmed_lower"), b.get("confirmed_lower"))
                && Objects.equals(a.get("pending_qty"), b.get("pending_qty"))
                && Objects.equals(a.get("possible_upper"), b.get("possible_upper"));
    }

    private Map<String, Object> intervalOf(Map<String, Object> q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("confirmedLower", q.get("confirmed_lower"));
        m.put("pendingQty", q.get("pending_qty"));
        m.put("possibleUpper", q.get("possible_upper"));
        m.put("unattributedDelta", q.get("unattributed_delta"));
        return m;
    }

    public List<Map<String, Object>> listReleases() {
        List<Map<String, Object>> releases = Rows.camelize(jdbc.queryForList("SELECT * FROM release_version ORDER BY version_no"),
                "diffDetail", "basis", "affectedConclusions");
        for (Map<String, Object> r : releases) {
            long id = ((Number) r.get("id")).longValue();
            r.put("items", Rows.camelize(jdbc.queryForList("SELECT * FROM release_item WHERE release_id = ? ORDER BY batch_code, node_name", id)));
        }
        return releases;
    }

    /** 外部处置系统视图：仅白名单字段，原始载荷与敏感字段不出现。 */
    public List<Map<String, Object>> externalReleases() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM release_version ORDER BY version_no")) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("releaseCode", r.get("release_code"));
            v.put("versionNo", r.get("version_no"));
            v.put("status", r.get("status"));
            v.put("diffType", r.get("diff_type"));
            v.put("publishedAt", r.get("published_at"));
            out.add(v);
        }
        return out;
    }

    public Map<String, Object> externalSummary(String releaseCode) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM release_version WHERE release_code = ?", releaseCode);
        long id = ((Number) r.get("id")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("releaseCode", r.get("release_code"));
        out.put("versionNo", r.get("version_no"));
        out.put("status", r.get("status"));
        out.put("publishedAt", r.get("published_at"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> it : jdbc.queryForList("SELECT * FROM release_item WHERE release_id = ? ORDER BY batch_code, node_name", id)) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("batchCode", it.get("batch_code"));
            v.put("nodeName", it.get("node_name"));
            v.put("confirmedQty", it.get("confirmed_qty"));
            v.put("pendingQty", it.get("pending_qty"));
            v.put("possibleQty", it.get("possible_qty"));
            v.put("lowerBound", it.get("lower_bound"));
            v.put("upperBound", it.get("upper_bound"));
            v.put("unit", it.get("unit"));
            v.put("evidenceGrade", it.get("evidence_grade"));
            v.put("traceLink", it.get("trace_link"));
            v.put("conflictFlag", it.get("conflict_flag"));
            items.add(v);
        }
        out.put("items", items);
        return out;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
