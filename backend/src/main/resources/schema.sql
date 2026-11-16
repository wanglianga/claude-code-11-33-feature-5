-- 药品批次谱系数据质量与召回影响服务 · 审计级 schema
-- 所有事实均保留：原始载荷、接收时间、事件时间、来源修订号、文件哈希、解析版本
-- feature-5 监管答复：问题册 / 策略册 / 答复日快照 / 聚合单元格 / 核平 / 授权 / 审计

CREATE TABLE IF NOT EXISTS source_package (
    id                       BIGSERIAL,
    package_code             TEXT NOT NULL UNIQUE,
    source_system            TEXT NOT NULL,            -- QC_LAB / MES / WMS / LOGISTICS / INSTITUTION / MDM
    source_revision          TEXT NOT NULL,            -- 来源修订号
    payload_hash             TEXT NOT NULL,            -- 原始载荷 SHA-256
    parse_rule_version       TEXT NOT NULL,            -- 接收时解析规则版本
    parse_status             TEXT NOT NULL,            -- PARSED / PARSE_DIFF / FAILED
    received_at              TIMESTAMPTZ NOT NULL,     -- 接收时间
    event_time               TIMESTAMPTZ NOT NULL,     -- 事件时间
    supersedes_package_code  TEXT,
    status                   TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / REVOKED（来源版本撤销）
    revoked_at               TIMESTAMPTZ,
    revoke_reason            TEXT,
    payload                  JSONB NOT NULL,           -- 原始载荷（内部可见，含敏感字段）
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);
ALTER TABLE source_package ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE source_package ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ;
ALTER TABLE source_package ADD COLUMN IF NOT EXISTS revoke_reason TEXT;

CREATE TABLE IF NOT EXISTS parse_rule (
    version        TEXT PRIMARY KEY,
    effective_at   TIMESTAMPTZ NOT NULL,
    description    TEXT NOT NULL,
    affected_types TEXT NOT NULL                       -- 受影响事实类型，逗号分隔
);

