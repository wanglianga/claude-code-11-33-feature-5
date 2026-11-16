package com.trace.recall.ingestion;

import com.trace.recall.config.Json;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 接入登记台：来源包与原子事实的读取、解析状态判定、来源版本撤销。
 * 解析状态按“截至某时点有效的最新解析规则”动态判定：
 * 包哈希有效但解析规则落后且新版规则影响该包事实类型 → PARSE_DIFF。
 */
@Service
public class IngestionService {

    /** 来源版本被撤销（监管答复生成随之取消）。 */
    public record SourceRevokedEvent(String packageCode, String reason, String actor) {}

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public IngestionService(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    private static final RowMapper<SourcePackage> PACKAGE_MAPPER = (rs, i) -> new SourcePackage(
            rs.getLong("id"),
            rs.getString("package_code"),
            rs.getString("source_system"),
            rs.getString("source_revision"),
            rs.getString("payload_hash"),
            rs.getString("parse_rule_version"),
            rs.getString("parse_status"),
            rs.getObject("received_at", OffsetDateTime.class),
            rs.getObject("event_time", OffsetDateTime.class),
            rs.getString("supersedes_package_code"),
            rs.getString("status"),
            Json.readMap(rs.getString("payload"))
    );

    private static final RowMapper<RawFact> FACT_MAPPER = (rs, i) -> new RawFact(
            rs.getLong("id"),
            rs.getLong("package_id"),
            rs.getString("fact_type"),
            rs.getString("fact_code"),
            rs.getObject("event_time", OffsetDateTime.class),
            Json.readMap(rs.getString("payload"))
    );

    public List<SourcePackage> allPackages() {
        return jdbc.query("SELECT * FROM source_package ORDER BY received_at", PACKAGE_MAPPER);
    }

    public Optional<SourcePackage> byCode(String code) {
        List<SourcePackage> r = jdbc.query("SELECT * FROM source_package WHERE package_code = ?", PACKAGE_MAPPER, code);
        return r.stream().findFirst();
    }

    /** 截止时间前接收且未撤销的来源包（核验快照的输入边界）。 */
    public List<SourcePackage> packagesReceivedBefore(OffsetDateTime cutoff) {
        return jdbc.query("SELECT * FROM source_package WHERE received_at <= ? AND status = 'ACTIVE' ORDER BY received_at",
                PACKAGE_MAPPER, cutoff);
    }

    public List<RawFact> factsOfPackage(long packageId) {
        return jdbc.query("SELECT * FROM raw_fact WHERE package_id = ? ORDER BY id", FACT_MAPPER, packageId);
    }

    public List<RawFact> factsOfPackages(Collection<Long> packageIds) {
        if (packageIds.isEmpty()) return List.of();
        String marks = String.join(",", Collections.nCopies(packageIds.size(), "?"));
        return jdbc.query("SELECT * FROM raw_fact WHERE package_id IN (" + marks + ") ORDER BY id",
                FACT_MAPPER, packageIds.toArray());
    }

    /** 截至 asOf 有效的解析规则（按生效时间升序）。 */
    public List<Map<String, Object>> parseRules() {
        return jdbc.query("SELECT version, effective_at, description, affected_types FROM parse_rule ORDER BY effective_at",
                (rs, i) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("version", rs.getString("version"));
                    r.put("effective_at", rs.getObject("effective_at", OffsetDateTime.class));
                    r.put("description", rs.getString("description"));
                    r.put("affected_types", rs.getString("affected_types"));
                    return r;
                });
    }

    /**
     * 判定某来源包在 asOf 时点的解析状态：
     * 包的解析规则落后于 asOf 前生效的最新规则，且新规则影响该包的事实类型 → PARSE_DIFF，否则 PARSED。
     */
    public String parseStatusAt(String packageRuleVersion, Set<String> packageFactTypes, OffsetDateTime asOf) {
        List<Map<String, Object>> rules = parseRules().stream()
                .filter(r -> !((OffsetDateTime) r.get("effective_at")).isAfter(asOf))
                .toList();
        if (rules.isEmpty()) return "PARSED";
        String newest = (String) rules.get(rules.size() - 1).get("version");
        if (newest.equals(packageRuleVersion)) return "PARSED";
        // 包规则之后生效的规则
        boolean after = false;
        for (Map<String, Object> r : rules) {
            String v = (String) r.get("version");
            if (v.equals(packageRuleVersion)) { after = true; continue; }
            if (!after) continue;
            Set<String> affected = new HashSet<>(Arrays.asList(((String) r.get("affected_types")).split(",")));
            if (!Collections.disjoint(affected, packageFactTypes)) return "PARSE_DIFF";
        }
        return "PARSED";
    }

    /** 来源版本撤销：标记 REVOKED 并发布事件（监管答复生成方监听后取消生成）。 */
    @Transactional
    public Map<String, Object> revokePackage(String packageCode, String reason, String actor) {
        SourcePackage pkg = byCode(packageCode).orElseThrow(() -> new NoSuchElementException("来源包不存在: " + packageCode));
        if ("REVOKED".equals(pkg.status())) {
            throw new IllegalStateException("来源包 " + packageCode + " 已处于撤销状态");
        }
        jdbc.update("UPDATE source_package SET status='REVOKED', revoked_at=now(), revoke_reason=? WHERE package_code=?",
                reason, packageCode);
        events.publishEvent(new SourceRevokedEvent(packageCode, reason, actor));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("packageCode", packageCode);
        out.put("status", "REVOKED");
        out.put("revokeReason", reason);
        return out;
    }
}
