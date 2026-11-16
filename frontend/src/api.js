const BASE = ''

async function request(path, options = {}) {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options
  })
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) {
    throw new Error(data?.message || `请求失败 ${res.status}`)
  }
  return data
}

export const api = {
  get: (path) => request(path),
  post: (path, body) => request(path, { method: 'POST', body: JSON.stringify(body || {}) }),
  externalGet: (path, apiKey) => request(path, { headers: { 'X-API-Key': apiKey } })
}

export const GAP_LABELS = {
  MISSING_FEEDING_EVIDENCE: '缺失投料凭证',
  DUPLICATE_BOX: '重复箱码（重复载体证据）',
  QUANTITY_CONFLICT: '物流/机构数量冲突',
  UNMATCHED_BATCH: '无法匹配的批号',
  IDENTITY_AMBIGUITY: '身份歧义',
  OVER_FEEDING: '超额投料（分配链冻结）',
  PARSE_DIFF: '解析规则差异'
}

export const CONFIDENCE_LABELS = {
  CONFIRMED: '已确认',
  SUSPECTED: '待核/疑似',
  FROZEN: '冻结'
}

export const GRADE_LABELS = {
  CONFIRMED: '确认',
  PENDING: '待核',
  POSSIBLE: '可能受影响'
}