CREATE TABLE IF NOT EXISTS raw_fact (
    id            BIGSERIAL PRIMARY KEY,
    package_id    BIGINT NOT NULL REFERENCES source_package(id),
    fact_type     TEXT NOT NULL,                       -- INSPECTION/FEEDING/RELEASE/SPLIT/BLEND/LOGISTICS/INSTITUTION_REPORT/MASTER_DATA
    fact_code     TEXT NOT NULL UNIQUE,
    event_time    TIMESTAMPTZ NOT NULL,
    payload       JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS batch_master (
    batch_code    TEXT PRIMARY KEY,                    -- 内部批号
    batch_type    TEXT NOT NULL,                       -- RAW / INTERMEDIATE / FINISHED
    product_code  TEXT,
    product_name  TEXT,
    unit          TEXT NOT NULL DEFAULT 'kg',
    quantity      NUMERIC(14,3),
    package_id    BIGINT REFERENCES source_package(id),
    attributes    JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- 机构主数据（监管答复地区维度的来源）
CREATE TABLE IF NOT EXISTS institution (
    inst_code   TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    region      TEXT NOT NULL
);

-- 核验请求（一次范围核验）：锁定检验更正版本 + 截止时间 + 输入快照
CREATE TABLE IF NOT EXISTS verification_run (
    id                           BIGSERIAL PRIMARY KEY,
    run_code                     TEXT NOT NULL UNIQUE,
    inspection_correction_version TEXT NOT NULL,       -- 选定的检验更正版本
    cutoff_at                    TIMESTAMPTZ NOT NULL, -- 截止时间
    rule_version                 TEXT NOT NULL,
    status                       TEXT NOT NULL,        -- LOCKED / PUBLISHED / SUPERSEDED
    input_snapshot               JSONB NOT NULL,       -- 锁定的来源包、哈希、解析状态
    created_by                   TEXT NOT NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 身份观测（供应商批号/内部批号/箱码/生产批号）
CREATE TABLE IF NOT EXISTS identity_record (
    id                   BIGSERIAL PRIMARY KEY,
    run_id               BIGINT NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    code_type            TEXT NOT NULL,                -- SUPPLIER_LOT / INTERNAL_LOT / BOX_CODE / PRODUCTION_LOT
    code_value           TEXT NOT NULL,
    observed_batch_code  TEXT,                         -- 观测指向的内部批次（NULL=未匹配）
    match_status         TEXT NOT NULL,                -- MATCHED / AMBIGUOUS / UNMATCHED
    UNIQUE (run_id, code_type, code_value)
);

CREATE TABLE IF NOT EXISTS identity_candidate (
    id                   BIGSERIAL PRIMARY KEY,
    identity_record_id   BIGINT NOT NULL REFERENCES identity_record(id) ON DELETE CASCADE,
    candidate_batch_code TEXT NOT NULL,
    confidence           TEXT NOT NULL,                -- HIGH / MEDIUM / LOW
    ambiguous            BOOLEAN NOT NULL DEFAULT false,
    evidence             JSONB NOT NULL DEFAULT '[]'::jsonb
);

-- 谱系边（每次核验独立编译，不可变快照）
CREATE TABLE IF NOT EXISTS lineage_edge (
    id                   BIGSERIAL PRIMARY KEY,
    run_id               BIGINT NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    edge_code            TEXT NOT NULL,
    upstream_batch       TEXT,                          -- 未匹配运输事实允许为空
    downstream_batch     TEXT NOT NULL,
    relation_type        TEXT NOT NULL,                -- FEEDING/SPLIT/BLEND/RELEASE/SHIPMENT
    source_fact_code     TEXT NOT NULL,
    source_package_code  TEXT NOT NULL,
    valid_from           TIMESTAMPTZ NOT NULL,
    valid_to             TIMESTAMPTZ,
    confidence_status    TEXT NOT NULL,                -- CONFIRMED / SUSPECTED / FROZEN
    review_required      BOOLEAN NOT NULL DEFAULT false,
    block_reason         TEXT,
    parse_rule_version   TEXT NOT NULL,
    quantity             NUMERIC(14,3),
    unit                 TEXT,
    edge_detail          JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- 批次数量质量状态（每批次每核验）
CREATE TABLE IF NOT EXISTS quantity_state (
    run_id              BIGINT NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    batch_code          TEXT NOT NULL,
    affected_flag       BOOLEAN NOT NULL DEFAULT false,
    confirmed_lower     NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 确认量下界
    pending_qty         NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 待核量
    possible_upper      NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 可能受影响量上界
    unattributed_delta  NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 未能归因的数量差
    unit                TEXT NOT NULL,
    detail              JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (run_id, batch_code)
);

-- 证据缺口工作台
CREATE TABLE IF NOT EXISTS evidence_gap (
    id                 BIGSERIAL PRIMARY KEY,
    run_id             BIGINT NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    gap_type           TEXT NOT NULL,  -- MISSING_FEEDING_EVIDENCE/DUPLICATE_BOX/QUANTITY_CONFLICT/UNMATCHED_BATCH/IDENTITY_AMBIGUITY/OVER_FEEDING/PARSE_DIFF
    severity           TEXT NOT NULL,  -- BLOCKER / WARNING
    subject_code       TEXT NOT NULL,
    node_name          TEXT,
    description        TEXT NOT NULL,
    blocks_conclusion  TEXT NOT NULL,  -- 阻断了哪个谱系结论
    evidence           JSONB NOT NULL DEFAULT '[]'::jsonb,
    status             TEXT NOT NULL DEFAULT 'OPEN',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 发布范围版本（来源断言与发布范围分别版本化）
CREATE TABLE IF NOT EXISTS release_version (
    id                        BIGSERIAL PRIMARY KEY,
    release_code              TEXT NOT NULL UNIQUE,
    version_no                INTEGER NOT NULL,
    run_id                    BIGINT NOT NULL REFERENCES verification_run(id),
    status                    TEXT NOT NULL,           -- PUBLISHED / SUPERSEDED
    predecessor_release_code  TEXT,
    diff_type                 TEXT,                    -- NONE/INSPECTION_CHANGED/NEW_LOGISTICS_EVIDENCE/PARSE_RULE_REVISED/MIXED
    diff_detail               JSONB NOT NULL DEFAULT '[]'::jsonb,
    basis                     JSONB NOT NULL,          -- 输入依据：包/哈希/解析版本/规则版本
    affected_conclusions      JSONB NOT NULL DEFAULT '[]'::jsonb,
    published_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 对外发布项（仅批次标识、机构/节点、数量区间、证据等级、可追溯链接）
CREATE TABLE IF NOT EXISTS release_item (
    id              BIGSERIAL PRIMARY KEY,
    release_id      BIGINT NOT NULL REFERENCES release_version(id) ON DELETE CASCADE,
    batch_code      TEXT NOT NULL,
    node_name       TEXT,
    confirmed_qty   NUMERIC(14,3) NOT NULL DEFAULT 0,
    pending_qty     NUMERIC(14,3) NOT NULL DEFAULT 0,
    possible_qty    NUMERIC(14,3) NOT NULL DEFAULT 0,
    lower_bound     NUMERIC(14,3) NOT NULL DEFAULT 0,
    upper_bound     NUMERIC(14,3) NOT NULL DEFAULT 0,
    unit            TEXT NOT NULL,
    evidence_grade  TEXT NOT NULL,                     -- CONFIRMED / PENDING / POSSIBLE
    trace_link      TEXT NOT NULL,
    conflict_flag   BOOLEAN NOT NULL DEFAULT false
);

-- 外部处置系统凭证
CREATE TABLE IF NOT EXISTS api_client (
    api_key   TEXT PRIMARY KEY,
    name      TEXT NOT NULL,
    enabled   BOOLEAN NOT NULL DEFAULT true
);

-- ==================================================================
-- feature-5 监管答复：最细仍可验证的汇总，而非整包脱敏导出
-- ==================================================================

-- 问题册：监管问询必须证明的事实
CREATE TABLE IF NOT EXISTS question_book (
    question_code      TEXT PRIMARY KEY,
    title              TEXT NOT NULL,
    required_dims      JSONB NOT NULL,        -- 必须证明的维度，如 ["region","month","evidenceGrade"]
    required_measures  JSONB NOT NULL,        -- 必须证明的度量，如 ["confirmedQty","reportCount"]
    description        TEXT NOT NULL DEFAULT '',
    status             TEXT NOT NULL DEFAULT 'ACTIVE'
);

-- 策略册：禁止字段、最小单元格、允许维度（含维度层级，供聚合求解）
CREATE TABLE IF NOT EXISTS policy_book (
    policy_code           TEXT PRIMARY KEY,
    version_no            INTEGER NOT NULL,
    forbidden_fields      JSONB NOT NULL,     -- 禁止出现的字段
    min_cell_size         INTEGER NOT NULL,   -- 最小单元格（机构数低于此值必须明确不披露）
    allowed_dimensions    JSONB NOT NULL,     -- 允许用于聚合的维度
    dimension_hierarchies JSONB NOT NULL,     -- 维度上卷层级，如 {"region":["national"],"month":["quarter","year"]}
    effective_from        TIMESTAMPTZ NOT NULL,
    status                TEXT NOT NULL DEFAULT 'ACTIVE',
    description           TEXT NOT NULL DEFAULT ''
);

-- 预批准模板：常见问询的固定地区/时间/证据层投影
CREATE TABLE IF NOT EXISTS response_template (
    template_code  TEXT PRIMARY KEY,
    name           TEXT NOT NULL,
    dimensions     JSONB NOT NULL,            -- 固定投影维度
    measures       JSONB NOT NULL,            -- 固定度量
    approved       BOOLEAN NOT NULL DEFAULT true,
    description    TEXT NOT NULL DEFAULT ''
);

-- 监管答复（一次生成）
CREATE TABLE IF NOT EXISTS regulatory_response (
    id                   BIGSERIAL PRIMARY KEY,
    response_code        TEXT NOT NULL UNIQUE,
    inquiry_type         TEXT NOT NULL,        -- TEMPLATE / COMPLEX
    template_code        TEXT,
    question_codes       JSONB NOT NULL,
    policy_code          TEXT NOT NULL,
    response_date        TIMESTAMPTZ NOT NULL, -- 答复日（快照只含此前已发布范围/机构回报/证据等级）
    release_id           BIGINT REFERENCES release_version(id),
    status               TEXT NOT NULL,        -- GENERATED / CANCELLED / SUPERSEDED
    dimensions           JSONB NOT NULL DEFAULT '[]'::jsonb,  -- 选定解的聚合维度
    information_loss     NUMERIC(14,3) NOT NULL DEFAULT 0,
    equivalent_solutions JSONB NOT NULL DEFAULT '[]'::jsonb,  -- 等价解及解释
    constraint_conflicts JSONB NOT NULL DEFAULT '[]'::jsonb,  -- 约束冲突及解释
    cancelled_reason     TEXT,
    created_by           TEXT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 答复日快照：冻结当时已发布范围、机构回报（去重后）与证据等级分布
CREATE TABLE IF NOT EXISTS response_snapshot (
    id                    BIGSERIAL PRIMARY KEY,
    response_id           BIGINT NOT NULL UNIQUE REFERENCES regulatory_response(id) ON DELETE CASCADE,
    release_code          TEXT NOT NULL,
    package_codes         JSONB NOT NULL,      -- 冻结来源包清单（撤销检查依据）
    facts                 JSONB NOT NULL,      -- 扁平化答复事实（单元格聚合的输入）
    raw_report_count      INTEGER NOT NULL,    -- 机构回报原始条数
    dedup_report_count    INTEGER NOT NULL,    -- 同机构重复回报合并后的条数
    merged_reports        JSONB NOT NULL,      -- 合并明细
    out_of_scope_reports  JSONB NOT NULL,      -- 不在已发布范围内的回报
    evidence_distribution JSONB NOT NULL,      -- 证据等级分布
    quantity_totals       JSONB NOT NULL,      -- 三类数量合计
    undefined_fields      JSONB NOT NULL,      -- 未定义字段（保持待复核）
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 聚合单元格：最小单元格不足时明确不披露（不用四舍五入掩盖机构）
CREATE TABLE IF NOT EXISTS response_cell (
    id                    BIGSERIAL PRIMARY KEY,
    response_id           BIGINT NOT NULL REFERENCES regulatory_response(id) ON DELETE CASCADE,
    cell_key              TEXT NOT NULL,
    dimensions            JSONB NOT NULL,      -- 维度值，如 {"region":"华东","month":"2026-11","evidenceGrade":"POSSIBLE"}
    institution_count     INTEGER NOT NULL,
    report_count          INTEGER NOT NULL,
    fact_count            INTEGER NOT NULL,
    confirmed_qty         NUMERIC(14,3),
    pending_qty           NUMERIC(14,3),
    possible_qty          NUMERIC(14,3),
    disclosed             BOOLEAN NOT NULL,
    non_disclosure_reason TEXT,                -- 不披露理由（明确写出，不四舍五入）
    UNIQUE (response_id, cell_key)
);

-- 核验器：输出同冻结来源的去重数与证据分布核平
CREATE TABLE IF NOT EXISTS response_reconciliation (
    id             BIGSERIAL PRIMARY KEY,
    response_id    BIGINT NOT NULL REFERENCES regulatory_response(id) ON DELETE CASCADE,
    metric         TEXT NOT NULL,        -- DEDUP_REPORT_COUNT / EVIDENCE_DISTRIBUTION / QUANTITY_TOTALS / UNDEFINED_FIELDS_PENDING
    expected_value TEXT NOT NULL,
    actual_value   TEXT NOT NULL,
    status         TEXT NOT NULL,        -- BALANCED / MISMATCH
    detail         TEXT NOT NULL DEFAULT ''
);

-- 授权：控制下载与重新生成；收回后二者失效，审计轨迹保留
CREATE TABLE IF NOT EXISTS response_authorization (
    auth_code     TEXT PRIMARY KEY,
    response_id   BIGINT NOT NULL REFERENCES regulatory_response(id) ON DELETE CASCADE,
    grantee       TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE / REVOKED
    created_by    TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at    TIMESTAMPTZ,
    revoke_reason TEXT
);

-- 审计轨迹（授权收回后仍保留）
CREATE TABLE IF NOT EXISTS response_audit (
    id          BIGSERIAL PRIMARY KEY,
    response_id BIGINT,
    action      TEXT NOT NULL,   -- RESPONSE_CREATED/RESPONSE_CANCELLED/AUTH_GRANTED/AUTH_REVOKED/DOWNLOAD/REGENERATED/SOURCE_REVOKED
    actor       TEXT NOT NULL,
    detail      TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fact_package ON raw_fact(package_id);
CREATE INDEX IF NOT EXISTS idx_edge_run ON lineage_edge(run_id);
CREATE INDEX IF NOT EXISTS idx_gap_run ON evidence_gap(run_id);
CREATE INDEX IF NOT EXISTS idx_candidate_record ON identity_candidate(identity_record_id);
CREATE INDEX IF NOT EXISTS idx_cell_response ON response_cell(response_id);
CREATE INDEX IF NOT EXISTS idx_audit_response ON response_audit(response_id);
