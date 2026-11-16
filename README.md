# 药品批次谱系数据质量与召回影响服务（含监管答复）

面向药品质量团队的**批次谱系数据质量与召回影响核验系统**，并在此之上提供**监管答复**能力：

- **基础域**：检验结果更正后，回答截至某个发布时点哪些成品批次可能使用了受影响原料、数量能确认到什么程度（确认量下界 / 待核量 / 可能受影响量上界 / 未能归因差额）、哪些机构出现在可验证的流转证据中、每项结论缺什么证据。来源断言与发布范围分别版本化。
- **监管答复（本次特性）**：监管答复**不是**把历史召回资料整包脱敏导出，而是回答法规问题所需的**最细、仍可验证的汇总**。答复日快照只含当时已发布的范围、机构回报（去重后）和证据等级；问题册列出必须证明的事实，策略册列出禁止字段、最小单元格与允许维度，核验器再把输出同冻结来源的去重数和证据分布核平。

技术栈：**Vue 3 + Spring Boot 3.5 + PostgreSQL 18**。

> 服务边界：本系统只做数据质量核验、范围发布与监管答复汇总，**不隔离、销毁、退运或调拨药品，不向机构下达通知，不生成付款、补偿或审批决定**。

---

## 1. 监管答复设计（feature-5）

### 1.1 生成路径：模板投影 vs 复杂求解

| | 常见问询（预批准模板） | 复杂问询（最细可行聚合） |
| --- | --- | --- |
| 输入 | 问题册中能被模板覆盖的问题 | 任意问题（含非常规模板问题） |
| 聚合方式 | 固定地区 × 时间 × 证据层投影 | 在问题覆盖与披露约束下求解最细可行聚合 |
| 优点 | 审阅快、结果唯一 | 信息损失更少 |
| 代价 | 对特殊问题可能过度隐藏 | 必须解释多个等价解及约束冲突 |

- **不披露原则**：任何方案发现机构数低于最小单元格（策略册 `min_cell_size=2`）的小单元，一律**明确不披露**并写出理由，**不用四舍五入掩盖机构**。不披露单元的数量计入核平扣留桶，合计仍可对平。
- **信息损失**：不披露一条事实计 1.0；维度上卷一级每条事实计 0.4。复杂求解枚举“原维度 + 各级上卷”候选，取损失最小者；并列最优全部列为**等价解**并逐一解释。
- **约束冲突**：问题要求的维度不在允许维度（如机构单体）、或度量属于禁止字段（如联系人）时，生成仍在允许集合内进行，但冲突逐条列出并标明“该问题覆盖不完整”。

### 1.2 全生命周期规则

| 规则 | 实现 |
| --- | --- |
| 答复日快照 | 取“数据截止时间 ≤ 答复日”的最新发布版本；机构回报限答复日前接收且来源未撤销的包；快照 JSON 冻结于 `response_snapshot` |
| 重复回报合并 | 同机构 + 同批次 + 同数量的重复回报在核平前合并为一条，合并明细入快照（RPT-003 并入 RPT-001） |
| 未定义字段 | 回报载荷中字典外字段（wardContact、driverPhone）保持 `PENDING_REVIEW`，不进入聚合、不静默丢弃 |
| 来源版本撤销 | `POST /api/source-packages/{code}/revoke` → 快照中含该来源且状态为已生成的答复全部 `CANCELLED`，写审计 |
| 授权收回 | `POST /api/regulatory/authorizations/{code}/revoke` → 之后下载与重新生成一律 403；`response_audit` 审计轨迹保留不删 |
| 核验器核平 | 四项指标：机构回报去重数（单元格覆盖 + 范围外 = 快照去重数）、证据等级分布、三类数量合计、未定义字段待复核数 |

---

## 2. 基础域设计：来源断言与发布范围分别版本化

| 变化类型 | 识别方式 | 系统行为 |
| --- | --- | --- |
| 检验结论变了 `INSPECTION_CHANGED` | 两次核验选择的检验更正版本不同 | 生成新发布版本，旧版本 `SUPERSEDED` 只读保留 |
| 新物流/机构凭证补到 `NEW_LOGISTICS_EVIDENCE` | 新快照中出现旧快照没有的物流/机构包 | 迟到回报只进入新版本，不改变历史答复 |
| 解析规则修订 `PARSE_RULE_REVISED` | 包哈希有效但解析版本落后且字段语义受影响 | 标记 `PARSE_DIFF`，要求重新审阅关系边，不回填旧范围 |

