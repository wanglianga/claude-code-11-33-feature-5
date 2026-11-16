<template>
  <div>
    <h2 class="page-title">外部处置系统接口（脱敏演示）</h2>
    <p class="page-desc">
      外部系统只能凭 API Key 获得经授权的范围摘要与证据链接；原始文件、患者与联系人等敏感字段不进入对外结果。
      本页面不改变任何数据，仅演示 <span class="mono">GET /external/**</span> 的实际响应。
    </p>

    <div class="card">
      <div class="form-row">
        <label class="field">X-API-Key
          <input v-model="apiKey" style="width:300px" placeholder="外部系统凭证" />
        </label>
        <button class="btn" @click="load">请求外部摘要</button>
        <button class="btn secondary" @click="loadNoKey">测试无 Key（应 401）</button>
      </div>
      <p v-if="error" class="small" style="color:var(--red);margin-top:10px">{{ error }}</p>
    </div>

    <div class="card" v-if="list.length">
      <h3>可访问的发布版本</h3>
      <table>
        <thead><tr><th>发布编号</th><th>版本</th><th>状态</th><th>差异类型</th><th>发布时间</th><th></th></tr></thead>
        <tbody>
          <tr v-for="r in list" :key="r.releaseCode">
            <td class="mono">{{ r.releaseCode }}</td>
            <td>V{{ r.versionNo }}</td>
            <td><span class="tag green">{{ r.status }}</span></td>
            <td class="small">{{ r.diffType }}</td>
            <td class="nowrap">{{ fmt(r.publishedAt) }}</td>
            <td><a href="#" @click.prevent="open(r.releaseCode)">查看脱敏摘要</a></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card" v-if="summary">
      <h3>脱敏范围摘要 · <span class="mono">{{ summary.releaseCode }}</span></h3>
      <p class="small">
        证据等级三类数量：
        <span class="tag green">CONFIRMED 确认</span>
        <span class="tag amber">PENDING 待核</span>
        <span class="tag red">POSSIBLE 可能受影响</span>
      </p>
      <table>
        <thead><tr>
          <th>批次标识</th><th>机构/节点</th><th class="right">确认量</th><th class="right">待核量</th>
          <th class="right">可能受影响量</th><th>数量区间</th><th>证据等级</th><th>追溯链接</th>
        </tr></thead>
        <tbody>
          <tr v-for="it in summary.items" :key="it.batchCode">
            <td class="mono">{{ it.batchCode }}</td>
            <td>{{ it.nodeName || '—' }}</td>
            <td class="right">{{ it.confirmedQty }}</td>
            <td class="right">{{ it.pendingQty }}</td>
            <td class="right">{{ it.possibleQty }}</td>
            <td class="nowrap">{{ it.lowerBound }} ~ {{ it.upperBound }} {{ it.unit }}</td>
            <td><span class="tag" :class="it.evidenceGrade==='CONFIRMED'?'green':it.evidenceGrade==='POSSIBLE'?'red':'amber'">
              {{ it.evidenceGrade }}
            </span></td>
            <td class="small mono">{{ it.traceLink }}</td>
          </tr>
        </tbody>
      </table>
      <h4>原始响应（可核对：不含 payload / 联系人 / 患者字段）</h4>
      <pre class="payload">{{ JSON.stringify(summary, null, 2) }}</pre>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { api } from '../api.js'

const apiKey = ref('ext-trace-key-2026')
const list = ref([])
const summary = ref(null)
const error = ref('')
const fmt = (t) => t ? t.replace('T', ' ').slice(0, 16) : ''

async function load() {
  error.value = ''
  try {
    list.value = await api.externalGet('/external/releases', apiKey.value)
  } catch (e) {
    error.value = '请求失败：' + e.message
    list.value = []
  }
}

async function open(code) {
  error.value = ''
  try {
    summary.value = await api.externalGet('/external/releases/' + code, apiKey.value)
  } catch (e) {
    error.value = '请求失败：' + e.message
  }
}

async function loadNoKey() {
  error.value = ''
  try {
    await fetch('/external/releases')
    error.value = '预期外：无 Key 请求未被拒绝'
  } catch {
    error.value = '网络异常'
  }
  const res = await fetch('/external/releases')
  error.value = res.status === 401
    ? '验证通过：未携带 X-API-Key 时返回 401 UNAUTHORIZED'
    : `预期外状态码 ${res.status}`
}

load()
</script>
