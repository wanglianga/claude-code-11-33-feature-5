package com.trace.recall.ingestion;

import java.time.OffsetDateTime;
import java.util.Map;

/** 来源包内解析出的原子事实。 */
public record RawFact(
        long id,
        long packageId,
        String factType,
        String factCode,
        OffsetDateTime eventTime,
        Map<String, Object> payload
) {}
