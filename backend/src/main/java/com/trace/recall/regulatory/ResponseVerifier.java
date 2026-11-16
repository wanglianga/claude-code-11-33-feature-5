package com.trace.recall.regulatory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 核验器：把监管答复输出同冻结来源（答复日快照）核平——
 * 机构回报去重数、证据等级分布、三类数量合计、未定义字段待复核数，逐项对账。
 */
@Service
public class ResponseVerifier {

    private final JdbcTemplate jdbc;

    public ResponseVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @SuppressWarnings("unchecked")
    public void reconcile(long responseId, Map<String, Object> snapshot, List<Map<String, Object>> cells) {
        // 1) 机构回报去重数：单元格覆盖数 + 范围外回报数 == 快照去重数
        int inCells = cells.stream().mapToInt(c -> ((Number) c.get("reportCount")).intValue()).sum();
        List<Map<String, Object>> outOfScope = (List<Map<String, Object>>) snapshot.get("outOfScopeReports");
        int actual = inCells + outOfScope.size();
        int expected = ((Number) snapshot.get("dedupReportCount")).intValue();
        write(responseId, "DEDUP_REPORT_COUNT", expected, actual,
                "单元格覆盖 " + inCells + " 条 + 已发布范围外 " + outOfScope.size() + " 条");

        // 2) 证据等级分布：输出引用的事实分布 == 快照冻结分布
        Map<String, Object> expectedDist = (Map<String, Object>) snapshot.get("evidenceDistribution");
        List<Map<String, Object>> facts = (List<Map<String, Object>>) snapshot.get("facts");
        Map<String, Integer> actualDist = new TreeMap<>();
        for (Map<String, Object> f : facts) {
            actualDist.merge((String) f.get("evidenceGrade"), 1, Integer::sum);
        }
        writeMap(responseId, "EVIDENCE_DISTRIBUTION", expectedDist, actualDist, "按已发布范围项的证据等级计数");

        // 3) 三类数量合计：单元格合计（含不披露单元的扣留量）== 快照合计
        Map<String, Object> totals = (Map<String, Object>) snapshot.get("quantityTotals");
        Map<String, Double> actualTotals = new LinkedHashMap<>();
        actualTotals.put("confirmedQty", r3(cells.stream().mapToDouble(c -> ((Number) c.get("confirmedQty")).doubleValue()).sum()));
        actualTotals.put("pendingQty", r3(cells.stream().mapToDouble(c -> ((Number) c.get("pendingQty")).doubleValue()).sum()));
        actualTotals.put("possibleQty", r3(cells.stream().mapToDouble(c -> ((Number) c.get("possibleQty")).doubleValue()).sum()));
        writeMap(responseId, "QUANTITY_TOTALS", totals, actualTotals, "不披露单元的数量计入扣留桶，合计仍可对平");

        // 4) 未定义字段：全部保持待复核，不静默丢弃
        List<Map<String, Object>> undefined = (List<Map<String, Object>>) snapshot.get("undefinedFields");
        long pendingReview = undefined.stream().filter(u -> "PENDING_REVIEW".equals(u.get("status"))).count();
        write(responseId, "UNDEFINED_FIELDS_PENDING", undefined.size(), (int) pendingReview,
                "未定义字段保持待复核，不进入聚合");
    }

    private void write(long responseId, String metric, Object expected, Object actual, String detail) {
        boolean balanced = Objects.equals(String.valueOf(expected), String.valueOf(actual));
        jdbc.update("INSERT INTO response_reconciliation(response_id, metric, expected_value, actual_value, status, detail) VALUES (?,?,?,?,?,?)",
                responseId, metric, String.valueOf(expected), String.valueOf(actual), balanced ? "BALANCED" : "MISMATCH", detail);
    }

    private void writeMap(long responseId, String metric, Map<String, ?> expected, Map<String, ?> actual, String detail) {
        Map<String, Double> e = toDoubleMap(expected);
        Map<String, Double> a = toDoubleMap(actual);
        boolean balanced = e.equals(a);
        jdbc.update("INSERT INTO response_reconciliation(response_id, metric, expected_value, actual_value, status, detail) VALUES (?,?,?,?,?,?)",
                responseId, metric, new TreeMap<>(e).toString(), new TreeMap<>(a).toString(),
                balanced ? "BALANCED" : "MISMATCH", detail);
    }

    private Map<String, Double> toDoubleMap(Map<String, ?> map) {
        Map<String, Double> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v))));
        return out;
    }

    private static double r3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
