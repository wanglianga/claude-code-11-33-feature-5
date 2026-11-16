package com.trace.recall.identity;

import com.trace.recall.config.Json;
import com.trace.recall.config.Rows;
import com.trace.recall.ingestion.RawFact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 批次身份解析：同一内部批号对应两个供应商批号且无可验证凭证时，
 * 保留双候选、不合并（歧义是数据事实，不是待消除的噪声）。
 */
@Service
public class IdentityResolutionService {

    private final JdbcTemplate jdbc;

    public IdentityResolutionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @SuppressWarnings("unchecked")
    public void resolve(long runId, List<RawFact> facts) {
        // 1) 供应商批号观测（来自主数据）
        for (RawFact f : facts) {
            if (!"MASTER_DATA".equals(f.factType())) continue;
            Object lots = f.payload().get("supplierLots");
            if (!(lots instanceof List<?> list) || list.isEmpty()) continue;
            String batch = (String) f.payload().get("batchCode");
            boolean packingEvidence = Boolean.TRUE.equals(f.payload().get("packingEvidence"));
            boolean ambiguous = list.size() > 1 && !packingEvidence;
            for (Object lot : list) {
                long recId = insertRecord(runId, "SUPPLIER_LOT", lot.toString(), batch, ambiguous ? "AMBIGUOUS" : "MATCHED");
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("sourceFact", f.factCode());
                if (ambiguous) {
                    evidence.put("note", "同一内部批号 " + batch + " 对应供应商批号 " + list + "，无装箱凭证，保留双候选不合并");
                    evidence.put("alternativeLots", list);
                }
                insertCandidate(recId, batch, ambiguous ? "MEDIUM" : "HIGH", ambiguous, List.of(evidence));
            }
        }

        // 2) 箱码观测（来自物流事实）：同一箱码出现在多条互不相连运输记录 → 歧义
        Map<String, List<RawFact>> byBox = new LinkedHashMap<>();
        Map<String, List<RawFact>> byProdLot = new LinkedHashMap<>();
        for (RawFact f : facts) {
            if (!"LOGISTICS".equals(f.factType())) continue;
            Object box = f.payload().get("boxCode");
            if (box != null) byBox.computeIfAbsent(box.toString(), k -> new ArrayList<>()).add(f);
            Object pl = f.payload().get("productionLot");
            if (pl != null) byProdLot.computeIfAbsent(pl.toString(), k -> new ArrayList<>()).add(f);
        }
        byBox.forEach((box, shipments) -> {
            Set<String> batches = new LinkedHashSet<>();
            for (RawFact s : shipments) batches.add((String) s.payload().get("batch"));
            boolean ambiguous = batches.size() > 1;
            long recId = insertRecord(runId, "BOX_CODE", box, ambiguous ? null : batches.iterator().next(),
                    ambiguous ? "AMBIGUOUS" : "MATCHED");
            for (String b : batches) {
                List<Map<String, Object>> evidence = new ArrayList<>();
                for (RawFact s : shipments) {
                    if (b.equals(s.payload().get("batch"))) {
                        evidence.add(Map.of("sourceFact", s.factCode(), "node", s.payload().get("node")));
                    }
                }
                insertCandidate(recId, b, ambiguous ? "LOW" : "HIGH", ambiguous, evidence);
            }
        });

        // 3) 生产批号观测：无法匹配内部批次 → UNMATCHED，不强行归因
        Set<String> knownBatches = new HashSet<>(jdbc.queryForList("SELECT batch_code FROM batch_master", String.class));
        byProdLot.forEach((pl, shipments) -> {
            String observed = null;
            for (RawFact s : shipments) {
                String b = (String) s.payload().get("batch");
                if (knownBatches.contains(pl)) { observed = pl; break; }
            }
            long recId = insertRecord(runId, "PRODUCTION_LOT", pl, observed, observed == null ? "UNMATCHED" : "MATCHED");
            if (observed != null) insertCandidate(recId, observed, "HIGH", false, List.of());
        });
    }

    private long insertRecord(long runId, String type, String value, String observedBatch, String status) {
        return jdbc.queryForObject(
                "INSERT INTO identity_record(run_id, code_type, code_value, observed_batch_code, match_status) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, runId, type, value, observedBatch, status);
    }

    private void insertCandidate(long recordId, String batch, String confidence, boolean ambiguous, List<Map<String, Object>> evidence) {
        jdbc.update("INSERT INTO identity_candidate(identity_record_id, candidate_batch_code, confidence, ambiguous, evidence) VALUES (?,?,?,?,?::jsonb)",
                recordId, batch, confidence, ambiguous, Json.write(evidence));
    }

    public List<Map<String, Object>> recordsOfRun(long runId) {
        List<Map<String, Object>> records = Rows.camelize(jdbc.queryForList(
                "SELECT * FROM identity_record WHERE run_id = ? ORDER BY id", runId));
        for (Map<String, Object> r : records) {
            r.put("candidates", Rows.camelize(jdbc.queryForList(
                    "SELECT * FROM identity_candidate WHERE identity_record_id = ? ORDER BY id", r.get("id")), "evidence"));
        }
        return records;
    }
}
