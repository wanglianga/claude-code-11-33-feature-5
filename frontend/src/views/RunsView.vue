<template>
  <div>
    <h2 class="page-title">范围核验台</h2>
    <p class="page-desc">分析员选定检验更正版本与截止时间发起核验；系统锁定截止时间前每个来源包的原始副本与解析状态，后续补录不会混入本次判断。</p>

    <div class="card">
      <h3>发起范围核验</h3>
      <div class="form-row">
        <label class="field">检验更正版本
          <select v-model="form.inspectionCorrectionVersion">
            <option v-for="v in versions" :key="v.version" :value="v.version">
              {{ v.version }}（接收于 {{ fmt(v.receivedAt) }}，替代 {{ v.supersedesPackageCode || '—' }}）
            </option>
          </select>
        </label>
        <label class="field">截止时间
          <select v-model="form.cutoffAt">
            <option value="2026-11-10T18:00:00+08:00">截止 #1：2026-11-10 18:00（更正前）</option>
            <option value="2026-11-14T18:00:00+08:00">截止 #2：2026-11-14 18:00（更正+迟到回报后）</option>
          </select>
        </label>
        <label class="field">发起人
          <input v-model="form.createdBy" style="width:160px" />
        </label>
        <button class="btn" :disabled="loading" @click="createRun">{{ loading ? '核验中…' : '锁定快照并核验' }}</button>
      </div>
      <p v-if="error" class="small" style="color:var(--red);margin-top:10px">{{ error }}</p>
    </div>

    <div class="card">
      <h3>核验记录（{{ runs.length }}）</h3>
      <table>
        <thead><tr>
          <th>ID</th><th>核验编号</th><th>检验更正版本</th><th>截止时间</th><th>规则版本</th>
          <th>状态</th><th>发起人</th><th>快照包数</th><th></th>
        </tr></thead>
        <tbody>
          <tr v-for="r in runs" :key="r.id">
            <td>{{ r.id }}</td>
            <td class="mono">{{ r.runCode }}</td>
            <td class="mono">{{ r.inspectionCorrectionVersion }}</td>
            <td class="nowrap">{{ fmt(r.cutoffAt) }}</td>
            <td class="mono">{{ r.ruleVersion }}</td>
            <td><span :class="['tag', r.status === 'PUBLISHED' ? 'green' : 'amber']">{{ statusLabel(r.status) }}</span></td>
            <td>{{ r.createdBy }}</td>
            <td>{{ snapshotCount(r) }}</td>
            <td><router-link :to="`/runs/${r.id}`">打开核验结果 →</router-link></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api.js'

const router = useRouter()
const versions = ref([])
const runs = ref([])
const loading = ref(false)
const error = ref('')
const form = ref({
  inspectionCorrectionVersion: 'QC-REV-1',
  cutoffAt: '2026-11-10T18:00:00+08:00',
  createdBy: '质量分析员-张某'
})

const fmt = (t) => t ? t.replace('T', ' ').slice(0, 16) : ''
const statusLabel = (s) => ({ LOCKED: '已锁定', PUBLISHED: '已发布', SUPERSEDED: '已被替代' }[s] || s)
const snapshotCount = (r) => r.inputSnapshot?.packages?.length ?? '—'

async function createRun() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.post('/api/runs', form.value)
    await loadRuns()
    router.push(`/runs/${res.runId}`)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function loadRuns() {
  runs.value = await api.get('/api/runs')
}

onMounted(async () => {
  versions.value = await api.get('/api/meta/inspection-versions')
  await loadRuns()
})
</script>
