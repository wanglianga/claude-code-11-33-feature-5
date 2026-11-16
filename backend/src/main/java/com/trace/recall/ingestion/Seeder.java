package com.trace.recall.ingestion;

import com.trace.recall.config.Json;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 演示数据播种：2026-11 监管时间线。
 * 覆盖六条强制规则与 feature-5 监管答复验收场景（单机构单元、重复回报、未定义字段、非常规问题）。
 */
@Component
public class Seeder implements CommandLineRunner {

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private final JdbcTemplate jdbc;
    private final IngestionService ingestion;

    public Seeder(JdbcTemplate jdbc, IngestionService ingestion) {
        this.jdbc = jdbc;
        this.ingestion = ingestion;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM source_package", Integer.class);
        if (count != null && count > 0) return;
        seed();
    }

    private static OffsetDateTime t(String iso) {
        return OffsetDateTime.parse(iso + "+08:00");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> payload, String key) {
        return (List<Map<String, Object>>) payload.get(key);
    }

    public void seed() {
        // ---- 解析规则：PARSE-2.0 在两次核验截止之间生效（11-11），影响 LOGISTICS/BLEND ----
        jdbc.update("INSERT INTO parse_rule(version, effective_at, description, affected_types) VALUES (?,?,?,?)",
                "PARSE-1.9", t("2026-10-01T00:00:00"), "初始解析规则", "INSPECTION,FEEDING,RELEASE,SPLIT,BLEND,LOGISTICS,INSTITUTION_REPORT,MASTER_DATA");
        jdbc.update("INSERT INTO parse_rule(version, effective_at, description, affected_types) VALUES (?,?,?,?)",
                "PARSE-2.0", t("2026-11-11T00:00:00"), "物流与混批字段语义修订（箱码/签收字段含义变化）", "LOGISTICS,BLEND");

        // ---- 机构主数据 ----
        jdbc.update("INSERT INTO institution(inst_code, name, region) VALUES (?,?,?)", "HOSP-甲", "华东一院", "华东");
        jdbc.update("INSERT INTO institution(inst_code, name, region) VALUES (?,?,?)", "HOSP-乙", "华东二院", "华东");
        jdbc.update("INSERT INTO institution(inst_code, name, region) VALUES (?,?,?)", "HOSP-丙", "华北中心医院", "华北");
        jdbc.update("INSERT INTO institution(inst_code, name, region) VALUES (?,?,?)", "HOSP-丁", "华南附属医院", "华南");

        // ---- 11-01 主数据（含 RM-A02 双供应商批号观测，无装箱凭证 → 身份歧义） ----
        List<Map<String, Object>> batches = List.of(
                batch("RM-A01", "RAW", "PRD-X", "原料药-X", 100),
                batch("RM-A02", "RAW", "PRD-X", "原料药-X", 80),
                batch("RM-A03", "RAW", "PRD-X", "原料药-X", 50),
                batch("RM-A04", "RAW", "PRD-X", "原料药-X", 60),
                batch("IM-B10", "INTERMEDIATE", "PRD-X", "中间体-B10", 40),
                batch("IM-B11", "INTERMEDIATE", "PRD-X", "中间体-B11", 35),
                batch("IM-B30", "INTERMEDIATE", "PRD-X", "中间体-B30", 60),
                batch("IM-B40", "INTERMEDIATE", "PRD-X", "中间体-B40", 50),
                batch("FG-C01", "FINISHED", "PRD-Y", "制剂-Y", 120),
                batch("FG-C02", "FINISHED", "PRD-Y", "制剂-Y", 50),
                batch("FG-C03", "FINISHED", "PRD-Y", "制剂-Y", 60),
                batch("FG-C04", "FINISHED", "PRD-Y", "制剂-Y", 50)
        );
        Map<String, Object> mdmPayload = new LinkedHashMap<>();
        mdmPayload.put("batches", batches);
        mdmPayload.put("institutions", List.of(
                Map.of("instCode", "HOSP-甲", "region", "华东"),
                Map.of("instCode", "HOSP-乙", "region", "华东"),
                Map.of("instCode", "HOSP-丙", "region", "华北"),
                Map.of("instCode", "HOSP-丁", "region", "华南")));
        mdmPayload.put("identityObservations", List.of(Map.of(
                "batchCode", "RM-A02",
                "supplierLots", List.of("SUP-LOT-22", "SUP-LOT-23"),
                "packingEvidence", false,
                "note", "同一内部批号对应两个供应商批号，无可验证装箱凭证")));
        long mdmId = insertPackage("MDM-PKG-1", "MDM", "MDM-R1", "PARSE-1.9",
                t("2026-11-01T09:00:00"), t("2026-11-01T09:00:00"), null, mdmPayload);
        for (Map<String, Object> b : batches) {
            Map<String, Object> factPayload = new LinkedHashMap<>(b);
            if ("RM-A02".equals(b.get("batchCode"))) {
                factPayload.put("supplierLots", List.of("SUP-LOT-22", "SUP-LOT-23"));
                factPayload.put("packingEvidence", false);
            }
            insertFact(mdmId, "MASTER_DATA", "MD-" + b.get("batchCode"), t("2026-11-01T09:00:00"), factPayload);
            jdbc.update("INSERT INTO batch_master(batch_code, batch_type, product_code, product_name, unit, quantity, package_id, attributes) VALUES (?,?,?,?,?,?,?,?::jsonb)",
                    b.get("batchCode"), b.get("batchType"), b.get("productCode"), b.get("productName"), "kg",
                    b.get("quantity"), mdmId, Json.write(Map.of()));
        }

        // ---- 11-02 检验 V1：RM-A03、RM-A04 不合格 ----
        Map<String, Object> qc1Payload = Map.of("revision", "QC-REV-1", "inspections", List.of(
                Map.of("batchCode", "RM-A01", "result", "PASS"),
                Map.of("batchCode", "RM-A02", "result", "PASS"),
                Map.of("batchCode", "RM-A03", "result", "FAIL"),
                Map.of("batchCode", "RM-A04", "result", "FAIL")));
        long qc1Id = insertPackage("QC-PKG-1", "QC_LAB", "QC-REV-1", "PARSE-1.9",
                t("2026-11-02T10:00:00"), t("2026-11-02T10:00:00"), null, qc1Payload);
        for (Map<String, Object> ins : listOf(qc1Payload, "inspections")) {
            Map<String, Object> p = new LinkedHashMap<>(ins);
            p.put("revision", "QC-REV-1");
            insertFact(qc1Id, "INSPECTION", "INS-" + ins.get("batchCode"), t("2026-11-02T10:00:00"), p);
        }

        // ---- 11-03 投料/放行：RM-A04 拆分 75kg > 可用 60kg（超额冻结）；RM-A02→IM-B30 缺投料凭证 ----
        List<Map<String, Object>> mesEvents = List.of(
                feeding("FEED-01", "RM-A03", "IM-B10", 40, "VCH-01"),
                feeding("FEED-02", "RM-A04", "IM-B11", 35, "VCH-02"),
                feeding("FEED-03", "RM-A04", "IM-B30", 40, "VCH-03"),
                feeding("FEED-04", "RM-A02", "IM-B30", 25, null),
                feeding("FEED-05", "RM-A01", "IM-B40", 50, "VCH-05"),
                release("REL-01", "IM-B10", "FG-C01", 40),
                release("REL-02", "IM-B11", "FG-C02", 30),
                release("REL-03", "IM-B30", "FG-C03", 60),
                release("REL-04", "IM-B40", "FG-C04", 50)
        );
        long mesId = insertPackage("MES-PKG-1", "MES", "MES-R1", "PARSE-1.9",
                t("2026-11-03T14:00:00"), t("2026-11-03T14:00:00"), null, Map.of("events", mesEvents));
        for (Map<String, Object> e : mesEvents) {
            insertFact(mesId, (String) e.get("type"), (String) e.get("code"), t("2026-11-03T14:00:00"), e);
        }

        // ---- 11-06 物流（PARSE-1.9 解析；PARSE-2.0 于 11-11 生效后两包均触发解析差异） ----
        List<Map<String, Object>> log1 = List.of(
                shipment("SHIP-01", "FG-C01", "BOX-1", "HOSP-甲", 120, 120, null),
                shipment("SHIP-02", "FG-C02", "BOX-9", "HOSP-乙", 30, 30, null),
                shipment("SHIP-04", "FG-C03", "BOX-12", "HOSP-丁", 30, 30, null),
                shipment("SHIP-05", "FG-C02", "BOX-7", "HOSP-甲", 20, 20, null),
                shipment("SHIP-06", "FG-C04", "BOX-21", "HOSP-甲", 50, 50, null)
        );
        long log1Id = insertPackage("LOG-PKG-1", "LOGISTICS", "LOG-R1", "PARSE-1.9",
                t("2026-11-06T11:00:00"), t("2026-11-06T11:00:00"), null, Map.of("shipments", log1));
        for (Map<String, Object> s : log1) insertFact(log1Id, "LOGISTICS", (String) s.get("code"), t("2026-11-06T11:00:00"), s);

        // 箱码 BOX-9 在两条互不相连运输记录中重复；生产批号 PL-UNKNOWN-9 无法匹配
        List<Map<String, Object>> log2 = List.of(
                shipment("SHIP-03", "FG-C03", "BOX-9", "HOSP-丙", 30, 30, "PL-UNKNOWN-9")
        );
        long log2Id = insertPackage("LOG-PKG-2", "LOGISTICS", "LOG-R2", "PARSE-1.9",
                t("2026-11-06T16:00:00"), t("2026-11-06T16:00:00"), null, Map.of("shipments", log2));
        for (Map<String, Object> s : log2) insertFact(log2Id, "LOGISTICS", (String) s.get("code"), t("2026-11-06T16:00:00"), s);

        // ---- 11-07 机构回报 R1：HOSP-乙 46kg vs 物流签收 30kg（数量冲突）；RPT-003 为 RPT-001 的重复回报 ----
        List<Map<String, Object>> ins1 = List.of(
                report("RPT-001", "HOSP-甲", "华东", "FG-C01", 120, "2026-11-07T10:00:00", Map.of()),
                report("RPT-002", "HOSP-乙", "华东", "FG-C02", 46, "2026-11-07T11:00:00", Map.of()),
                report("RPT-003", "HOSP-甲", "华东", "FG-C01", 120, "2026-11-07T10:05:00", Map.of("duplicateOf", "RPT-001"))
        );
        long ins1Id = insertPackage("INS-PKG-1", "INSTITUTION", "INS-R1", "PARSE-1.9",
                t("2026-11-07T15:00:00"), t("2026-11-07T15:00:00"), null, Map.of("reports", ins1));
        for (Map<String, Object> r : ins1) insertFact(ins1Id, "INSTITUTION_REPORT", (String) r.get("reportCode"), t((String) r.get("eventTime")), r);

        // ---- 11-12 检验更正 V2：RM-A01 改判不合格、RM-A03 复检合格（RM-A04 维持不合格） ----
        Map<String, Object> qc2Payload = Map.of("revision", "QC-REV-2", "inspections", List.of(
                Map.of("batchCode", "RM-A01", "result", "FAIL"),
                Map.of("batchCode", "RM-A03", "result", "PASS")));
        long qc2Id = insertPackage("QC-PKG-2", "QC_LAB", "QC-REV-2", "PARSE-1.9",
                t("2026-11-12T10:00:00"), t("2026-11-12T10:00:00"), "QC-PKG-1", qc2Payload);
        for (Map<String, Object> ins : listOf(qc2Payload, "inspections")) {
            Map<String, Object> p = new LinkedHashMap<>(ins);
            p.put("revision", "QC-REV-2");
            insertFact(qc2Id, "INSPECTION", "INS2-" + ins.get("batchCode"), t("2026-11-12T10:00:00"), p);
        }

        // ---- 11-13 迟到机构回报 R2：含华南唯一机构 HOSP-丁（单机构单元）与未定义字段 ----
        List<Map<String, Object>> ins2 = List.of(
                report("RPT-004", "HOSP-丙", "华北", "FG-C03", 30, "2026-11-13T08:30:00", Map.of()),
                report("RPT-005", "HOSP-丁", "华南", "FG-C03", 30, "2026-11-13T08:40:00",
                        Map.of("wardContact", "病房联系人-李某", "driverPhone", "139****0001")),
                report("RPT-006", "HOSP-甲", "华东", "FG-C02", 20, "2026-11-13T08:50:00", Map.of()),
                report("RPT-007", "HOSP-甲", "华东", "FG-C04", 50, "2026-11-13T09:00:00", Map.of())
        );
        long ins2Id = insertPackage("INS-PKG-2", "INSTITUTION", "INS-R2", "PARSE-1.9",
                t("2026-11-13T09:30:00"), t("2026-11-13T09:30:00"), null, Map.of("reports", ins2));
        for (Map<String, Object> r : ins2) insertFact(ins2Id, "INSTITUTION_REPORT", (String) r.get("reportCode"), t((String) r.get("eventTime")), r);

        // ---- 外部处置系统凭证 ----
        jdbc.update("INSERT INTO api_client(api_key, name, enabled) VALUES (?,?,TRUE)",
                "ext-trace-key-2026", "外部处置系统-演示");

        seedRegulatoryMeta();
    }

