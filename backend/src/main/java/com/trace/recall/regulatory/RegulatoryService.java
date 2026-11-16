package com.trace.recall.regulatory;

import com.trace.recall.config.Json;
import com.trace.recall.config.Rows;
import com.trace.recall.ingestion.IngestionService;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 监管答复：回答法规问题所需的最细、仍可验证的汇总（不是整包脱敏导出）。
 * - 答复日快照只含当时已发布的范围、机构回报（去重后）和证据等级；
 * - 常见问询由预批准模板投影；复杂问询在问题覆盖与披露约束下求最细可行聚合；
 * - 小单元无法安全披露时明确不披露，不用四舍五入掩盖机构。
 */
@Service
public class RegulatoryService {

    /** 上卷一个层级对每条事实计 0.4 信息损失（经验权重）；不披露一条事实计 1.0。 */
    private static final double COARSEN_WEIGHT = 0.4;
    /** 机构回报载荷中已定义的字段；其余字段一律保持待复核。 */
    private static final Set<String> DEFINED_REPORT_FIELDS = Set.of(
            "reportCode", "institution", "region", "batchCode", "qty", "unit", "eventTime", "duplicateOf");

    private final JdbcTemplate jdbc;
    private final ResponseVerifier verifier;
    private final AuditService audit;

    public RegulatoryService(JdbcTemplate jdbc, ResponseVerifier verifier, AuditService audit) {
        this.jdbc = jdbc;
        this.verifier = verifier;
        this.audit = audit;
    }

    // ============================== 生成 ==============================

    @Transactional
    public Map<String, Object> createResponse(Map<String, Object> req) {
        String inquiryType = required(req, "inquiryType").toUpperCase();
        @SuppressWarnings("unchecked")
        List<String> questionCodes = (List<String>) req.get("questionCodes");
        if (questionCodes == null || questionCodes.isEmpty()) throw new IllegalArgumentException("必须至少选择一个问题");
        OffsetDateTime responseDate = OffsetDateTime.parse(required(req, "responseDate"));
        String createdBy = String.valueOf(req.getOrDefault("createdBy", "质量分析员"));

        Map<String, Object> policy = activePolicy();
        List<Map<String, Object>> questions = questionCodes.stream().map(this::question).toList();
        Map<String, Object> snapshot = buildSnapshot(responseDate);

        String templateCode = null;
        List<String> dimensions;
        double informationLoss;
        List<Map<String, Object>> equivalentSolutions = new ArrayList<>();
        List<Map<String, Object>> constraintConflicts = new ArrayList<>();
        List<Map<String, Object>> cells;
        String explanation;

        if ("TEMPLATE".equals(inquiryType)) {
            templateCode = required(req, "templateCode");
            Map<String, Object> template = template(templateCode);
            List<String> tplDims = Json.readStringList(String.valueOf(template.get("dimensions")));
            // 模板覆盖校验：问题所需维度必须被模板固定投影覆盖，否则属于非常规模板问题
            for (Map<String, Object> q : questions) {
                List<String> required = Json.readStringList(String.valueOf(q.get("required_dims")));
                if (!new HashSet<>(tplDims).containsAll(required)) {
                    throw new IllegalArgumentException("非常规模板问题：" + q.get("question_code") + " 需要维度 " + required
                            + "，预批准模板 " + templateCode + " 仅投影 " + tplDims + "，请改用复杂问询");
                }
            }
            checkDimensionsAllowed(tplDims, policy);
            dimensions = tplDims;
            cells = aggregate(snapshot, dimensions, policy);
            informationLoss = cells.stream().filter(c -> !Boolean.TRUE.equals(c.get("disclosed")))
                    .mapToDouble(c -> ((Number) c.get("factCount")).doubleValue()).sum();
            explanation = "预批准模板固定投影（" + String.join(",", dimensions) + "），审阅快；"
                    + (informationLoss > 0 ? "本次有 " + (int) informationLoss + " 条事实所在单元因最小单元格约束被过度隐藏。" : "无单元被隐藏。");
        } else if ("COMPLEX".equals(inquiryType)) {
            Set<String> requiredDims = new LinkedHashSet<>();
            Set<String> requiredMeasures = new LinkedHashSet<>();
            for (Map<String, Object> q : questions) {
                requiredDims.addAll(Json.readStringList(String.valueOf(q.get("required_dims"))));
                requiredMeasures.addAll(Json.readStringList(String.valueOf(q.get("required_measures"))));
            }
            Solution solution = solveComplex(snapshot, new ArrayList<>(requiredDims), new ArrayList<>(requiredMeasures), policy);
            dimensions = solution.dimensions();
            cells = solution.cells();
            informationLoss = solution.loss();
            equivalentSolutions = solution.equivalents();
            constraintConflicts = solution.conflicts();
            explanation = solution.explanation();
        } else {
            throw new IllegalArgumentException("inquiryType 必须是 TEMPLATE 或 COMPLEX");
        }

        long responseId = jdbc.queryForObject(
                "INSERT INTO regulatory_response(response_code, inquiry_type, template_code, question_codes, policy_code, response_date, release_id, status, dimensions, information_loss, equivalent_solutions, constraint_conflicts, created_by) "
                        + "VALUES ('PENDING', ?, ?, ?::jsonb, ?, ?, ?, 'GENERATED', ?::jsonb, ?, ?::jsonb, ?::jsonb, ?) RETURNING id",
                Long.class, inquiryType, templateCode, Json.write(questionCodes), policy.get("policy_code"),
                responseDate, snapshot.get("releaseId"), Json.write(dimensions), informationLoss,
                Json.write(equivalentSolutions), Json.write(constraintConflicts), createdBy);
        String responseCode = String.format("RESP-%03d", responseId);
        jdbc.update("UPDATE regulatory_response SET response_code = ? WHERE id = ?", responseCode, responseId);

        persistSnapshot(responseId, snapshot);
        persistCells(responseId, cells);
        verifier.reconcile(responseId, snapshot, cells);
        audit.audit(responseId, "RESPONSE_CREATED", createdBy,
                inquiryType + " 问询生成：" + explanation);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("responseId", responseId);
        out.put("responseCode", responseCode);
        out.put("informationLoss", informationLoss);
        out.put("explanation", explanation);
        return out;
    }

