package com.trace.recall.quality;

import com.trace.recall.config.Json;
import com.trace.recall.ingestion.RawFact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 数量质量引擎：确认量下界 / 待核量 / 可能受影响上界 / 未能归因差额。
 * - 拆分投料合计超过原料可用量 → 冻结整条分配链确认量，冻结沿 BFS 传播到全部下游代际；
 * - 机构回报与物流签收不一致且双凭证有效 → 数量冲突，两个数字都保留，任一来源不得覆盖另一来源；
 * - 待核/缺凭证/解析差异的边只贡献待核量，不贡献确认量。
 */
@Service
public class QuantityQualityEngine {

    private final JdbcTemplate jdbc;

    public QuantityQualityEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Edge(long id, String code, String upstream, String downstream, String relation,
                        String confidence, String blockReason, double qty) {}

    public void compute(long runId, List<RawFact> facts) {
        // ---- 有效检验结论：按事件时间顺序应用（同一批次后到的更正覆盖先到结论） ----
        Map<String, String> inspection = new LinkedHashMap<>();
        facts.stream().filter(f -> "INSPECTION".equals(f.factType()))
                .sorted(Comparator.comparing(RawFact::eventTime))
                .forEach(f -> inspection.put((String) f.payload().get("batchCode"), (String) f.payload().get("result")));
        Set<String> affectedRaws = new LinkedHashSet<>();
        inspection.forEach((b, r) -> { if ("FAIL".equals(r)) affectedRaws.add(b); });

        // ---- 批次主数据 ----
        Map<String, Map<String, Object>> masters = new LinkedHashMap<>();
        jdbc.queryForList("SELECT * FROM batch_master").forEach(m -> masters.put((String) m.get("batch_code"), m));

        // ---- 谱系边 ----
        List<Edge> edges = jdbc.query("SELECT * FROM lineage_edge WHERE run_id = ? ORDER BY id", (rs, i) -> new Edge(
                rs.getLong("id"), rs.getString("edge_code"), rs.getString("upstream_batch"), rs.getString("downstream_batch"),
                rs.getString("relation_type"), rs.getString("confidence_status"), rs.getString("block_reason"),
                rs.getObject("quantity") == null ? 0 : rs.getDouble("quantity")), runId);

        // ---- 超额投料：拆分合计 > 原料可用量 → 冻结分配链（BFS 传播到全部下游代际） ----
        Map<String, Double> feedingOut = new LinkedHashMap<>();
        for (Edge e : edges) {
            if ("FEEDING".equals(e.relation()) || "SPLIT".equals(e.relation())) {
                feedingOut.merge(e.upstream(), e.qty(), Double::sum);
            }
        }
        Set<String> frozenEdges = new HashSet<>();
        Set<String> tainted = new HashSet<>();
        for (Map.Entry<String, Double> en : feedingOut.entrySet()) {
            String raw = en.getKey();
            Map<String, Object> master = masters.get(raw);
            if (master == null) continue;
            double available = ((Number) master.get("quantity")).doubleValue();
            if (en.getValue() > available && affectedRaws.contains(raw)) {
                double delta = en.getValue() - available;
                insertGap(runId, "OVER_FEEDING", "BLOCKER", raw, null,
                        "拆分投料合计 " + en.getValue() + "kg 超过原料可用量 " + available + "kg，冻结整条分配链确认量",
                        "阻断 " + raw + " 下游全部确认量（仅展示下界与超额差额 " + round3(delta) + "kg）",
                        List.of(Map.of("fedTotal", en.getValue(), "available", available, "delta", round3(delta))));
                // 冻结该原料的投料边并沿 BFS 传播
                Deque<String> queue = new ArrayDeque<>();
                for (Edge e : edges) {
                    if (("FEEDING".equals(e.relation()) || "SPLIT".equals(e.relation())) && raw.equals(e.upstream())) {
                        frozenEdges.add(e.code());
                        queue.add(e.downstream());
                    }
                }
                while (!queue.isEmpty()) {
                    String b = queue.poll();
                    if (!tainted.add(b)) continue;
                    for (Edge e : edges) {
                        if (b.equals(e.upstream()) && !"SHIPMENT".equals(e.relation())) {
                            frozenEdges.add(e.code());
                            queue.add(e.downstream());
                        }
                    }
                }
                // 未归因差额记录到原料批次
                jdbc.update("INSERT INTO quantity_state(run_id, batch_code, affected_flag, confirmed_lower, pending_qty, possible_upper, unattributed_delta, unit, detail) VALUES (?,?,TRUE,0,0,0,?,?,'{}'::jsonb) ON CONFLICT (run_id, batch_code) DO UPDATE SET unattributed_delta = EXCLUDED.unattributed_delta",
                        runId, raw, round3(delta), unitOf(master));
            }
        }
        for (Edge e : edges) {
            if (frozenEdges.contains(e.code())) {
                jdbc.update("UPDATE lineage_edge SET confidence_status='FROZEN' WHERE run_id=? AND edge_code=?", runId, e.code());
            }
        }
        // 重新读取冻结后的边
        edges = jdbc.query("SELECT * FROM lineage_edge WHERE run_id = ? ORDER BY id", (rs, i) -> new Edge(
                rs.getLong("id"), rs.getString("edge_code"), rs.getString("upstream_batch"), rs.getString("downstream_batch"),
                rs.getString("relation_type"), rs.getString("confidence_status"), rs.getString("block_reason"),
                rs.getObject("quantity") == null ? 0 : rs.getDouble("quantity")), runId);

        // ---- 数量传播（拓扑序）：CONFIRMED 边携带确认量，SUSPECTED/FROZEN 边只携带待核量 ----
        Map<String, List<Edge>> outEdges = new LinkedHashMap<>();
        Set<String> batchNodes = new LinkedHashSet<>();
        for (Edge e : edges) {
            if ("SHIPMENT".equals(e.relation())) continue;
            outEdges.computeIfAbsent(e.upstream(), k -> new ArrayList<>()).add(e);
            batchNodes.add(e.upstream());
            batchNodes.add(e.downstream());
        }
        Map<String, Double> confirmed = new LinkedHashMap<>();
        Map<String, Double> pending = new LinkedHashMap<>();
        for (String raw : affectedRaws) {
            Map<String, Object> m = masters.get(raw);
            if (m != null) confirmed.put(raw, ((Number) m.get("quantity")).doubleValue());
        }
        for (String b : topoSort(batchNodes, outEdges)) {
            double c = confirmed.getOrDefault(b, 0.0);
            double p = pending.getOrDefault(b, 0.0);
            if (c == 0 && p == 0) continue;
            for (Edge e : outEdges.getOrDefault(b, List.of())) {
                if ("CONFIRMED".equals(e.confidence())) {
                    double carry = Math.min(e.qty(), c);
                    confirmed.merge(e.downstream(), carry, Double::sum);
                    double overflow = e.qty() - carry;
                    if (overflow > 0) pending.merge(e.downstream(), Math.min(overflow, p), Double::sum);
                } else {
                    pending.merge(e.downstream(), Math.min(e.qty(), c + p), Double::sum);
                }
            }
        }
        //  capped by 批量
        Set<String> inScope = new LinkedHashSet<>();
        inScope.addAll(confirmed.keySet());
        inScope.addAll(pending.keySet());
        for (String b : inScope) {
            Map<String, Object> m = masters.get(b);
            if (m == null) continue;
            double qty = ((Number) m.get("quantity")).doubleValue();
            double c = Math.min(confirmed.getOrDefault(b, 0.0), qty);
            double p = Math.min(pending.getOrDefault(b, 0.0), Math.max(0, qty - c));
            if (c == 0 && p == 0) continue;
            confirmed.put(b, c);
            pending.put(b, p);
            upsertQuantity(runId, b, c, p, unitOf(m));
        }

        // ---- 机构/运输节点数量冲突：双凭证并存，任一来源不得覆盖另一来源 ----
        Map<String, RawFact> reportByNodeBatch = new LinkedHashMap<>();
        for (RawFact f : facts) {
            if (!"INSTITUTION_REPORT".equals(f.factType())) continue;
            String key = f.payload().get("institution") + "|" + f.payload().get("batchCode");
            reportByNodeBatch.putIfAbsent(key, f);
        }
        for (Edge e : edges) {
            if (!"SHIPMENT".equals(e.relation())) continue;
            RawFact report = reportByNodeBatch.get(e.downstream() + "|" + e.upstream());
            if (report == null) continue;
            double reported = ((Number) report.payload().get("qty")).doubleValue();
            double signed = e.qty();
            if (Math.abs(reported - signed) > 1e-9) {
                insertGap(runId, "QUANTITY_CONFLICT", "WARNING", e.upstream(), e.downstream(),
                        "机构回报 " + round3(reported) + "kg（" + report.factCode() + "）与物流签收 " + round3(signed)
                                + "kg（" + e.code() + "）不一致，双凭证在截止前均有效，任一来源不得覆盖另一来源",
                        "阻断 " + e.upstream() + " 在节点 " + e.downstream() + " 的数量确认结论",
                        List.of(Map.of("institutionReportQty", round3(reported), "logisticsSignedQty", round3(signed))));
            }
        }

        // ---- 其余证据缺口（来自身份解析与谱系编译的标记） ----
        jdbc.queryForList("SELECT * FROM identity_record WHERE run_id = ? AND match_status = 'AMBIGUOUS' AND code_type = 'SUPPLIER_LOT'", runId)
                .forEach(r -> insertGap(runId, "IDENTITY_AMBIGUITY", "WARNING",
                        (String) r.get("observed_batch_code"), null,
                        "供应商批号 " + r.get("code_value") + " 与另一批号共享内部批次且无可验证凭证，保留双候选不合并",
                        "阻断 " + r.get("observed_batch_code") + " 的供应商级身份确认（数量上界保留扩大）",
                        List.of(Map.of("codeValue", r.get("code_value")))));
        jdbc.queryForList("SELECT * FROM identity_record WHERE run_id = ? AND match_status = 'UNMATCHED'", runId)
                .forEach(r -> insertGap(runId, "UNMATCHED_BATCH", "WARNING",
                        (String) r.get("code_value"), null,
                        "生产批号 " + r.get("code_value") + " 无法匹配任何内部批次，不强行归因",
                        "阻断该批号相关运输事实的链路完整性结论",
                        List.of()));
        Set<String> seenEdgeGaps = new HashSet<>();
        for (Edge e : edges) {
            if (e.blockReason() == null) continue;
            for (String reason : e.blockReason().split("\\+")) {
                String key = reason + "|" + ("DUPLICATE_BOX".equals(reason) ? boxOf(runId, e) : e.code());
                if (!seenEdgeGaps.add(key)) continue;
                switch (reason) {
                    case "MISSING_FEEDING_EVIDENCE" -> insertGap(runId, "MISSING_FEEDING_EVIDENCE", "WARNING", e.code(), null,
                            "投料事实 " + e.code() + "（" + e.upstream() + " → " + e.downstream() + "）缺少投料凭证",
                            "阻断该投料边的确认量贡献（仅计入待核量）", List.of());
                    case "DUPLICATE_BOX" -> {
                        String box = boxOf(runId, e);
                        insertGap(runId, "DUPLICATE_BOX", "WARNING", box, null,
                                "箱码 " + box + " 在两条互不相连运输记录中重复，两条边均降级为重复载体证据",
                                "阻断两条运输边的链路完整性证明", List.of(Map.of("boxCode", box)));
                    }
                    case "PARSE_DIFF" -> insertGap(runId, "PARSE_DIFF", "WARNING", e.code(), null,
                            "来源包哈希有效但解析规则升级导致字段含义变化，需重新审阅关系边，不回填旧范围",
                            "阻断该运输边的确认状态（降级为待核）", List.of(Map.of("edge", e.code())));
                    default -> { }
                }
            }
        }
    }

