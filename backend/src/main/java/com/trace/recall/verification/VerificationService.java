package com.trace.recall.verification;

import com.trace.recall.config.Json;
import com.trace.recall.config.Rows;
import com.trace.recall.identity.IdentityResolutionService;
import com.trace.recall.ingestion.IngestionService;
import com.trace.recall.ingestion.RawFact;
import com.trace.recall.ingestion.SourcePackage;
import com.trace.recall.lineage.LineageCompiler;
import com.trace.recall.quality.QuantityQualityEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 核验编排：锁定截止时间前的来源包快照（含哈希与解析状态），
 * 在快照上依次执行身份解析、谱系编译、数量质量引擎。后续补录无法混入本次判断。
 */
@Service
public class VerificationService {

    private final JdbcTemplate jdbc;
    private final IngestionService ingestion;
    private final IdentityResolutionService identityService;
    private final LineageCompiler lineageCompiler;
    private final QuantityQualityEngine quantityEngine;
    private final String ruleVersion;

    public VerificationService(JdbcTemplate jdbc, IngestionService ingestion,
                               IdentityResolutionService identityService, LineageCompiler lineageCompiler,
                               QuantityQualityEngine quantityEngine,
                               @Value("${app.rule.current-rule-version:RULE-2026.11}") String ruleVersion) {
        this.jdbc = jdbc;
        this.ingestion = ingestion;
        this.identityService = identityService;
        this.lineageCompiler = lineageCompiler;
        this.quantityEngine = quantityEngine;
        this.ruleVersion = ruleVersion;
    }

    @Transactional
    public Map<String, Object> createRun(String inspectionCorrectionVersion, OffsetDateTime cutoffAt, String createdBy) {
        List<SourcePackage> packages = ingestion.packagesReceivedBefore(cutoffAt);
        if (packages.isEmpty()) throw new IllegalStateException("截止时间 " + cutoffAt + " 前没有可用来源包");
        List<RawFact> facts = ingestion.factsOfPackages(packages.stream().map(SourcePackage::id).toList());

        // 快照：锁定包清单 + 哈希 + 本次核验时点的解析状态
        List<Map<String, Object>> snapshotPackages = new ArrayList<>();
        Map<Long, Set<String>> pkgFactTypes = new HashMap<>();
        for (RawFact f : facts) pkgFactTypes.computeIfAbsent(f.packageId(), k -> new HashSet<>()).add(f.factType());
        for (SourcePackage p : packages) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("packageCode", p.packageCode());
            entry.put("sourceSystem", p.sourceSystem());
            entry.put("sourceRevision", p.sourceRevision());
            entry.put("payloadHash", p.payloadHash());
            entry.put("parseRuleVersion", p.parseRuleVersion());
            entry.put("parseStatus", ingestion.parseStatusAt(p.parseRuleVersion(),
                    pkgFactTypes.getOrDefault(p.id(), Set.of()), cutoffAt));
            entry.put("receivedAt", p.receivedAt().toString());
            snapshotPackages.add(entry);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("cutoffAt", cutoffAt.toString());
        snapshot.put("inspectionCorrectionVersion", inspectionCorrectionVersion);
        snapshot.put("packages", snapshotPackages);

        long runId = jdbc.queryForObject(
                "INSERT INTO verification_run(run_code, inspection_correction_version, cutoff_at, rule_version, status, input_snapshot, created_by) "
                        + "VALUES ('PENDING', ?, ?, ?, 'LOCKED', ?::jsonb, ?) RETURNING id",
                Long.class, inspectionCorrectionVersion, cutoffAt, ruleVersion, Json.write(snapshot), createdBy);
        String runCode = String.format("RUN-%03d", runId);
        jdbc.update("UPDATE verification_run SET run_code = ? WHERE id = ?", runCode, runId);

        identityService.resolve(runId, facts);
        lineageCompiler.compile(runId, packages, facts, cutoffAt);
        quantityEngine.compute(runId, facts);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("runCode", runCode);
        out.put("snapshotPackages", snapshotPackages.size());
        return out;
    }