    // ============================== 答复日快照 ==============================

    /** 答复日快照：只含当时已发布的范围、机构回报（去重后）和证据等级。 */
    public Map<String, Object> buildSnapshot(OffsetDateTime responseDate) {
        // 数据截止时间 ≤ 答复日的最新发布版本
        List<Map<String, Object>> releases = jdbc.queryForList(
                "SELECT rv.* FROM release_version rv JOIN verification_run vr ON rv.run_id = vr.id "
                        + "WHERE vr.cutoff_at <= ? ORDER BY rv.version_no DESC", responseDate);
        if (releases.isEmpty()) throw new IllegalStateException("答复日 " + responseDate + " 前没有任何已发布范围，无法生成监管答复");
        Map<String, Object> release = releases.get(0);
        long releaseId = ((Number) release.get("id")).longValue();

        List<Map<String, Object>> items = jdbc.queryForList("SELECT * FROM release_item WHERE release_id = ?", releaseId);
        Map<String, String> regionOf = new HashMap<>();
        jdbc.queryForList("SELECT inst_code, region FROM institution")
                .forEach(r -> regionOf.put((String) r.get("inst_code"), (String) r.get("region")));
        Map<String, String> productOf = new HashMap<>();
        jdbc.queryForList("SELECT batch_code, product_code FROM batch_master")
                .forEach(r -> productOf.put((String) r.get("batch_code"), (String) r.get("product_code")));

        // 机构回报：答复日前接收且来源未撤销；相同机构的重复回报在核平前合并
        List<Map<String, Object>> reportRows = jdbc.queryForList(
                "SELECT f.fact_code, f.event_time, f.payload::text AS payload, p.package_code FROM raw_fact f "
                        + "JOIN source_package p ON f.package_id = p.id "
                        + "WHERE f.fact_type = 'INSTITUTION_REPORT' AND p.received_at <= ? AND p.status = 'ACTIVE' "
                        + "ORDER BY f.event_time", responseDate);
        List<Map<String, Object>> rawReports = new ArrayList<>();
        for (Map<String, Object> row : reportRows) {
            Map<String, Object> payload = Json.readMap((String) row.get("payload"));
            payload.put("_factCode", row.get("fact_code"));
            payload.put("_packageCode", row.get("package_code"));
            rawReports.add(payload);
        }
        // 去重合并：同机构 + 同批次 + 同数量 的重复回报合并为一条
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        List<Map<String, Object>> mergedReports = new ArrayList<>();
        Map<String, List<String>> duplicates = new LinkedHashMap<>();
        for (Map<String, Object> r : rawReports) {
            String key = r.get("institution") + "|" + r.get("batchCode") + "|" + r.get("qty");
            if (deduped.containsKey(key)) {
                duplicates.computeIfAbsent(key, k -> new ArrayList<>()).add((String) r.get("_factCode"));
            } else {
                deduped.put(key, r);
            }
        }
        duplicates.forEach((key, dups) -> {
            Map<String, Object> kept = deduped.get(key);
            mergedReports.add(Map.of(
                    "keptReport", kept.get("_factCode"),
                    "mergedDuplicates", dups,
                    "institution", kept.get("institution"),
                    "batchCode", kept.get("batchCode"),
                    "reason", "相同机构的重复回报在核平前合并"));
        });
        List<Map<String, Object>> reports = new ArrayList<>(deduped.values());

        // 未定义字段保持待复核
        List<Map<String, Object>> undefinedFields = new ArrayList<>();
        for (Map<String, Object> r : reports) {
            for (String field : r.keySet()) {
                if (field.startsWith("_")) continue;
                if (!DEFINED_REPORT_FIELDS.contains(field)) {
                    undefinedFields.add(Map.of(
                            "reportCode", r.get("_factCode"),
                            "field", field,
                            "status", "PENDING_REVIEW",
                            "reason", "字段未在回报字典中定义，保持待复核，不进入聚合"));
                }
            }
        }

        // 扁平化答复事实：发布项 × 机构回报（按机构+批次关联）
        List<Map<String, Object>> facts = new ArrayList<>();
        Set<String> usedReports = new HashSet<>();
        String releaseMonth = String.valueOf(release.get("published_at")).substring(0, 7);
        for (Map<String, Object> item : items) {
            String batch = (String) item.get("batch_code");
            String node = (String) item.get("node_name");
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> r : reports) {
                if (Objects.equals(r.get("institution"), node) && Objects.equals(r.get("batchCode"), batch)) {
                    matched.add(r);
                    usedReports.add((String) r.get("_factCode"));
                }
            }
            String month = matched.isEmpty() ? releaseMonth
                    : String.valueOf(matched.get(0).get("eventTime")).substring(0, 7);
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("batchCode", batch);
            fact.put("productCode", productOf.getOrDefault(batch, "未知"));
            fact.put("institution", node == null ? "未分配节点" : node);
            fact.put("region", node == null ? "未知" : regionOf.getOrDefault(node, "未知"));
            fact.put("month", month);
            fact.put("evidenceGrade", item.get("evidence_grade"));
            fact.put("confirmedQty", ((Number) item.get("confirmed_qty")).doubleValue());
            fact.put("pendingQty", ((Number) item.get("pending_qty")).doubleValue());
            fact.put("possibleQty", ((Number) item.get("possible_qty")).doubleValue());
            fact.put("reportCount", matched.size());
            fact.put("reportCodes", matched.stream().map(r -> (String) r.get("_factCode")).toList());
            facts.add(fact);
        }
        List<Map<String, Object>> outOfScope = new ArrayList<>();
        for (Map<String, Object> r : reports) {
            if (!usedReports.contains((String) r.get("_factCode"))) {
                outOfScope.add(Map.of(
                        "reportCode", r.get("_factCode"),
                        "institution", r.get("institution"),
                        "batchCode", r.get("batchCode"),
                        "qty", r.get("qty"),
                        "reason", "该回报的批次/机构不在答复日已发布范围内"));
            }
        }

