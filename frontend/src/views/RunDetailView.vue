<template>
  <div v-if="data">
    <div class="breadcrumb">
      <router-link to="/runs">范围核验台</router-link> / 核验 #{{ data.run.id }}
    </div>
    <h2 class="page-title">核验 {{ data.run.runCode }}
      <span class="tag" :class="data.run.status === 'PUBLISHED' ? 'green' : 'amber'" style="margin-left:8px">
        {{ statusLabel(data.run.status) }}
      </span>
    </h2>
    <p class="page-desc">
      检验更正版本 <b class="mono">{{ data.run.inspectionCorrectionVersion }}</b> ·
      截止时间 <b>{{ fmt(data.run.cutoffAt) }}</b> ·
      规则版本 <b class="mono">{{ data.run.ruleVersion }}</b> ·
      锁定来源包 <b>{{ data.packages.length }}</b> 个
    </p>

    <div class="grid-3">
      <div class="stat"><div class="num">{{ data.quantities.length }}</div><div class="lbl">受影响/待观察批次</div></div>
      <div class="stat"><div class="num" style="color:var(--red)">{{ data.gaps.filter(g=>g.severity==='BLOCKER').length }}</div><div class="lbl">阻断级证据缺口</div></div>
      <div class="stat"><div class="num" style="color:var(--purple)">{{ data.identityRecords.filter(r=>r.matchStatus!=='MATCHED').length }}</div><div class="lbl">歧义/未匹配身份记录</div></div>
    </div>

    <div style="margin:16px 0">
      <button class="btn" :disabled="publishing || data.run.status==='PUBLISHED'" @click="publish">
        {{ data.run.status === 'PUBLISHED' ? '本核验已发布' : '发布范围新版本' }}
      </button>
      <router-link class="btn secondary" style="margin-left:10px" to="/releases">查看发布版本</router-link>
    </div>

    <div class="tabs">
      <button v-for="t in tabs" :key="t.key" :class="{active: tab===t.key}" @click="tab=t.key">{{ t.label }}</button>
    </div>

    <!-- 数量区间 -->
    <div v-if="tab==='qty'" class="card">
      <h3>数量质量引擎结果（确认下界 / 待核 / 可能受影响上界）</h3>
      <table>
        <thead><tr>
          <th>批次</th><th>类型</th><th class="right">确认量下界</th><th class="right">待核量</th>
          <th class="right">可能受影响上界</th><th class="right">未归因差额</th><th>单位</th>
          <th style="width:180px">区间构成</th><th></th>
        </tr></thead>
        <tbody>
          <tr v-for="q in data.quantities" :key="q.batchCode">
            <td class="mono"><b>{{ q.batchCode }}</b></td>
            <td><span class="tag gray">{{ batchType(q.batchCode) }}</span></td>
            <td class="right" style="color:var(--green)">{{ q.confirmedLower }}</td>
            <td class="right" style="color:var(--amber)">{{ q.pendingQty }}</td>
            <td class="right" style="color:var(--red)">{{ q.possibleUpper }}</td>
            <td class="right">{{ q.unattributedDelta }}</td>
            <td>{{ q.unit }}</td>
            <td><div class="qty-bar">
              <div class="c" :style="{width: pct(q.confirmedLower, q.possibleUpper)}"></div>
              <div class="p" :style="{width: pct(q.pendingQty, q.possibleUpper)}"></div>
            </div></td>
            <td><router-link :to="`/runs/${data.run.id}/batches/${q.batchCode}`">展开证据 →</router-link></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 谱系图 -->
    <div v-if="tab==='graph'" class="card">
      <h3>有向批次谱系（虚线=待核/缺凭证/解析差异，红虚线=冻结）</h3>
      <svg class="graph-svg" :viewBox="`0 0 ${graph.width} ${graph.height}`">
        <defs>
          <marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
            <path d="M0,0 L7,3 L0,6 Z" fill="#93a4bd" />
          </marker>
        </defs>
        <line v-for="(e,i) in graph.edges" :key="'e'+i"
              :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2"
              :class="['edge-line', e.confidence==='SUSPECTED'?'suspected':e.confidence==='FROZEN'?'frozen':'']"
              marker-end="url(#arrow)" />
        <text v-for="(e,i) in graph.edges" :key="'l'+i" :x="(e.x1+e.x2)/2" :y="(e.y1+e.y2)/2-4"
              class="edge-label" text-anchor="middle">{{ e.label }} {{ e.qty ?? '' }}</text>
        <g v-for="n in graph.nodes" :key="n.code">
          <rect :x="n.x" :y="n.y" width="132" height="40" rx="7"
                :class="['node-rect', n.ambiguous ? 'ambiguous' : (n.affected ? 'affected' : '')]" />
          <text :x="n.x+66" :y="n.y+17" text-anchor="middle" class="node-label">{{ n.code }}</text>
          <text :x="n.x+66" :y="n.y+32" text-anchor="middle" class="edge-label">{{ n.rel }}</text>
        </g>
      </svg>
      <p class="small">红框=受影响批次，紫框=身份歧义批次；点击“数量区间”页签中的批次可下钻到原始载荷与凭证。</p>
    </div>

    <!-- 身份候选 -->
    <div v-if="tab==='identity'" class="card">
      <h3>批次身份解析（歧义保留，不强行合并）</h3>
      <table>
        <thead><tr><th>编码类型</th><th>编码值</th><th>观测内部批次</th><th>匹配状态</th><th>候选身份与置信</th></tr></thead>
        <tbody>
          <tr v-for="r in data.identityRecords" :key="r.id">
            <td>{{ codeTypeLabel(r.codeType) }}</td>
            <td class="mono">{{ r.codeValue }}</td>
            <td class="mono">{{ r.observedBatchCode || '—' }}</td>
            <td><span :class="['tag', r.matchStatus==='MATCHED'?'green':r.matchStatus==='AMBIGUOUS'?'purple':'red']">
              {{ matchLabel(r.matchStatus) }}
            </span></td>
            <td>
              <div v-for="c in r.candidates" :key="c.id" style="margin-bottom:4px">
                <span class="mono">{{ c.candidateBatchCode }}</span>
                <span class="tag" :class="c.confidence==='HIGH'?'green':'amber'" style="margin:0 6px">
                  {{ c.confidence==='HIGH'?'高置信':'低置信' }}{{ c.ambiguous ? '·歧义' : '' }}
                </span>
              </div>
              <span v-if="!r.candidates.length" class="muted">无候选（无法归因）</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 证据缺口 -->
    <div v-if="tab==='gaps'">
      <div class="card">
        <h3>证据缺口工作台（{{ data.gaps.length }}）</h3>
        <div v-for="g in data.gaps" :key="g.id" :class="['gap-item', g.severity==='WARNING'?'warning':'']">
          <div>
            <span :class="['tag', g.severity==='BLOCKER'?'red':'amber']">{{ g.severity==='BLOCKER'?'阻断':'警告' }}</span>
            <b style="margin-left:8px">{{ gapLabel(g.gapType) }}</b>
            <span class="mono muted" style="margin-left:8px">{{ g.subjectCode }}</span>
            <span v-if="g.nodeName" class="tag gray" style="margin-left:6px">{{ g.nodeName }}</span>
          </div>
          <div style="margin-top:7px">{{ g.description }}</div>
          <div class="blocks">阻断结论：{{ g.blocksConclusion }}</div>
        </div>
      </div>
    </div>

    <!-- 来源快照 -->
    <div v-if="tab==='snapshot'" class="card">
      <h3>输入快照（截止时间锁定，含哈希与解析状态）</h3>
      <table>
        <thead><tr><th>包编号</th><th>来源</th><th>修订号</th><th>哈希</th><th>解析版本</th><th>解析状态</th><th>接收时间</th></tr></thead>
        <tbody>
          <tr v-for="p in data.packages" :key="p.packageCode">
            <td class="mono">{{ p.packageCode }}</td>
            <td><span class="tag gray">{{ p.sourceSystem }}</span></td>
            <td class="mono">{{ p.sourceRevision }}</td>
            <td class="mono small">{{ p.payloadHash.slice(0,18) }}…</td>
            <td class="mono">{{ p.parseRuleVersion }}</td>
            <td><span :class="['tag', p.parseStatus==='PARSE_DIFF'?'amber':'green']">{{ p.parseStatus }}</span></td>
            <td class="nowrap">{{ fmt(p.receivedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api, GAP_LABELS, CONFIDENCE_LABELS } from '../api.js'

const props = defineProps({ id: String })
const router = useRouter()
const data = ref(null)
const tab = ref('qty')
const publishing = ref(false)
const tabs = [
  { key: 'qty', label: '数量区间' },
  { key: 'graph', label: '谱系图' },
  { key: 'identity', label: '身份候选' },
  { key: 'gaps', label: `证据缺口` },
  { key: 'snapshot', label: '输入快照' }
]

const fmt = (t) => t ? t.replace('T', ' ').slice(0, 16) : ''
const statusLabel = (s) => ({ LOCKED: '已锁定', PUBLISHED: '已发布', SUPERSEDED: '已被替代' }[s] || s)
const gapLabel = (t) => GAP_LABELS[t] || t
const matchLabel = (s) => ({ MATCHED: '唯一匹配', AMBIGUOUS: '歧义（保留多候选）', UNMATCHED: '未匹配' }[s] || s)
const codeTypeLabel = (t) => ({
  SUPPLIER_LOT: '供应商批号', INTERNAL_LOT: '内部批号', BOX_CODE: '箱码', PRODUCTION_LOT: '生产批号'
}[t] || t)

const batches = ref([])
const batchType = (code) => batches.value.find(b => b.batchCode === code)?.batchType || '—'
const pct = (v, total) => (Number(total) > 0 ? Math.max(2, Number(v) / Number(total) * 100) + '%' : '0%')

const graph = computed(() => {
  if (!data.value) return { width: 900, height: 460, nodes: [], edges: [] }
  const edges = data.value.edges.filter(e => e.relationType !== 'RELEASE' && e.upstream)
  const typeOrder = { RAW: 0, INTERMEDIATE: 1, FINISHED: 2 }
  const typeOf = (code) => batches.value.find(b => b.batchCode === code)?.batchType || 'FINISHED'
  const layers = [[], [], []]
  const seen = new Set()
  edges.forEach(e => [e.upstreamBatch, e.downstreamBatch].forEach(code => {
    if (!code || seen.has(code)) return
    seen.add(code)
    const idx = typeOrder[typeOf(code)] ?? 1
    layers[idx].push(code)
  }))
  const nodeMap = new Map()
  const colX = [60, 330, 620]
  const width = 900
  const rowH = 58
  const height = Math.max(460, Math.max(...layers.map(l => l.length)) * rowH + 60)
  layers.forEach((layer, li) => {
    layer.forEach((code, i) => {
      nodeMap.set(code, {
        code, x: colX[li], y: 30 + i * rowH,
        rel: typeOf(code) === 'RAW' ? '原料' : typeOf(code) === 'INTERMEDIATE' ? '中间品' : '成品/节点',
        affected: data.value.quantities.some(q => q.batchCode === code),
        ambiguous: data.value.identityRecords.some(r => r.matchStatus === 'AMBIGUOUS' && r.observedBatchCode === code)
      })
    })
  })
  const affectedSet = new Set(data.value.quantities.map(q => q.batchCode))
  const drawnEdges = edges.map(e => {
    const a = nodeMap.get(e.upstreamBatch)
    const b = nodeMap.get(e.downstreamBatch)
    if (!a || !b) return null
    return {
      x1: a.x + 132, y1: a.y + 20, x2: b.x, y2: b.y + 20,
      confidence: e.confidenceStatus,
      label: e.relationType, qty: e.quantity ? e.quantity + e.unit : ''
    }
  }).filter(Boolean)
  return { width, height, nodes: [...nodeMap.values()], edges: drawnEdges }
})

async function publish() {
  publishing.value = true
  try {
    await api.post(`/api/runs/${props.id}/publish`, {})
    router.push('/releases')
  } finally {
    publishing.value = false
  }
}

onMounted(async () => {
  data.value = await api.get(`/api/runs/${props.id}`)
  batches.value = await api.get('/api/batches')
})
</script>