    /** 监管答复元数据：问题册、策略册、预批准模板。 */
    private void seedRegulatoryMeta() {
        jdbc.update("INSERT INTO question_book(question_code, title, required_dims, required_measures, description) VALUES (?,?,?::jsonb,?::jsonb,?)",
                "Q-001", "各区域各月不同证据等级的受影响数量与机构回报数",
                Json.write(List.of("region", "month", "evidenceGrade")),
                Json.write(List.of("confirmedQty", "pendingQty", "possibleQty", "reportCount")),
                "常见问询：预批准模板 TPL-REG-GRADE 直接覆盖");
        jdbc.update("INSERT INTO question_book(question_code, title, required_dims, required_measures, description) VALUES (?,?,?::jsonb,?::jsonb,?)",
                "Q-002", "各产品在各区域的确认量下界",
                Json.write(List.of("productCode", "region")),
                Json.write(List.of("confirmedQty")),
                "常见问询：预批准模板 TPL-PROD-REGION 直接覆盖");
        jdbc.update("INSERT INTO question_book(question_code, title, required_dims, required_measures, description) VALUES (?,?,?::jsonb,?::jsonb,?)",
                "Q-003", "各机构单体的回报明细与联系人",
                Json.write(List.of("institution")),
                Json.write(List.of("reportCount", "contactPerson")),
                "非常规模板问题：institution 不在允许维度、contactPerson 属禁止字段 → 约束冲突");
        jdbc.update("INSERT INTO question_book(question_code, title, required_dims, required_measures, description) VALUES (?,?,?::jsonb,?::jsonb,?)",
                "Q-004", "各区域各证据等级的可能受影响上界",
                Json.write(List.of("region", "evidenceGrade")),
                Json.write(List.of("possibleQty")),
                "无预批准模板覆盖：复杂问询求最细可行聚合（存在等价解）");

        jdbc.update("INSERT INTO policy_book(policy_code, version_no, forbidden_fields, min_cell_size, allowed_dimensions, dimension_hierarchies, effective_from, description) VALUES (?,?,?::jsonb,?,?::jsonb,?::jsonb,?,?)",
                "POLICY-1", 1,
                Json.write(List.of("payload", "sensitiveContacts", "contactPerson", "phone", "wardContact", "driverPhone")),
                2,
                Json.write(List.of("region", "month", "evidenceGrade", "productCode")),
                Json.write(Map.of("region", List.of("national"), "month", List.of("quarter", "year"), "productCode", List.of("productLine"))),
                t("2026-11-01T00:00:00"),
                "监管答复披露策略：最小单元格=2 家机构；不足必须明确不披露，不得四舍五入掩盖机构");

        jdbc.update("INSERT INTO response_template(template_code, name, dimensions, measures, approved, description) VALUES (?,?,?::jsonb,?::jsonb,?,?)",
                "TPL-REG-GRADE", "区域×月份×证据层固定投影",
                Json.write(List.of("region", "month", "evidenceGrade")),
                Json.write(List.of("confirmedQty", "pendingQty", "possibleQty", "reportCount")),
                true, "常见问询预批准模板：审阅快，但对特殊问题可能过度隐藏");
        jdbc.update("INSERT INTO response_template(template_code, name, dimensions, measures, approved, description) VALUES (?,?,?::jsonb,?::jsonb,?,?)",
                "TPL-PROD-REGION", "产品×区域固定投影",
                Json.write(List.of("productCode", "region")),
                Json.write(List.of("confirmedQty", "pendingQty", "possibleQty", "reportCount")),
                true, "常见问询预批准模板");
    }