        Map<String, Integer> evidenceDist = new TreeMap<>();
        for (Map<String, Object> item : items) evidenceDist.merge((String) item.get("evidence_grade"), 1, Integer::sum);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("confirmedQty", items.stream().mapToDouble(i -> ((Number) i.get("confirmed_qty")).doubleValue()).sum());
        totals.put("pendingQty", items.stream().mapToDouble(i -> ((Number) i.get("pending_qty")).doubleValue()).sum());
        totals.put("possibleQty", items.stream().mapToDouble(i -> ((Number) i.get("possible_qty")).doubleValue()).sum());

        Set<String> packageCodes = new TreeSet<>();
        reportRows.forEach(r -> packageCodes.add((String) r.get("package_code")));
        Map<String, Object> basis = Json.readMap(String.valueOf(release.get("basis")));
        Object basisPackages = basis.get("packages");
        if (basisPackages instanceof List<?> list) {
            for (Object p : list) {
                if (p instanceof Map<?, ?> m && m.get("packageCode") != null) packageCodes.add(String.valueOf(m.get("packageCode")));
            }
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("releaseId", releaseId);
        snapshot.put("releaseCode", release.get("release_code"));
        snapshot.put("packageCodes", new ArrayList<>(packageCodes));
        snapshot.put("facts", facts);
        snapshot.put("rawReportCount", rawReports.size());
        snapshot.put("dedupReportCount", reports.size());
        snapshot.put("mergedReports", mergedReports);
        snapshot.put("outOfScopeReports", outOfScope);
        snapshot.put("evidenceDistribution", evidenceDist);
        snapshot.put("quantityTotals", totals);
        snapshot.put("undefinedFields", undefinedFields);
        return snapshot;
    }

