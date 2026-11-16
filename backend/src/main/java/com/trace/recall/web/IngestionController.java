package com.trace.recall.web;

import com.trace.recall.ingestion.IngestionService;
import com.trace.recall.ingestion.SourcePackage;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/source-packages")
public class IngestionController {

    private final IngestionService ingestion;

    public IngestionController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return ingestion.allPackages().stream().map(this::view).toList();
    }

    @GetMapping("/{code}")
    public Map<String, Object> detail(@PathVariable String code) {
        SourcePackage p = ingestion.byCode(code).orElseThrow(() -> new NoSuchElementException("来源包不存在: " + code));
        Map<String, Object> v = view(p);
        v.put("payload", p.payload());
        return v;
    }

    /** 来源版本撤销：取消依赖该来源的监管答复生成（审计轨迹保留）。 */
    @PostMapping("/{code}/revoke")
    public Map<String, Object> revoke(@PathVariable String code, @RequestBody Map<String, Object> body) {
        String reason = String.valueOf(body.getOrDefault("reason", "来源版本撤销"));
        String actor = String.valueOf(body.getOrDefault("actor", "质量分析员"));
        return ingestion.revokePackage(code, reason, actor);
    }

    private Map<String, Object> view(SourcePackage p) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("packageCode", p.packageCode());
        v.put("sourceSystem", p.sourceSystem());
        v.put("sourceRevision", p.sourceRevision());
        v.put("payloadHash", p.payloadHash());
        v.put("parseRuleVersion", p.parseRuleVersion());
        v.put("parseStatus", p.parseStatus());
        v.put("receivedAt", p.receivedAt());
        v.put("eventTime", p.eventTime());
        v.put("supersedesPackageCode", p.supersedesPackageCode());
        v.put("status", p.status());
        return v;
    }
}