    private Map<String, Object> batch(String code, String type, String productCode, String productName, int qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchCode", code);
        m.put("batchType", type);
        m.put("productCode", productCode);
        m.put("productName", productName);
        m.put("quantity", qty);
        m.put("unit", "kg");
        return m;
    }

    private Map<String, Object> feeding(String code, String up, String down, int qty, String voucher) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "FEEDING");
        m.put("code", code);
        m.put("upstream", up);
        m.put("downstream", down);
        m.put("qty", qty);
        m.put("unit", "kg");
        m.put("voucher", voucher);
        return m;
    }

    private Map<String, Object> release(String code, String up, String down, int qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "RELEASE");
        m.put("code", code);
        m.put("upstream", up);
        m.put("downstream", down);
        m.put("qty", qty);
        m.put("unit", "kg");
        return m;
    }

    private Map<String, Object> shipment(String code, String batch, String box, String node, int qty, int signed, String productionLot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "LOGISTICS");
        m.put("code", code);
        m.put("batch", batch);
        m.put("boxCode", box);
        m.put("node", node);
        m.put("qty", qty);
        m.put("signedQty", signed);
        m.put("unit", "kg");
        if (productionLot != null) m.put("productionLot", productionLot);
        return m;
    }

    private Map<String, Object> report(String code, String inst, String region, String batch, int qty, String eventTime, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportCode", code);
        m.put("institution", inst);
        m.put("region", region);
        m.put("batchCode", batch);
        m.put("qty", qty);
        m.put("unit", "kg");
        m.put("eventTime", eventTime);
        m.putAll(extra);
        return m;
    }

    private long insertPackage(String code, String system, String revision, String parseRule,
                               OffsetDateTime receivedAt, OffsetDateTime eventTime, String supersedes,
                               Map<String, Object> payload) {
        String json = Json.write(payload);
        String hash = sha256(json);
        String status = ingestion.parseStatusAt(parseRule, factTypesOf(payload), OffsetDateTime.now());
        return jdbc.queryForObject(
                "INSERT INTO source_package(package_code, source_system, source_revision, payload_hash, parse_rule_version, parse_status, received_at, event_time, supersedes_package_code, payload) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb) RETURNING id",
                Long.class, code, system, revision, hash, parseRule, status, receivedAt, eventTime, supersedes, json);
    }

    @SuppressWarnings("unchecked")
    private Set<String> factTypesOf(Map<String, Object> payload) {
        Set<String> types = new HashSet<>();
        if (payload.containsKey("batches")) types.add("MASTER_DATA");
        if (payload.containsKey("inspections")) types.add("INSPECTION");
        if (payload.containsKey("reports")) types.add("INSTITUTION_REPORT");
        if (payload.containsKey("shipments")) types.add("LOGISTICS");
        Object events = payload.get("events");
        if (events instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof Map<?, ?> m && m.get("type") != null) types.add(m.get("type").toString());
            }
        }
        return types;
    }

    private void insertFact(long packageId, String type, String code, OffsetDateTime eventTime, Map<String, Object> payload) {
        jdbc.update("INSERT INTO raw_fact(package_id, fact_type, fact_code, event_time, payload) VALUES (?,?,?,?,?::jsonb)",
                packageId, type, code, eventTime, Json.write(payload));
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