    // ============================== 聚合与不披露 ==============================

    /** 按维度聚合快照事实；机构数低于最小单元格的单元明确不披露（不四舍五入）。 */
    List<Map<String, Object>> aggregate(Map<String, Object> snapshot, List<String> dims, Map<String, Object> policy) {
        return aggregateWithMapping(snapshot, dims, policy, Map.of());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> aggregateWithMapping(Map<String, Object> snapshot, List<String> dims,
                                                           Map<String, Object> policy, Map<String, String> dimMapping) {
        int minCell = ((Number) policy.get("min_cell_size")).intValue();
        List<Map<String, Object>> facts = (List<Map<String, Object>>) snapshot.get("facts");
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> f : facts) {
            StringBuilder key = new StringBuilder();
            for (String d : dims) {
                String mapped = dimMapping.getOrDefault(d, d);
                key.append(d).append('=').append(dimValue(mapped, f)).append('|');
            }
            groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(f);
        }
        List<Map<String, Object>> cells = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> en : groups.entrySet()) {
            Map<String, Object> dimValues = new LinkedHashMap<>();
            for (String d : dims) {
                String mapped = dimMapping.getOrDefault(d, d);
                dimValues.put(d, dimValue(mapped, en.getValue().get(0)));
            }
            Set<String> institutions = new HashSet<>();
            double confirmed = 0, pending = 0, possible = 0;
            int reportCount = 0;
            for (Map<String, Object> f : en.getValue()) {
                institutions.add((String) f.get("institution"));
                confirmed += ((Number) f.get("confirmedQty")).doubleValue();
                pending += ((Number) f.get("pendingQty")).doubleValue();
                possible += ((Number) f.get("possibleQty")).doubleValue();
                reportCount += ((Number) f.get("reportCount")).intValue();
            }
            boolean disclosed = institutions.size() >= minCell;
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("cellKey", en.getKey());
            cell.put("dimensions", dimValues);
            cell.put("institutionCount", institutions.size());
            cell.put("reportCount", reportCount);
            cell.put("factCount", en.getValue().size());
            cell.put("confirmedQty", r3(confirmed));
            cell.put("pendingQty", r3(pending));
            cell.put("possibleQty", r3(possible));
            cell.put("disclosed", disclosed);
            cell.put("nonDisclosureReason", disclosed ? null
                    : "单元仅含 " + institutions.size() + " 家机构（低于最小单元格 " + minCell
                    + " 家），无法安全披露，明确不披露而非四舍五入掩盖机构");
            cells.add(cell);
        }
        return cells;
    }

    private Object dimValue(String dim, Map<String, Object> fact) {
        return switch (dim) {
            case "national" -> "全国";
            case "quarter" -> quarterOf((String) fact.get("month"));
            case "year" -> ((String) fact.get("month")).substring(0, 4);
            case "productLine" -> "全部产品线";
            default -> fact.get(dim);
        };
    }

    private static String quarterOf(String month) {
        int m = Integer.parseInt(month.substring(5, 7));
        return month.substring(0, 4) + "-Q" + ((m - 1) / 3 + 1);
    }

    // ============================== 复杂问询求解 ==============================

    private Solution solveComplex(Map<String, Object> snapshot, List<String> requiredDims,
                                  List<String> requiredMeasures, Map<String, Object> policy) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Set<String> allowed = new HashSet<>(Json.readStringList(String.valueOf(policy.get("allowed_dimensions"))));
        Set<String> forbidden = new HashSet<>(Json.readStringList(String.valueOf(policy.get("forbidden_fields"))));

        List<String> feasible = new ArrayList<>();
        for (String d : requiredDims) {
            if (allowed.contains(d)) feasible.add(d);
            else conflicts.add(Map.of("type", "DIMENSION_NOT_ALLOWED", "dimension", d,
                    "explanation", "问题要求的维度「" + d + "」不在策略册允许维度 " + allowed + " 内，已从聚合中移除；该问题覆盖不完整"));
        }
        for (String m : requiredMeasures) {
            if (forbidden.contains(m)) {
                conflicts.add(Map.of("type", "MEASURE_FORBIDDEN", "measure", m,
                        "explanation", "度量「" + m + "」属于策略册禁止字段，任何方案都不得输出"));
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, List<String>> hierarchies = Json.readMap(String.valueOf(policy.get("dimension_hierarchies")))
                .entrySet().stream().collect(LinkedHashMap::new,
                        (m, e) -> m.put(e.getKey(), (List<String>) e.getValue()), LinkedHashMap::putAll);

        // 候选解：原维度 + 每个可上卷维度的各级父维度
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate(feasible, Map.of(), 0));
        for (String d : feasible) {
            List<String> parents = hierarchies.getOrDefault(d, List.of());
            for (int i = 0; i < parents.size(); i++) {
                List<String> dims = new ArrayList<>(feasible);
                dims.set(dims.indexOf(d), parents.get(i));
                Map<String, String> mapping = new HashMap<>();
                mapping.put(parents.get(i), parents.get(i));
                candidates.add(new Candidate(dims, mapping, i + 1));
            }
        }

        @SuppressWarnings("unchecked")
        int factCount = ((List<Map<String, Object>>) snapshot.get("facts")).size();
        List<Map<String, Object>> evaluated = new ArrayList<>();
        for (Candidate c : candidates) {
            List<Map<String, Object>> cells = aggregateWithMapping(snapshot, c.dims(), policy, c.mapping());
            long suppressedFacts = cells.stream().filter(x -> !Boolean.TRUE.equals(x.get("disclosed")))
                    .mapToLong(x -> ((Number) x.get("factCount")).longValue()).sum();
            long suppressedCells = cells.stream().filter(x -> !Boolean.TRUE.equals(x.get("disclosed"))).count();
            double loss = suppressedFacts + COARSEN_WEIGHT * c.levels() * factCount;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("dimensions", c.dims());
            e.put("cells", cells);
            e.put("loss", r3(loss));
            e.put("suppressedCells", suppressedCells);
            e.put("suppressedFacts", suppressedFacts);
            e.put("coarsenLevels", c.levels());
            evaluated.add(e);
        }

        double minLoss = evaluated.stream().mapToDouble(e -> ((Number) e.get("loss")).doubleValue()).min().orElse(0);
        // 选定：损失最小者优先；并列时上卷层级少者、维度多者、字典序小者优先
        List<Map<String, Object>> best = evaluated.stream()
                .filter(e -> Math.abs(((Number) e.get("loss")).doubleValue() - minLoss) < 1e-9)
                .sorted(Comparator.<Map<String, Object>>comparingInt(e -> ((Number) e.get("coarsenLevels")).intValue())
                        .thenComparingInt(e -> -((List<?>) e.get("dimensions")).size())
                        .thenComparing(e -> e.get("dimensions").toString()))
                .toList();
        Map<String, Object> chosen = best.get(0);
        List<Map<String, Object>> equivalents = new ArrayList<>();
        for (int i = 1; i < best.size(); i++) {
            Map<String, Object> alt = best.get(i);
            equivalents.add(Map.of(
                    "dimensions", alt.get("dimensions"),
                    "informationLoss", alt.get("loss"),
                    "suppressedCells", alt.get("suppressedCells"),
                    "explanation", "与选定解信息损失相同（" + alt.get("loss") + "）：维度 "
                            + alt.get("dimensions") + "，隐藏单元 " + alt.get("suppressedCells") + " 个；"
                            + "选定解保留更细维度，故列为等价备选"));
        }
        String explanation = "在允许维度内求最细可行聚合：选定维度 " + chosen.get("dimensions")
                + "，信息损失 " + chosen.get("loss") + "（不披露事实 " + chosen.get("suppressedFacts")
                + " 条 + 上卷 " + chosen.get("coarsenLevels") + " 级×" + COARSEN_WEIGHT + "）"
                + (equivalents.isEmpty() ? "；无等价解" : "；存在 " + equivalents.size() + " 个等价解已列出")
                + (conflicts.isEmpty() ? "" : "；存在 " + conflicts.size() + " 项约束冲突");
        return new Solution(
                (List<String>) chosen.get("dimensions"),
                (List<Map<String, Object>>) chosen.get("cells"),
                ((Number) chosen.get("loss")).doubleValue(),
                equivalents, conflicts, explanation);
    }

    private record Candidate(List<String> dims, Map<String, String> mapping, int levels) {}

    private record Solution(List<String> dimensions, List<Map<String, Object>> cells, double loss,
                            List<Map<String, Object>> equivalents, List<Map<String, Object>> conflicts,
                            String explanation) {}

    // ============================== 来源撤销联动 ==============================

    /** 来源版本撤销会取消生成：快照中含该来源且仍处于已生成状态的答复一律取消。 */
    @EventListener
    @Transactional
    public void onSourceRevoked(IngestionService.SourceRevokedEvent event) {
        List<Map<String, Object>> responses = jdbc.queryForList(
                "SELECT r.id, r.response_code FROM regulatory_response r "
                        + "JOIN response_snapshot s ON s.response_id = r.id "
                        + "WHERE r.status = 'GENERATED' AND s.package_codes::jsonb @> ?::jsonb",
                Json.write(List.of(event.packageCode())));
        for (Map<String, Object> r : responses) {
            long id = ((Number) r.get("id")).longValue();
            jdbc.update("UPDATE regulatory_response SET status = 'CANCELLED', cancelled_reason = ? WHERE id = ?",
                    "来源包 " + event.packageCode() + " 已撤销：" + event.reason(), id);
            audit.audit(id, "RESPONSE_CANCELLED", event.actor(),
                    "来源版本撤销取消生成：" + event.packageCode() + "（" + event.reason() + "）");
        }
        audit.audit(null, "SOURCE_REVOKED", event.actor(),
                "来源包 " + event.packageCode() + " 撤销：" + event.reason() + "；联动取消 " + responses.size() + " 份监管答复");
    }

    // ============================== 查询 ==============================

    public List<Map<String, Object>> listResponses() {
        return Rows.camelize(jdbc.queryForList("SELECT * FROM regulatory_response ORDER BY id DESC"),
                "questionCodes", "dimensions", "equivalentSolutions", "constraintConflicts");
    }

    public Map<String, Object> responseDetail(long id) {
        Map<String, Object> response = Rows.camelize(jdbc.queryForMap("SELECT * FROM regulatory_response WHERE id = ?", id),
                "questionCodes", "dimensions", "equivalentSolutions", "constraintConflicts");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("response", response);
        // 单元格：不披露单元的数量与回报数对外隐藏（核验器仍可对平合计）
        List<Map<String, Object>> cells = Rows.camelize(jdbc.queryForList(
                "SELECT * FROM response_cell WHERE response_id = ? ORDER BY cell_key", id), "dimensions");
        for (Map<String, Object> c : cells) {
            if (!Boolean.TRUE.equals(c.get("disclosed"))) {
                c.put("confirmedQty", null);
                c.put("pendingQty", null);
                c.put("possibleQty", null);
                c.put("reportCount", null);
            }
        }
        out.put("cells", cells);
        out.put("reconciliation", Rows.camelize(jdbc.queryForList(
                "SELECT * FROM response_reconciliation WHERE response_id = ? ORDER BY id", id)));
        List<Map<String, Object>> snapshots = jdbc.queryForList(
                "SELECT * FROM response_snapshot WHERE response_id = ?", id);
        if (!snapshots.isEmpty()) {
            Map<String, Object> s = Rows.camelize(snapshots.get(0),
                    "packageCodes", "facts", "mergedReports", "outOfScopeReports", "evidenceDistribution",
                    "quantityTotals", "undefinedFields");
            out.put("snapshot", s);
        }
        return out;
    }

    public Map<String, Object> meta() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("questions", Rows.camelize(jdbc.queryForList("SELECT * FROM question_book ORDER BY question_code"),
                "requiredDims", "requiredMeasures"));
        out.put("policies", Rows.camelize(jdbc.queryForList("SELECT * FROM policy_book ORDER BY policy_code"),
                "forbiddenFields", "allowedDimensions", "dimensionHierarchies"));
        out.put("templates", Rows.camelize(jdbc.queryForList("SELECT * FROM response_template ORDER BY template_code"),
                "dimensions", "measures"));
        return out;
    }

    // ============================== 持久化辅助 ==============================

    private void persistSnapshot(long responseId, Map<String, Object> snapshot) {
        jdbc.update("INSERT INTO response_snapshot(response_id, release_code, package_codes, facts, raw_report_count, dedup_report_count, merged_reports, out_of_scope_reports, evidence_distribution, quantity_totals, undefined_fields) "
                        + "VALUES (?,?,?::jsonb,?::jsonb,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb)",
                responseId, snapshot.get("releaseCode"), Json.write(snapshot.get("packageCodes")),
                Json.write(snapshot.get("facts")), snapshot.get("rawReportCount"), snapshot.get("dedupReportCount"),
                Json.write(snapshot.get("mergedReports")), Json.write(snapshot.get("outOfScopeReports")),
                Json.write(snapshot.get("evidenceDistribution")), Json.write(snapshot.get("quantityTotals")),
                Json.write(snapshot.get("undefinedFields")));
    }

    private void persistCells(long responseId, List<Map<String, Object>> cells) {
        for (Map<String, Object> c : cells) {
            jdbc.update("INSERT INTO response_cell(response_id, cell_key, dimensions, institution_count, report_count, fact_count, confirmed_qty, pending_qty, possible_qty, disclosed, non_disclosure_reason) "
                            + "VALUES (?,?,?::jsonb,?,?,?,?,?,?,?,?)",
                    responseId, c.get("cellKey"), Json.write(c.get("dimensions")), c.get("institutionCount"),
                    c.get("reportCount"), c.get("factCount"), c.get("confirmedQty"), c.get("pendingQty"),
                    c.get("possibleQty"), c.get("disclosed"), c.get("nonDisclosureReason"));
        }
    }

    private Map<String, Object> activePolicy() {
        List<Map<String, Object>> policies = jdbc.queryForList(
                "SELECT * FROM policy_book WHERE status = 'ACTIVE' ORDER BY version_no DESC LIMIT 1");
        if (policies.isEmpty()) throw new IllegalStateException("策略册为空，无法生成监管答复");
        return policies.get(0);
    }

    private Map<String, Object> question(String code) {
        List<Map<String, Object>> q = jdbc.queryForList("SELECT * FROM question_book WHERE question_code = ?", code);
        if (q.isEmpty()) throw new NoSuchElementException("问题册中不存在问题: " + code);
        return q.get(0);
    }

    private Map<String, Object> template(String code) {
        List<Map<String, Object>> t = jdbc.queryForList(
                "SELECT * FROM response_template WHERE template_code = ? AND approved = TRUE", code);
        if (t.isEmpty()) throw new NoSuchElementException("预批准模板不存在或未批准: " + code);
        return t.get(0);
    }

    private void checkDimensionsAllowed(List<String> dims, Map<String, Object> policy) {
        Set<String> allowed = new HashSet<>(Json.readStringList(String.valueOf(policy.get("allowed_dimensions"))));
        for (String d : dims) {
            if (!allowed.contains(d)) throw new IllegalStateException("模板维度 " + d + " 不在策略册允许维度内");
        }
    }

    private String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) throw new IllegalArgumentException("缺少必填字段: " + key);
        return v.toString();
    }

    private static double r3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
