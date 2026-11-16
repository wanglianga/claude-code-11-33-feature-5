<template>
  <div>
    <h2 class="page-title">发布范围版本管理</h2>
    <p class="page-desc">来源断言与发布范围分别版本化：检验更正只生成新候选版本，旧版本的输入依据、差异类型与受影响结论完整保留，已发布结果不被改写。</p>

    <div v-for="r in releases" :key="r.id" class="card">
      <h3>
        <span class="mono">{{ r.releaseCode }}</span> · V{{ r.versionNo }}
        <span :class="['tag', r.status==='PUBLISHED'?'green':'gray']" style="margin-left:8px">
          {{ r.status==='PUBLISHED'?'当前发布':'已被替代（只读保留）' }}
        </span>
        <span class="tag purple" style="margin-left:8px">{{ diffLabel(r.diffType) }}</span>
      </h3>
      <p class="small" style="margin:0 0 10px">
        发布时间 {{ fmt(r.publishedAt) }} · 前驱版本 {{ r.predecessorReleaseCode || '—' }} ·
        输入依据：检验版本 <b class="mono">{{ r.basis.correctionVersion }}</b>，
        规则 <b class="mono">{{ r.basis.ruleVersion }}</b>，
        锁定来源包 <b>{{ r.basis.packages?.length ?? 0 }}</b> 个
      </p>

      <div v-if="r.diffDetail?.length" class="card" style="background:var(--panel-2);margin-bottom:12px">
        <h4>与上一版本的差异依据（三类变化可区分）</h4>
        <table>
          <thead><tr><th>差异类型</th><th>说明</th><th>涉及对象</th></tr></thead>
          <tbody>
            <tr v-for="(d,i) in r.diffDetail" :key="i">
              <td><span class="tag" :class="diffTag(d.type)">{{ diffLabel(d.type) }}</span></td>
              <td class="small">{{ d.description || '—' }}</td>
              <td class="small mono">
                {{ d.from ? d.from+' → '+d.to : '' }}
                {{ d.packages ? d.packages.join(', ') : '' }}
                {{ d.package ? d.package : '' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <h4>受影响结论变化</h4>
      <table>
        <thead><tr><th>批次</th><th>变化类型</th><th>前版本区间（确认/待核/上界）</th><th>当前区间（确认/待核/上界）</th></tr></thead>
        <tbody>
          <tr v-for="(c,i) in r.affectedConclusions" :key="i">
            <td class="mono">{{ c.batchCode }}</td>
            <td><span :class="['tag', changeTag(c.changeType)]">{{ changeLabel(c.changeType) }}</span></td>
            <td class="small">{{ c.previous ? q(c.previous) : '—' }}</td>
            <td class="small">{{ c.current ? q(c.current) : '—' }}</td>
          </tr>
        </tbody>
      </table>

      <h4>对外发布项（仅批次/节点/数量区间/证据等级/追溯链接，无原始载荷与敏感字段）</h4>
      <table>
        <thead><tr>
          <th>批次</th><th>机构/节点</th><th class="right">确认</th><th class="right">待核</th>
          <th class="right">可能</th><th>区间(下界~上界)</th><th>证据等级</th><th>冲突</th><th>追溯链接</th>
        </tr></thead>
        <tbody>
          <tr v-for="it in r.items" :key="it.batchCode">
            <td class="mono">{{ it.batchCode }}</td>
            <td>{{ it.nodeName || '—' }}</td>
            <td class="right" style="color:var(--green)">{{ it.confirmedQty }}</td>
            <td class="right" style="color:var(--amber)">{{ it.pendingQty }}</td>
            <td class="right" style="color:var(--red)">{{ it.possibleQty }}</td>
            <td class="nowrap">{{ it.lowerBound }} ~ {{ it.upperBound }} {{ it.unit }}</td>
            <td><span :class="['tag', gradeTag(it.evidenceGrade)]">{{ gradeLabel(it.evidenceGrade) }}</span></td>
            <td><span v-if="it.conflictFlag" class="tag red">是</span><span v-else class="muted">否</span></td>
            <td class="small mono">{{ it.traceLink }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api, GRADE_LABELS } from '../api.js'

const releases = ref([])
const fmt = (t) => t ? t.replace('T', ' ').slice(0, 16) : ''
const q = (v) => `${v.confirmedLower} / ${v.pendingQty} / ${v.possibleUpper}（差额 ${v.unattributedDelta}）`

const DIFF = {
  NONE: '无差异（首次发布）',
  INSPECTION_CHANGED: '检验结论变了',
  NEW_LOGISTICS_EVIDENCE: '新物流/机构凭证补到',
  PARSE_RULE_REVISED: '解析规则修订',
  NEW_SOURCE_PACKAGE: '新增来源包'
}
const diffLabel = (t) => {
  if (!t) return '—'
  if (t.startsWith('MIXED')) {
    return '混合变化：' + t.replace('MIXED:', '').split('+').map(x => DIFF[x] || x).join(' + ')
  }
  return DIFF[t] || t
}
const diffTag = (t) => t === 'INSPECTION_CHANGED' ? 'red' : t === 'NEW_LOGISTICS_EVIDENCE' ? 'cyan' : 'amber'
const changeLabel = (t) => ({
  NEW_AFFECTED_BATCH: '新进入召回范围',
  NO_LONGER_AFFECTED: '移出召回范围',
  QUANTITY_INTERVAL_CHANGED: '数量区间变化'
}[t] || t)
const changeTag = (t) => t === 'NEW_AFFECTED_BATCH' ? 'red' : t === 'NO_LONGER_AFFECTED' ? 'green' : 'amber'
const gradeLabel = (g) => GRADE_LABELS[g] || g
const gradeTag = (g) => g === 'CONFIRMED' ? 'green' : g === 'POSSIBLE' ? 'red' : 'amber'

onMounted(async () => {
  releases.value = await api.get('/api/releases')
})
</script>