六条强制规则的实现位置：

| 业务规则 | 实现 |
| --- | --- |
| 同一内部批号对应两个供应商批号且无可验证凭证 → 保留双候选、不合并 | `IdentityResolutionService`（缺口 `IDENTITY_AMBIGUITY`） |
| 拆分投料合计超过原料可用量 → 冻结整条分配链确认量 | `QuantityQualityEngine`（缺口 `OVER_FEEDING`，边 `FROZEN`，冻结沿 BFS 传播到全部下游代际） |
| 机构回报与物流签收不一致且双凭证有效 → 数量冲突，任一来源不得覆盖另一来源 | `QuantityQualityEngine`（缺口 `QUANTITY_CONFLICT`，节点同时保留两个数字） |
| 检验更正发生在发布之后 → 新候选版本，旧版本只读保留 | `ReleaseService` |
| 箱码在两条互不相连运输记录中重复 → 重复载体证据 | `LineageCompiler`（缺口 `DUPLICATE_BOX`，两条运输边降级 `SUSPECTED`） |
| 哈希通过但解析规则升级导致字段含义变化 → 解析差异 | `IngestionService.parseStatusAt` + `LineageCompiler`（仅降级受影响事实类型的边） |

另有 `MISSING_FEEDING_EVIDENCE`（缺失投料凭证）、`UNMATCHED_BATCH`（无法匹配批号）两类缺口。

---

## 3. 目录结构

```
.
├── backend/                 # Spring Boot 服务（JDK 25 编译目标 21）
│   ├── src/main/java/com/trace/recall/
│   │   ├── ingestion/       # 接入登记台（解析状态判定、来源撤销）+ 演示数据 Seeder
│   │   ├── identity/        # 批次身份解析（歧义保留）
│   │   ├── lineage/         # 谱系编译器（有向关系边）
│   │   ├── quality/         # 数量质量引擎 + 证据缺口
│   │   ├── verification/    # 核验编排（快照锁定）
│   │   ├── release/         # 范围发布器（版本化、三类差异）
│   │   ├── regulatory/      # 监管答复：快照/聚合求解/核验器/授权/审计
│   │   ├── config/          # CORS、外部 API Key 拦截器、JSONB/行转换
│   │   └── web/             # 内部 /api、外部 /external、监管答复 /api/regulatory 控制器
│   ├── src/main/resources/  # application.yml、schema.sql
│   └── src/test/            # 端到端规则测试（独立 recall_test schema）
├── frontend/                # Vue 3 + Vite + vue-router
│   └── src/views/           # 核验台/接入台/核验详情/批次钻取/发布版本/监管答复/外部接口
├── docker-compose.yml       # 一键启动 postgres + backend + frontend
└── README.md
```

---

## 4. 本地启动

### 方式 A：Docker Compose 一键启动

```bash
docker compose up --build
```

- 前端（Nginx）：http://localhost:8081
- 后端 API：http://localhost:8080
- PostgreSQL：localhost:5432（库/用户/密码 `ccdev`）
- 后端首次启动自动建表并播种演示数据。

### 方式 B：本地开发模式

前置：PostgreSQL 已在 127.0.0.1:5432 运行，库/用户/密码为 `ccdev`。

```bash
# 1) 后端（终端 1）
cd backend
mvn spring-boot:run
# 启动后自动执行 schema.sql 并播种 8 个来源包、12 个批次主数据、4 家机构、问题册/策略册/模板

# 2) 前端（终端 2）
cd frontend
npm install
npm run dev
# 打开 http://localhost:8081 （Vite 已配置 /api 与 /external 代理到 8080）
```

### 测试账号 / 凭证

| 用途 | 凭证 |
| --- | --- |
| 内部质量工作台 | 无需登录（内网工作台），默认发起人 `质量分析员-张某` |
| 外部处置系统 API Key（请求头 `X-API-Key`） | `ext-trace-key-2026`（可用环境变量 `EXTERNAL_API_KEY` 覆盖） |
| 监管答复授权 | 在「监管答复」页对某份答复点“发放授权”生成 `AUTH-XXXXXXXX` 授权码 |
| 数据库 | 库/用户/密码 `ccdev` |

---

## 5. 演示数据与操作路径

时间线（2026-11，UTC+8）：

