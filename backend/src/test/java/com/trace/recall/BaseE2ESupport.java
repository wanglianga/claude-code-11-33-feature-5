package com.trace.recall;

import com.trace.recall.ingestion.Seeder;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class BaseE2ESupport {

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected Seeder seeder;
    @Autowired
    protected TestRestTemplate rest;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE source_package, parse_rule, raw_fact, batch_master, institution, "
                + "verification_run, identity_record, identity_candidate, lineage_edge, quantity_state, evidence_gap, "
                + "release_version, release_item, api_client, question_book, policy_book, response_template, "
                + "regulatory_response, response_snapshot, response_cell, response_reconciliation, "
                + "response_authorization, response_audit RESTART IDENTITY CASCADE");
        seeder.seed();
    }

    @SuppressWarnings("unchecked")
    protected long createRun(String version, String cutoff) {
        Map<String, Object> res = rest.postForObject("/api/runs", Map.of(
                "inspectionCorrectionVersion", version,
                "cutoffAt", cutoff,
                "createdBy", "端到端测试"), Map.class);
        return ((Number) res.get("runId")).longValue();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> publish(long runId) {
        return rest.postForObject("/api/runs/" + runId + "/publish", Map.of(), Map.class);
    }

    protected Map<String, Object> runDetail(long runId) {
        return rest.getForObject("/api/runs/" + runId, Map.class);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> findBy(List<Map<String, Object>> rows, String key, Object value) {
        return rows.stream().filter(r -> value.equals(r.get(key))).findFirst()
                .orElseThrow(() -> new AssertionError("未找到 " + key + "=" + value + " 的行"));
    }

    protected double num(Object v) {
        return ((Number) v).doubleValue();
    }
}
