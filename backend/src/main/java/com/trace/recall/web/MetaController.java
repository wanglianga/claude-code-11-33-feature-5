package com.trace.recall.web;

import com.trace.recall.config.Rows;
import com.trace.recall.ingestion.IngestionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final JdbcTemplate jdbc;
    private final IngestionService ingestion;

    public MetaController(JdbcTemplate jdbc, IngestionService ingestion) {
        this.jdbc = jdbc;
        this.ingestion = ingestion;
    }

    /** 检验更正版本（来自 QC_LAB 来源包的来源修订号）。 */
    @GetMapping("/inspection-versions")
    public List<Map<String, Object>> inspectionVersions() {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.queryForList("SELECT source_revision, received_at, supersedes_package_code FROM source_package "
                        + "WHERE source_system = 'QC_LAB' AND status = 'ACTIVE' ORDER BY received_at")
                .forEach(r -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("version", r.get("source_revision"));
                    v.put("receivedAt", r.get("received_at"));
                    v.put("supersedesPackageCode", r.get("supersedes_package_code"));
                    out.add(v);
                });
        return out;
    }

    @GetMapping("/parse-rules")
    public List<Map<String, Object>> parseRules() {
        return Rows.camelize(ingestion.parseRules());
    }
}