| 日期 | 事件 |
| --- | --- |
| 11-01 | 主数据；RM-A02 同时被供应商批号 SUP-LOT-22 / SUP-LOT-23 观测，无装箱凭证（**身份歧义**） |
| 11-02 | 检验 V1 `QC-REV-1`：RM-A03、RM-A04 不合格 |
| 11-03 | 投料/放行：RM-A04 拆分合计 75kg > 可用 60kg（**超额冻结**，未归因差 15kg）；RM-A02→IM-B30 缺投料凭证 |
| 11-06 | 物流：箱码 BOX-9 在两条互不相连运输记录重复（**重复载体**）；生产批号 PL-UNKNOWN-9 无法匹配 |
| 11-07 | 机构回报：HOSP-乙 回报 46kg vs 物流签收 30kg（**数量冲突**）；RPT-003 是 RPT-001 的**重复回报** |
| 11-11 | 解析规则升级 PARSE-2.0（影响 LOGISTICS、BLEND 字段语义）→ 两个物流包触发**解析差异** |
| 11-12 | 检验更正 V2 `QC-REV-2`：RM-A01 改判不合格、RM-A03 复检合格（RM-A04 维持不合格） |
| 11-13 | 迟到机构回报：含华南唯一机构 HOSP-丁（**单机构单元**）与未定义字段 wardContact/driverPhone |
| **11-10 18:00** | **截止时间 #1**，检验版本 QC-REV-1（快照 6 个包） |
| **11-14 18:00** | **截止时间 #2**，检验版本 QC-REV-2（快照 8 个包） |

### 在页面上操作

1. 「范围核验台」选 `QC-REV-1` + 截止 #1 → 锁定快照并核验；再发起 `QC-REV-2` + 截止 #2；
2. 核验详情查看数量区间、谱系图（红虚线=冻结、黄虚线=待核）、身份候选、证据缺口、输入快照；点批次「展开证据」下钻原始载荷；
3. 对两次核验分别「发布范围新版本」→「发布版本管理」查看 REL-001 `SUPERSEDED` 与 REL-002 的三类差异；
4. 「监管答复」页：
   - 选 **常见问询（模板）** + Q-001 + 答复日 2026-11-15 → 生成：三个单机构单元（华东 CONFIRMED、华北、华南）**明确不披露**；
   - 选 **复杂问询** + Q-004 → 最细可行聚合（region × evidenceGrade），并列出一个**等价解**（上卷全国）；
   - 选 **复杂问询** + Q-003 → 两条**约束冲突**（机构维度不允许、联系人属禁止字段）；
   - 对某份答复“发放授权”→ 下载成功；“收回”授权 → 下载与重新生成均 403，审计轨迹仍在；
5. 「接入登记台」对 INS-PKG-2 点“撤销”→ 依赖它的已生成答复全部**取消生成**（审计保留）；
6. 「外部接口（脱敏）」用 `ext-trace-key-2026` 查看外部摘要，并可验证无 Key 返回 401。

---

## 6. HTTP 接口摘要

### 内部工作台（`/api`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/source-packages` / `/api/source-packages/{code}` | 来源包清单与原始载荷 |
| POST | `/api/source-packages/{code}/revoke` | 来源版本撤销（联动取消监管答复生成） |
| GET | `/api/meta/inspection-versions` / `/api/meta/parse-rules` | 检验版本与解析规则 |
| POST | `/api/runs` | 发起核验：`inspectionCorrectionVersion`、`cutoffAt`、`createdBy` |
| GET | `/api/runs` / `/api/runs/{id}` | 核验列表与详情（快照/数量/谱系边/身份/缺口） |
| GET | `/api/runs/{id}/batches/{batchCode}/trace` | 批次全链路证据钻取 |
| POST | `/api/runs/{id}/publish` | 发布范围新版本 |
| GET | `/api/releases` | 发布版本（含依据、差异、发布项） |
| GET | `/api/batches` | 批次主数据 |

