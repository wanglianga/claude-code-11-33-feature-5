package com.trace.recall.ingestion;

import java.time.OffsetDateTime;
import java.util.Map;

/** 来源包：原始载荷、接收/事件时间、来源修订号、文件哈希、解析规则版本、解析状态、撤销状态。 */
public record SourcePackage(
        long id,
        String packageCode,
        String sourceSystem,
        String sourceRevision,
        String payloadHash,
        String parseRuleVersion,
        String parseStatus,
        OffsetDateTime receivedAt,
        OffsetDateTime eventTime,
        String supersedesPackageCode,
        String status,
        Map<String, Object> payload
) {}
