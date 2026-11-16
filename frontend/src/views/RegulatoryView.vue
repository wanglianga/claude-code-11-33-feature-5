<template>
  <div>
    <h2 class="page-title">监管答复工作台</h2>
    <p class="page-desc">
      监管答复不是整包脱敏导出，而是回答法规问题所需的<b>最细、仍可验证的汇总</b>：答复日快照只含当时已发布的范围、机构回报（去重后）与证据等级；
      小单元无法安全披露时<b>明确不披露</b>，不用四舍五入掩盖机构；核验器把输出同冻结来源的去重数与证据分布核平。
    </p>

    <!-- 元数据：问题册 / 策略册 / 预批准模板 -->
    <div class="card">
      <h3>问题册 · 策略册 · 预批准模板</h3>
      <table v-if="meta.questions">
        <thead><tr><th>问题</th><th>必须证明的事实（维度）</th><th>度量</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-for="q in meta.questions" :key="q.questionCode">
            <td class="mono">{{ q.questionCode }} · {{ q.title }}</td>
            <td><span v-for="d in q.requiredDims" :key="d" class="tag cyan" style="margin-right:4px">{{ dimLabel(d) }}</span></td>
            <td><span v-for="m in q.requiredMeasures" :key="m" class="tag gray" style="margin-right:4px">{{ m }}</span></td>
            <td class="small muted">{{ q.description }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="policy" class="small" style="margin-top:10px">
        策略册 <b class="mono">{{ policy.policyCode }}</b>：最小单元格 <b>{{ policy.minCellSize }}</b> 家机构 ·
        允许维度 <span v-for="d in policy.allowedDimensions" :key="d" class="tag cyan" style="margin:0 3px">{{ dimLabel(d) }}</span> ·
        禁止字段 <span v-for="f in policy.forbiddenFields" :key="f" class="tag red" style="margin:0 3px">{{ f }}</span>
      </p>
      <p v-if="meta.templates" class="small">
        预批准模板：<span v-for="t in meta.templates" :key="t.templateCode" class="tag green" style="margin-right:6px">
          {{ t.templateCode }}（{{ (t.dimensions || []).map(dimLabel).join('×') }}）</span>
      </p>
    </div>

    <!-- 发起问询 -->
    <div class="card">
      <h3>发起监管问询</h3>
      <div class="form-row">
        <label class="field">问询方式
          <select v-model="form.inquiryType">
            <option value="TEMPLATE">常见问询（预批准模板投影）</option>
            <option value="COMPLEX">复杂问询（最细可行聚合求解）</option>
          </select>
        </label>
        <label class="field" v-if="form.inquiryType === 'TEMPLATE'">预批准模板
          <select v-model="form.templateCode">
            <option v-for="t in meta.templates" :key="t.templateCode" :value="t.templateCode">{{ t.templateCode }} · {{ t.name }}</option>
          </select>
        </label>
        <label class="field">答复日（快照只含此前已发布范围与回报）
          <select v-model="form.responseDate">
            <option value="2026-11-11T18:00:00+08:00">2026-11-11 18:00（第一次发布后）</option>
            <option value="2026-11-15T18:00:00+08:00">2026-11-15 18:00（第二次发布后）</option>
          </select>
        </label>
        <label class="field">发起人 <input v-model="form.createdBy" style="width:150px" /></label>
      </div>
      <div class="form-row" style="margin-top:8px">
        <span class="field">选择问题（问题册）：</span>
        <label v-for="q in meta.questions" :key="q.questionCode" style="margin-right:14px;font-size:13px">
          <input type="checkbox" :value="q.questionCode" v-model="form.questionCodes" style="margin-right:4px" />
          <span class="mono">{{ q.questionCode }}</span> {{ q.title }}
        </label>
        <button class="btn" :disabled="creating" @click="create">{{ creating ? '生成中…' : '生成监管答复' }}</button>
      </div>
      <p v-if="error" class="small" style="color:var(--red);margin-top:8px">{{ error }}</p>
      <p v-if="notice" class="small" style="color:var(--green);margin-top:8px">{{ notice }}</p>
    </div>

    <!-- 答复列表 -->
    <div class="card">
      <h3>监管答复（{{ responses.length }}）</h3>
      <table>
        <thead><tr>
          <th>编号</th><th>方式</th><th>问题</th><th>答复日</th><th>状态</th>
          <th class="right">信息损失</th><th>说明</th><th></th>
        </tr></thead>
        <tbody>
          <tr v-for="r in responses" :key="r.id">
            <td class="mono">{{ r.responseCode }}</td>
            <td><span class="tag" :class="r.inquiryType === 'TEMPLATE' ? 'green' : 'purple'">{{ r.inquiryType === 'TEMPLATE' ? '模板' : '复杂' }}</span></td>
            <td class="mono small">{{ (r.questionCodes || []).join(', ') }}</td>
            <td class="nowrap">{{ fmt(r.responseDate) }}</td>
            <td><span class="tag" :class="statusTag(r.status)">{{ statusLabel(r.status) }}</span></td>
            <td class="right">{{ r.informationLoss }}</td>
            <td class="small muted" style="max-width:340px">{{ r.cancelledReason || '' }}</td>
            <td><a href="#" @click.prevent="open(r.id)">展开 →</a></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 答复详情 -->
    <template v-if="detail">
      <div class="card">
        <h3>答复 {{ detail.response.responseCode }} · 答复日快照
          <span class="tag" :class="statusTag(detail.response.status)" style="margin-left:8px">{{ statusLabel(detail.response.status) }}</span>
        </h3>
        <p class="small">
          快照只含答复日时已发布范围 <b class="mono">{{ detail.snapshot.releaseCode }}</b>、机构回报与证据等级 ·
          冻结来源包 <b>{{ detail.snapshot.packageCodes.length }}</b> 个 ·
          机构回报：原始 <b>{{ detail.snapshot.rawReportCount }}</b> 条 → 去重合并后 <b>{{ detail.snapshot.dedupReportCount }}</b> 条 ·
          证据分布 <span v-for="(v, k) in detail.snapshot.evidenceDistribution" :key="k" class="tag" :class="k === 'CONFIRMED' ? 'green' : 'amber'" style="margin:0 3px">{{ k }} × {{ v }}</span>
        </p>
        <p class="small" v-if="detail.snapshot.mergedReports.length">
          重复回报合并：<span v-for="m in detail.snapshot.mergedReports" :key="m.keptReport" class="tag amber" style="margin-right:6px">
            {{ m.institution }}/{{ m.batchCode }}：{{ m.mergedDuplicates.join('、') }} 并入 {{ m.keptReport }}</span>
        </p>
        <p class="small" v-if="detail.snapshot.outOfScopeReports.length">
          已发布范围外回报：<span v-for="r in detail.snapshot.outOfScopeReports" :key="r.reportCode" class="tag gray" style="margin-right:6px">
            {{ r.reportCode }}（{{ r.institution }}/{{ r.batchCode }}）</span>
        </p>
        <p class="small" v-if="detail.snapshot.undefinedFields.length" style="color:var(--amber)">
          未定义字段（保持待复核，不进入聚合）：
          <span v-for="u in detail.snapshot.undefinedFields" :key="u.reportCode + u.field" class="tag amber" style="margin-right:6px">
            {{ u.reportCode }}.{{ u.field }}</span>
        </p>
        <p class="small muted">{{ detail.response.inquiryType === 'TEMPLATE' ? '模板投影：' + detail.response.templateCode : '复杂求解' }} ·
          聚合维度 {{ (detail.response.dimensions || []).map(dimLabel).join(' × ') || '（总计）' }} · 信息损失 {{ detail.response.informationLoss }}</p>
      </div>

      <div class="card">
        <h3>聚合单元格（最小单元格 {{ policy ? policy.minCellSize : '' }} 家机构）</h3>
        <table>
          <thead><tr>
            <th>单元维度</th><th class="right">机构数</th><th class="right">确认量</th><th class="right">待核量</th>
            <th class="right">可能上界</th><th class="right">回报数</th><th>披露判定</th>
          </tr></thead>
          <tbody>
            <tr v-for="c in detail.cells" :key="c.cellKey">
              <td><span v-for="(v, k) in c.dimensions" :key="k" class="tag cyan" style="margin-right:4px">{{ dimLabel(k) }}={{ v }}</span></td>
              <td class="right">{{ c.institutionCount }}</td>
              <template v-if="c.disclosed">
                <td class="right" style="color:var(--green)">{{ c.confirmedQty }}</td>
                <td class="right" style="color:var(--amber)">{{ c.pendingQty }}</td>
                <td class="right" style="color:var(--red)">{{ c.possibleQty }}</td>
                <td class="right">{{ c.reportCount }}</td>
                <td><span class="tag green">披露</span></td>
              </template>
              <template v-else>
                <td colspan="4" class="muted small">{{ c.nonDisclosureReason }}</td>
                <td><span class="tag red">明确不披露</span></td>
              </template>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="grid-2">
        <div class="card">
          <h3>核验器核平结果（同冻结来源对账）</h3>
          <table>
            <thead><tr><th>指标</th><th>快照冻结值</th><th>输出值</th><th>结论</th></tr></thead>
            <tbody>
              <tr v-for="r in detail.reconciliation" :key="r.id">
                <td class="mono small">{{ metricLabel(r.metric) }}</td>
                <td class="small mono">{{ r.expectedValue }}</td>
                <td class="small mono">{{ r.actualValue }}</td>
                <td><span class="tag" :class="r.status === 'BALANCED' ? 'green' : 'red'">{{ r.status === 'BALANCED' ? '核平' : '不平' }}</span></td>
              </tr>
            </tbody>
          </table>
          <p class="small muted" style="margin-top:8px">不披露单元的数量计入扣留桶，合计仍与快照对平；去重数含已发布范围外回报。</p>
        </div>
        <div class="card">
          <h3>等价解与约束冲突</h3>
          <div v-if="detail.response.equivalentSolutions && detail.response.equivalentSolutions.length">
            <div v-for="(s, i) in detail.response.equivalentSolutions" :key="i" class="gap-item warning">
              <b>等价解 #{{ i + 1 }}</b>：维度 {{ (s.dimensions || []).map(dimLabel).join(' × ') }} · 信息损失 {{ s.informationLoss }}
              <div class="small" style="margin-top:4px">{{ s.explanation }}</div>
            </div>
          </div>
          <p v-else class="muted small">无等价解（模板投影为固定唯一解，或复杂求解无并列最优）。</p>
          <div v-if="detail.response.constraintConflicts && detail.response.constraintConflicts.length" style="margin-top:10px">
            <div v-for="(c, i) in detail.response.constraintConflicts" :key="i" class="gap-item">
              <b>约束冲突 · {{ c.type }}</b>
              <div class="small" style="margin-top:4px">{{ c.explanation }}</div>
            </div>
          </div>
          <p v-else class="muted small" style="margin-top:10px">无约束冲突。</p>
        </div>
      </div>

      <div class="grid-2">
        <div class="card">
          <h3>授权（收回后下载与重新生成失效）</h3>
          <div class="form-row">
            <label class="field">被授权方 <input v-model="grantee" style="width:180px" /></label>
            <button class="btn secondary" @click="grant">发放授权</button>
          </div>
          <table style="margin-top:10px">
            <thead><tr><th>授权码</th><th>被授权方</th><th>状态</th><th></th></tr></thead>
            <tbody>
              <tr v-for="a in authorizations" :key="a.authCode">
                <td class="mono">{{ a.authCode }}</td>
                <td>{{ a.grantee }}</td>
                <td><span class="tag" :class="a.status === 'ACTIVE' ? 'green' : 'red'">{{ a.status === 'ACTIVE' ? '有效' : '已收回' }}</span></td>
                <td><a v-if="a.status === 'ACTIVE'" href="#" @click.prevent="revoke(a.authCode)">收回</a></td>
              </tr>
            </tbody>
          </table>
          <div class="form-row" style="margin-top:12px">
            <label class="field">授权码 <input v-model="authCode" style="width:200px" placeholder="AUTH-XXXXXXXX" /></label>
            <button class="btn secondary" @click="download">下载答复</button>
            <button class="btn secondary" @click="regenerate">重新生成</button>
          </div>
          <p v-if="authMessage" class="small" style="margin-top:8px" :style="{color: authOk ? 'var(--green)' : 'var(--red)'}">{{ authMessage }}</p>
        </div>
        <div class="card">
          <h3>审计轨迹（授权收回后仍保留）</h3>
          <table>
            <thead><tr><th>时间</th><th>动作</th><th>操作者</th><th>详情</th></tr></thead>
            <tbody>
              <tr v-for="a in auditTrail" :key="a.id">
                <td class="nowrap small">{{ fmt(a.createdAt) }}</td>
                <td><span class="tag gray">{{ actionLabel(a.action) }}</span></td>
                <td class="small">{{ a.actor }}</td>
                <td class="small">{{ a.detail }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const meta = ref({})
const policy = ref(null)
const responses = ref([])
const detail = ref(null)
const authorizations = ref([])
const auditTrail = ref([])
const creating = ref(false)
const error = ref('')
const notice = ref('')
const grantee = ref('监管对接人-国家局')
const authCode = ref('')
const authMessage = ref('')
const authOk = ref(false)

const form = ref({
  inquiryType: 'TEMPLATE',
  templateCode: 'TPL-REG-GRADE',
  responseDate: '2026-11-15T18:00:00+08:00',
  questionCodes: ['Q-001'],
  createdBy: '质量分析员-张某'
})

const fmt = (t) => t ? String(t).replace('T', ' ').slice(0, 16) : ''
const dimLabel = (d) => ({
  region: '地区', month: '月份', evidenceGrade: '证据等级', productCode: '产品',
  institution: '机构', national: '全国', quarter: '季度', year: '年度', productLine: '产品线'
}[d] || d)
const statusLabel = (s) => ({ GENERATED: '已生成', CANCELLED: '已取消（来源撤销）', SUPERSEDED: '已被重新生成替代' }[s] || s)
const statusTag = (s) => s === 'GENERATED' ? 'green' : s === 'CANCELLED' ? 'red' : 'gray'
const metricLabel = (m) => ({
  DEDUP_REPORT_COUNT: '机构回报去重数', EVIDENCE_DISTRIBUTION: '证据等级分布',
  QUANTITY_TOTALS: '三类数量合计', UNDEFINED_FIELDS_PENDING: '未定义字段待复核'
}[m] || m)
const actionLabel = (a) => ({
  RESPONSE_CREATED: '答复生成', RESPONSE_CANCELLED: '答复取消', AUTH_GRANTED: '授权发放',
  AUTH_REVOKED: '授权收回', DOWNLOAD: '下载', REGENERATED: '重新生成', SOURCE_REVOKED: '来源撤销'
}[a] || a)

async function create() {
  creating.value = true
  error.value = ''
  notice.value = ''
  try {
    const res = await api.post('/api/regulatory/responses', form.value)
    notice.value = `已生成 ${res.responseCode}：${res.explanation}`
    await loadResponses()
    await open(res.responseId)
  } catch (e) {
    error.value = e.message
  } finally {
    creating.value = false
  }
}

async function open(id) {
  detail.value = await api.get('/api/regulatory/responses/' + id)
  authorizations.value = await api.get(`/api/regulatory/responses/${id}/authorizations`)
  auditTrail.value = await api.get('/api/regulatory/audit?responseId=' + id)
}

async function grant() {
  const res = await api.post(`/api/regulatory/responses/${detail.value.response.id}/authorizations`,
    { grantee: grantee.value, createdBy: form.value.createdBy })
  authCode.value = res.authCode
  await open(detail.value.response.id)
}

async function revoke(code) {
  await api.post(`/api/regulatory/authorizations/${code}/revoke`,
    { reason: '监管授权收回演示', actor: form.value.createdBy })
  await open(detail.value.response.id)
}

async function download() {
  authMessage.value = ''
  try {
    await api.get(`/api/regulatory/responses/${detail.value.response.id}/download?authCode=${authCode.value}`)
    authOk.value = true
    authMessage.value = '下载成功：授权有效，答复内容已返回（含不披露标记与核平结果）'
  } catch (e) {
    authOk.value = false
    authMessage.value = '下载被拒绝：' + e.message
  }
  auditTrail.value = await api.get('/api/regulatory/audit?responseId=' + detail.value.response.id)
}

async function regenerate() {
  authMessage.value = ''
  try {
    const res = await api.post(`/api/regulatory/responses/${detail.value.response.id}/regenerate`,
      { authCode: authCode.value, createdBy: form.value.createdBy })
    authOk.value = true
    authMessage.value = `重新生成成功：新答复 ${res.responseCode}，旧答复已置 SUPERSEDED`
    await loadResponses()
  } catch (e) {
    authOk.value = false
    authMessage.value = '重新生成被拒绝：' + e.message
  }
  auditTrail.value = await api.get('/api/regulatory/audit?responseId=' + detail.value.response.id)
}

async function loadResponses() {
  responses.value = await api.get('/api/regulatory/responses')
}

onMounted(async () => {
  meta.value = await api.get('/api/regulatory/meta')
  policy.value = (meta.value.policies || [])[0] || null
  await loadResponses()
})
</script>