### 监管答复（`/api/regulatory`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/regulatory/meta` | 问题册、策略册（禁止字段/最小单元格/允许维度）、预批准模板 |
| POST | `/api/regulatory/responses` | 生成答复：`inquiryType`(TEMPLATE/COMPLEX)、`templateCode`、`questionCodes`、`responseDate` |
| GET | `/api/regulatory/responses` / `/api/regulatory/responses/{id}` | 答复列表与详情（单元格/核平/等价解/约束冲突/快照） |
| POST | `/api/regulatory/responses/{id}/authorizations` | 发放授权（返回授权码） |
| POST | `/api/regulatory/authorizations/{authCode}/revoke` | 收回授权 |
| GET | `/api/regulatory/responses/{id}/download?authCode=` | 下载答复（授权收回后 403） |
| POST | `/api/regulatory/responses/{id}/regenerate` | 重新生成（需有效授权，旧答复置 SUPERSEDED） |
| GET | `/api/regulatory/audit?responseId=` | 审计轨迹（授权收回后仍保留） |

### 外部处置系统（`/external`，必须 `X-API-Key`，仅脱敏摘要）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/external/releases` | 可访问发布版本列表 |
| GET | `/external/releases/{code}` | 范围摘要（白名单字段） |

外部响应字段白名单：`batchCode, nodeName, confirmedQty, pendingQty, possibleQty, lowerBound, upperBound, unit, evidenceGrade, traceLink, conflictFlag`；原始 payload、联系人（contactPerson/phone/wardContact/driverPhone）在任何情况下都不会出现。

---

## 7. 测试与验证方式

### 自动化测试

```bash
cd backend
mvn test
```

在独立 schema `recall_test` 中执行 6 个端到端测试（真实 PostgreSQL）：

- `RecallServiceE2ETest`：身份歧义 ≥2 候选且无供应商批号合并边；RM-A04 确认下界 60 + 未归因差 15、冻结边 ≥3、FG-C03 确认量 0；HOSP-乙 节点同时保留 30 与 46；BOX-9 两条运输边均降级；截止 #1 无 PARSE_DIFF、截止 #2 有 PARSE_DIFF 且 MES 投料边不受影响；两次核验快照 6/8 个包；REL-001 `SUPERSEDED`、REL-002 差异含三类变化；外部接口无 Key/错 Key 401、正确 Key 仅白名单字段。
- `RegulatoryResponseE2ETest`（feature-5 验收）：
  1. **单机构单元**：模板答复的 3 个单机构单元全部明确不披露（数量不出现在输出、理由含“最小单元格”），双机构单元正常披露；
  2. **重复回报**：RPT-003 并入 RPT-001，去重数 6 < 原始 7，核平指标 `DEDUP_REPORT_COUNT` 平衡；
  3. **来源失效**：撤销 INS-PKG-2 后相关答复 `CANCELLED`，审计含 `SOURCE_REVOKED` 与 `RESPONSE_CANCELLED`；
  4. **非常规模板问题**：Q-003 走模板被 400 拒绝，走复杂问询产出 2 条约束冲突；Q-004 复杂求解选定最细维度并列出 1 个等价解；
  5. **授权收回**：收回后下载与重新生成均 403，审计轨迹（发放/下载/收回）完整保留；有效授权可重新生成，旧答复 `SUPERSEDED`。

### 已实际执行的验证（本工作区）

- `mvn test`：**Tests run: 6, Failures: 0, Errors: 0 — BUILD SUCCESS**；
- 后端启动后两次核验（RUN-001=QC-REV-1/11-10、RUN-002=QC-REV-2/11-14）并发布：REL-001 被标记 `SUPERSEDED`，REL-002 `diff_type=MIXED:INSPECTION_CHANGED+NEW_LOGISTICS_EVIDENCE+PARSE_RULE_REVISED+NEW_SOURCE_PACKAGE`；
- 监管答复冒烟（curl 实际执行）：模板答复 RESP-001 信息损失 3.0（3 个单机构单元不披露）；复杂答复 RESP-002 选定 `[region, evidenceGrade]`、列出 1 个等价解、四项核平指标全部 `BALANCED`；Q-003 复杂问询产出 2 条约束冲突；授权 `AUTH-xxx` 下载 200 → 收回后下载/重新生成均 403；撤销 INS-PKG-2 后 3 份已生成答复全部 `CANCELLED` 且审计轨迹完整；
- 外部接口：无 Key 401；正确 Key 响应 5 个发布项且仅含白名单字段；
- 前端 `npm run build` 成功，`npm run dev` 启动后页面与 `/api`、`/external` 代理联通（首页 200、代理 200）。

> 说明：当前容器未安装 docker 二进制，`docker-compose.yml` 已按标准 Compose 规范编写但未在本容器内实际构建；本地开发模式与自动化测试均已实际执行通过。