    public List<Map<String, Object>> listRuns() {
        List<Map<String, Object>> runs = Rows.camelize(jdbc.queryForList(
                "SELECT id, run_code, inspection_correction_version, cutoff_at, rule_version, status, created_by, created_at, input_snapshot FROM verification_run ORDER BY id"),
                "inputSnapshot");
        return runs;
    }

    public Map<String, Object> runDetail(long runId) {
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM verification_run WHERE id = ?", runId);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> runView = Rows.camelize(run, "inputSnapshot");
        runView.remove("inputSnapshot");
        out.put("run", runView);
        Map<String, Object> snapshot = Json.readMap(String.valueOf(run.get("input_snapshot")));
        out.put("packages", snapshot.get("packages"));
        out.put("quantities", Rows.camelize(jdbc.queryForList("SELECT * FROM quantity_state WHERE run_id = ? ORDER BY batch_code", runId), "detail"));
        out.put("edges", lineageCompiler.edgesOfRun(runId));
        out.put("identityRecords", identityService.recordsOfRun(runId));
        out.put("gaps", Rows.camelize(jdbc.queryForList("SELECT * FROM evidence_gap WHERE run_id = ? ORDER BY id", runId), "evidence"));
        return out;
    }

    /** 批次全链路证据钻取：主数据、数量区间、谱系边、身份候选、缺口、原始载荷。 */
    public Map<String, Object> batchTrace(long runId, String batchCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> masters = Rows.camelize(jdbc.queryForList("SELECT * FROM batch_master WHERE batch_code = ?", batchCode), "attributes");
        out.put("batchMaster", masters.isEmpty() ? null : masters.get(0));
        List<Map<String, Object>> quantities = Rows.camelize(jdbc.queryForList(
                "SELECT * FROM quantity_state WHERE run_id = ? AND batch_code = ?", runId, batchCode), "detail");
        out.put("quantity", quantities.isEmpty() ? null : quantities.get(0));
        out.put("edges", Rows.camelize(jdbc.queryForList(
                "SELECT * FROM lineage_edge WHERE run_id = ? AND (upstream_batch = ? OR downstream_batch = ?) ORDER BY id",
                runId, batchCode, batchCode), "edgeDetail"));

        List<Map<String, Object>> allRecords = identityService.recordsOfRun(runId);
        List<Map<String, Object>> related = new ArrayList<>();
        for (Map<String, Object> r : allRecords) {
            boolean hit = batchCode.equals(r.get("observed_batch_code"));
            if (!hit) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cands = (List<Map<String, Object>>) r.get("candidates");
                hit = cands.stream().anyMatch(c -> batchCode.equals(c.get("candidate_batch_code")));
            }
            if (hit) related.add(r);
        }
        out.put("identityRecords", related);
        out.put("gaps", Rows.camelize(jdbc.queryForList(
                "SELECT * FROM evidence_gap WHERE run_id = ? AND (subject_code = ? OR subject_code IN "
                        + "(SELECT edge_code FROM lineage_edge WHERE run_id = ? AND (upstream_batch = ? OR downstream_batch = ?))) ORDER BY id",
                runId, batchCode, runId, batchCode, batchCode), "evidence"));

        // 原始载荷：该批次相关谱系边涉及的来源包（内部可见）
        List<String> pkgCodes = jdbc.queryForList(
                "SELECT DISTINCT source_package_code FROM lineage_edge WHERE run_id = ? AND (upstream_batch = ? OR downstream_batch = ?)",
                String.class, runId, batchCode, batchCode);
        List<Map<String, Object>> rawPackages = new ArrayList<>();
        for (String code : pkgCodes) {
            ingestion.byCode(code).ifPresent(p -> {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("packageCode", p.packageCode());
                view.put("sourceSystem", p.sourceSystem());
                view.put("sourceRevision", p.sourceRevision());
                view.put("payloadHash", p.payloadHash());
                view.put("parseRuleVersion", p.parseRuleVersion());
                view.put("parseStatus", p.parseStatus());
                view.put("receivedAt", p.receivedAt().toString());
                view.put("payload", p.payload());
                rawPackages.add(view);
            });
        }
        out.put("rawPackages", rawPackages);
        return out;
    }

    public Map<String, Object> runRow(long runId) {
        return jdbc.queryForMap("SELECT * FROM verification_run WHERE id = ?", runId);
    }
}