    private String boxOf(long runId, Edge e) {
        try {
            String json = jdbc.queryForObject("SELECT edge_detail::text FROM lineage_edge WHERE run_id=? AND edge_code=?",
                    String.class, runId, e.code());
            return Objects.toString(Json.readMap(json).get("boxCode"), e.code());
        } catch (Exception ex) {
            return e.code();
        }
    }

    private List<String> topoSort(Set<String> nodes, Map<String, List<Edge>> outEdges) {
        Map<String, Integer> indeg = new HashMap<>();
        for (String n : nodes) indeg.put(n, 0);
        for (List<Edge> list : outEdges.values()) for (Edge e : list) indeg.merge(e.downstream(), 1, Integer::sum);
        Deque<String> queue = new ArrayDeque<>();
        for (String n : nodes) if (indeg.get(n) == 0) queue.add(n);
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String n = queue.poll();
            order.add(n);
            for (Edge e : outEdges.getOrDefault(n, List.of())) {
                if (indeg.merge(e.downstream(), -1, Integer::sum) == 0) queue.add(e.downstream());
            }
        }
        return order;
    }

    private void upsertQuantity(long runId, String batch, double confirmed, double pending, String unit) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("nodes", nodeDetails(runId, batch));
        jdbc.update("INSERT INTO quantity_state(run_id, batch_code, affected_flag, confirmed_lower, pending_qty, possible_upper, unattributed_delta, unit, detail) VALUES (?,?,TRUE,?,?,?,0,?,?::jsonb) "
                        + "ON CONFLICT (run_id, batch_code) DO UPDATE SET confirmed_lower=EXCLUDED.confirmed_lower, pending_qty=EXCLUDED.pending_qty, possible_upper=EXCLUDED.possible_upper, detail=EXCLUDED.detail",
                runId, batch, round3(confirmed), round3(pending), round3(confirmed + pending), unit, Json.write(detail));
    }

    /** 成品批次的机构/运输节点明细（钻取页展示双凭证）。 */
    private List<Map<String, Object>> nodeDetails(long runId, String batch) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM lineage_edge WHERE run_id = ? AND relation_type = 'SHIPMENT' AND upstream_batch = ?", runId, batch);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map<String, Object> e : rows) {
            Map<String, Object> detail = Json.readMap(String.valueOf(e.get("edge_detail")));
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("node", e.get("downstream_batch"));
            node.put("boxCode", detail.get("boxCode"));
            node.put("logisticsSignedQty", e.get("quantity"));
            node.put("logisticsFactCode", e.get("source_fact_code"));
            node.put("logisticsPackage", e.get("source_package_code"));
            node.put("evidenceWeak", !"CONFIRMED".equals(e.get("confidence_status")));
            node.put("weakReason", e.get("block_reason"));
            List<Map<String, Object>> reports = jdbc.queryForList(
                    "SELECT f.* FROM raw_fact f JOIN source_package p ON f.package_id = p.id "
                            + "WHERE f.fact_type='INSTITUTION_REPORT' "
                            + "AND p.package_code IN (SELECT pkg->>'packageCode' FROM verification_run vr, jsonb_array_elements(vr.input_snapshot->'packages') pkg WHERE vr.id=?) "
                            + "AND f.payload->>'institution' = ? AND f.payload->>'batchCode' = ? ORDER BY f.event_time",
                    runId, e.get("downstream_batch"), batch);
            if (!reports.isEmpty()) {
                Map<String, Object> payload = Json.readMap(String.valueOf(reports.get(0).get("payload")));
                node.put("institutionReceivedQty", ((Number) payload.get("qty")).doubleValue());
                node.put("institutionFactCode", reports.get(0).get("fact_code"));
                double signed = ((Number) e.get("quantity")).doubleValue();
                node.put("quantityConflict", Math.abs(((Number) payload.get("qty")).doubleValue() - signed) > 1e-9);
            } else {
                node.put("institutionReceivedQty", null);
                node.put("quantityConflict", false);
            }
            nodes.add(node);
        }
        return nodes;
    }

    private void insertGap(long runId, String type, String severity, String subject, String node,
                           String description, String blocks, List<Map<String, Object>> evidence) {
        jdbc.update("INSERT INTO evidence_gap(run_id, gap_type, severity, subject_code, node_name, description, blocks_conclusion, evidence) VALUES (?,?,?,?,?,?,?,?::jsonb)",
                runId, type, severity, subject, node, description, blocks, Json.write(evidence));
    }

    private String unitOf(Map<String, Object> master) {
        return Objects.toString(master.get("unit"), "kg");
    }

    static double round3(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
