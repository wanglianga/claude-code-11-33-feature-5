<template>
  <div v-if="trace">
    <div class="breadcrumb">
      <router-link to="/runs">范围核验台</router-link> /
      <router-link :to="`/runs/${id}`">核验 #{{ id }}</router-link> /
      批次 <span class="mono">{{ batchCode }}</span>
    </div>
    <h2 class="page-title">批次证据钻取 · <span class="mono">{{ batchCode }}</span></h2>
    <p class="page-desc">从受影响批次一路展开到原始载荷、身份候选、谱系边、数量区间、冲突/缺口、规则版本与发布依据。</p>

    <div class="grid-2">
      <div class="card">
        <h3>批次主数据</h3>
        <table v-if="trace.batchMaster">
          <tbody>
            <tr><th>批号</th><td class="mono">{{ trace.batchMaster.batchCode }}</td></tr>
            <tr><th>类型</th><td><span class="tag gray">{{ typeLabel(trace.batchMaster.batchType) }}</span></td></tr>
            <tr><th>产品</th><td>{{ trace.batchMaster.productName }}（{{ trace.batchMaster.productCode }}）</td></tr>
            <tr><th>批量</th><td>{{ trace.batchMaster.quantity }} {{ trace.batchMaster.unit }}</td></tr>
          </tbody>
        </table>
      </div>
      <div class="card" v-if="trace.quantity">
        <h3>数量区间（本核验）</h3>
        <div class="grid-3">
          <div class="stat"><div class="num" style="color:var(--green)">{{ trace.quantity.confirmedLower }}</div><div class="lbl">确认量下界 / {{ trace.quantity.unit }}</div></div>
          <div class="stat"><div class="num" style="color:var(--amber)">{{ trace.quantity.pendingQty }}</div><div class="lbl">待核量 / {{ trace.quantity.unit }}</div></div>
          <div class="stat"><div class="num" style="color:var(--red)">{{ trace.quantity.possibleUpper }}</div><div class="lbl">可能受影响上界 / {{ trace.quantity.unit }}</div></div>
        </div>
        <p class="small" style="margin-top:10px">未能归因数量差：<b style="color:var(--red)">{{ trace.quantity.unattributedDelta }} {{ trace.quantity.unit }}</b></p>
      </div>
    </div>

    <div class="card" v-if="nodes.length">
      <h3>机构 / 运输节点流转证据</h3>
      <table>
        <thead><tr>
          <th>节点</th><th>箱码</th><th>物流签收量</th><th>机构回报量</th><th>状态</th><th>凭证（事实/来源包）</th>
        </tr></thead>
        <tbody>
          <tr v-for="(n,i) in nodes" :key="i">
            <td>{{ n.node }}</td>
            <td class="mono">{{ n.boxCode }}</td>
            <td>{{ n.logisticsSignedQty }} <span class="small">({{ n.logisticsFactCode }})</span></td>
            <td>{{ n.institutionReceivedQty ?? '—' }} <span class="small">{{ n.institutionFactCode ? '('+n.institutionFactCode+')' : '' }}</span></td>
            <td>
              <span v-if="n.quantityConflict" class="tag red">数量冲突（双凭证并存，不得互相覆盖）</span>
              <span v-else-if="n.evidenceWeak" class="tag amber">证据薄弱：{{ n.weakReason }}</span>
              <span v-else class="tag green">数量一致</span>
            </td>
            <td class="small mono">{{ n.logisticsPackage }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <h3>谱系边（来源凭证 · 有效时间 · 置信状态 · 解析版本）</h3>
      <table>
        <thead><tr>
          <th>边</th><th>关系</th><th>上游 → 下游</th><th>数量</th><th>凭证事实</th><th>来源包</th>
          <th>有效时间</th><th>置信状态</th><th>解析版本</th><th>阻断原因</th>
        </tr></thead>
        <tbody>
          <tr v-for="e in trace.edges" :key="e.edgeCode">
            <td class="mono">{{ e.edgeCode }}</td>
            <td><span class="tag cyan">{{ e.relationType }}</span></td>
            <td class="mono">{{ e.upstreamBatch || '∅' }} → {{ e.downstreamBatch }}</td>
            <td class="nowrap">{{ e.quantity ?? '—' }} {{ e.unit || '' }}</td>
            <td class="mono small">{{ e.sourceFactCode }}</td>
            <td class="mono small">{{ e.sourcePackageCode }}</td>
            <td class="nowrap small">{{ fmt(e.validFrom) }}</td>
            <td><span :class="['tag', e.confidenceStatus==='CONFIRMED'?'green':e.confidenceStatus==='FROZEN'?'red':'amber']">
              {{ confLabel(e.confidenceStatus) }}
            </span></td>
            <td class="mono small">{{ e.parseRuleVersion }}</td>
            <td><span v-if="e.blockReason" class="tag red">{{ e.blockReason }}</span><span v-else class="muted">—</span></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="grid-2">
      <div class="card">
        <h3>关联身份候选</h3>
        <table>
          <thead><tr><th>编码</th><th>类型</th><th>状态</th><th>候选（置信/歧义）</th></tr></thead>
          <tbody>
            <tr v-for="r in trace.identityRecords" :key="r.id">
              <td class="mono">{{ r.codeValue }}</td>
              <td class="small">{{ r.codeType }}</td>
              <td><span class="tag" :class="r.matchStatus==='MATCHED'?'green':'purple'">{{ r.matchStatus }}</span></td>
              <td>
                <span v-for="c in r.candidates" :key="c.id" class="tag"
                      :class="c.ambiguous?'purple':'green'" style="margin:0 5px 4px 0">
                  {{ c.candidateBatchCode }} · {{ c.confidence }}
                </span>
              </td>
            </tr>
            <tr v-if="!trace.identityRecords.length"><td colspan="4" class="muted">无直接身份观测</td></tr>
          </tbody>
        </table>
      </div>
      <div class="card">
        <h3>关联证据缺口 / 冲突</h3>
        <div v-for="g in trace.gaps" :key="g.subject+g.gapType" class="gap-item" :class="g.severity==='WARNING'?'warning':''">
          <b>{{ gapLabel(g.gapType) }}</b> <span class="mono muted small">{{ g.subjectCode }}</span>
          <div class="small" style="margin-top:4px">{{ g.description }}</div>
          <div class="blocks">阻断：{{ g.blocksConclusion }}</div>
        </div>
        <p v-if="!trace.gaps.length" class="muted">本批次无待核事项。</p>
      </div>
    </div>

    <div class="card">
      <h3>原始载荷（内部可见，含哈希与解析版本；不进入外部接口）</h3>
      <div v-for="p in trace.rawPackages" :key="p.packageCode" style="margin-bottom:16px">
        <h4 class="mono">{{ p.packageCode }} · {{ p.sourceSystem }} · {{ p.sourceRevision }}
          <span :class="['tag', p.parseStatus==='PARSE_DIFF'?'amber':'green']" style="margin-left:8px">{{ p.parseStatus }}</span>
        </h4>
        <p class="small mono" style="margin:4px 0">sha256={{ p.payloadHash }} · 解析版本={{ p.parseRuleVersion }} · 接收={{ fmt(p.receivedAt) }}</p>
        <pre class="payload">{{ JSON.stringify(p.payload, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api, GAP_LABELS } from '../api.js'

const props = defineProps({ id: String, batchCode: String })
const trace = ref(null)

const fmt = (t) => t ? t.replace('T', ' ').slice(0, 16) : ''
const typeLabel = (t) => ({ RAW: '原料', INTERMEDIATE: '中间品', FINISHED: '成品' }[t] || t)
const confLabel = (c) => ({ CONFIRMED: '已确认', SUSPECTED: '待核/疑似', FROZEN: '冻结' }[c] || c)
const gapLabel = (t) => GAP_LABELS[t] || t
const nodes = computed(() => trace.value?.quantity?.detail?.nodes || [])

onMounted(async () => {
  trace.value = await api.get(`/api/runs/${props.id}/batches/${props.batchCode}/trace`)
})
</script>
