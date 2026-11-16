package com.trace.recall.web;

import com.trace.recall.config.Rows;
import com.trace.recall.release.ReleaseService;
import com.trace.recall.verification.VerificationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RunController {

    private final VerificationService verification;
    private final ReleaseService releaseService;
    private final JdbcTemplate jdbc;

    public RunController(VerificationService verification, ReleaseService releaseService, JdbcTemplate jdbc) {
        this.verification = verification;
        this.releaseService = releaseService;
        this.jdbc = jdbc;
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() {
        return verification.listRuns();
    }

    @PostMapping("/runs")
    public Map<String, Object> createRun(@RequestBody Map<String, Object> body) {
        String version = required(body, "inspectionCorrectionVersion");
        String cutoff = required(body, "cutoffAt");
        String createdBy = String.valueOf(body.getOrDefault("createdBy", "质量分析员"));
        return verification.createRun(version, OffsetDateTime.parse(cutoff), createdBy);
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> runDetail(@PathVariable long id) {
        return verification.runDetail(id);
    }

    @GetMapping("/runs/{id}/batches/{batchCode}/trace")
    public Map<String, Object> batchTrace(@PathVariable long id, @PathVariable String batchCode) {
        return verification.batchTrace(id, batchCode);
    }

    @PostMapping("/runs/{id}/publish")
    public Map<String, Object> publish(@PathVariable long id) {
        return releaseService.publish(id);
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches() {
        return Rows.camelize(jdbc.queryForList("SELECT * FROM batch_master ORDER BY batch_code"), "attributes");
    }

    private String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) throw new IllegalArgumentException("缺少必填字段: " + key);
        return v.toString();
    }
}
