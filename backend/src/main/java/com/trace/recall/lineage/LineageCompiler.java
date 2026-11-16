package com.trace.recall.lineage;

import com.trace.recall.config.Json;
import com.trace.recall.config.Rows;
import com.trace.recall.ingestion.IngestionService;
import com.trace.recall.ingestion.RawFact;
import com.trace.recall.ingestion.SourcePackage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 谱系编译器：把来源事实编译成有向关系边（每次核验独立编译，不可变快照）。
 * 重复箱码 → 两条运输边都降级为重复载体证据；解析差异只降级受影响事实类型的边。
 */
@Service
public class LineageCompiler {

    private final JdbcTemplate jdbc;
    private final IngestionService ingestion;

    public LineageCompiler(JdbcTemplate jdbc, IngestionService ingestion) {
        this.jdbc = jdbc;
        this.ingestion = ingestion;
    }

    public void compile(long runId, List<SourcePackage> packages, List<RawFact> facts, OffsetDateTime cutoff) {
        Map<Long, SourcePackage> pkgById = new LinkedHashMap<>();
        for (SourcePackage p : packages) pkgById.put(p.id(), p);

        // 每个包在本次核验时点的解析状态（快照语义）
        Map<Long, String> parseStatus = new HashMap<>();
        Map<Long, Set<String>> pkgFactTypes = new HashMap<>();
        for (RawFact f : facts) pkgFactTypes.computeIfAbsent(f.packageId(), k -> new HashSet<>()).add(f.factType());
        for (SourcePackage p : packages) {
            parseStatus.put(p.id(), ingestion.parseStatusAt(p.parseRuleVersion(),
                    pkgFactTypes.getOrDefault(p.id(), Set.of()), cutoff));
        }

        // 重复箱码检测：同一箱码出现在多条互不相连运输记录
        Map<String, List<RawFact>> byBox = new LinkedHashMap<>();
        for (RawFact f : facts) {
            if ("LOGISTICS".equals(f.factType()) && f.payload().get("boxCode") != null) {
                byBox.computeIfAbsent(f.payload().get("boxCode").toString(), k -> new ArrayList<>()).add(f);
            }
        }
        Set<String> duplicateBoxes = new HashSet<>();
        byBox.forEach((box, list) -> {
            Set<String> batches = new HashSet<>();
            list.forEach(f -> batches.add((String) f.payload().get("batch")));
            if (list.size() > 1 && batches.size() > 1) duplicateBoxes.add(box);
        });

        for (RawFact f : facts) {
            SourcePackage pkg = pkgById.get(f.packageId());
            if (pkg == null) continue;
            Map<String, Object> p = f.payload();
            switch (f.factType()) {
                case "FEEDING", "SPLIT", "BLEND", "RELEASE" -> {
                    List<String> issues = new ArrayList<>();
                    if (p.get("voucher") == null && "FEEDING".equals(f.factType())) issues.add("MISSING_FEEDING_EVIDENCE");
                    insertEdge(runId, f.factCode(), (String) p.get("upstream"), (String) p.get("downstream"),
                            f.factType(), f, pkg, issues, Map.of());
                }
                case "LOGISTICS" -> {
                    List<String> issues = new ArrayList<>();
                    String box = Objects.toString(p.get("boxCode"), null);
                    if (box != null && duplicateBoxes.contains(box)) issues.add("DUPLICATE_BOX");
                    if ("PARSE_DIFF".equals(parseStatus.get(pkg.id()))) issues.add("PARSE_DIFF");
                    if (p.get("productionLot") != null) {
                        String pl = p.get("productionLot").toString();
                        Integer known = jdbc.queryForObject("SELECT count(*) FROM batch_master WHERE batch_code = ?", Integer.class, pl);
                        if (known == null || known == 0) issues.add("UNMATCHED_BATCH");
                    }
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("boxCode", box);
                    detail.put("node", p.get("node"));
                    detail.put("signedQty", p.get("signedQty"));
                    insertEdge(runId, f.factCode(), (String) p.get("batch"), (String) p.get("node"),
                            "SHIPMENT", f, pkg, issues, detail);
                }
                default -> { /* INSPECTION / MASTER_DATA / INSTITUTION_REPORT 不产生谱系边 */ }
            }
        }
    }

    private void insertEdge(long runId, String edgeCode, String upstream, String downstream, String relationType,
                            RawFact f, SourcePackage pkg, List<String> issues, Map<String, Object> detail) {
        String confidence = issues.isEmpty() ? "CONFIRMED" : "SUSPECTED";
        String blockReason = issues.isEmpty() ? null : String.join("+", issues);
        Object qty = f.payload().get("qty");
        jdbc.update("INSERT INTO lineage_edge(run_id, edge_code, upstream_batch, downstream_batch, relation_type, source_fact_code, source_package_code, valid_from, confidence_status, review_required, block_reason, parse_rule_version, quantity, unit, edge_detail) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                runId, edgeCode, upstream, downstream, relationType, f.factCode(), pkg.packageCode(), f.eventTime(),
                confidence, !issues.isEmpty(), blockReason, pkg.parseRuleVersion(),
                qty == null ? null : ((Number) qty).doubleValue(), (String) f.payload().get("unit"),
                Json.write(detail));
    }

    public List<Map<String, Object>> edgesOfRun(long runId) {
        return Rows.camelize(jdbc.queryForList("SELECT * FROM lineage_edge WHERE run_id = ? ORDER BY id", runId), "edgeDetail");
    }
}
