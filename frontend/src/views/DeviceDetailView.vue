<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Edit, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import type { Device, Shadow, Telemetry } from '../api/types'
import MultiSeriesChart from '../components/MultiSeriesChart.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useProductStore } from '../stores/products'
import { useSpaceStore } from '../stores/spaces'
import { subscribeRealtime } from '../realtime/mqtt'
import { fmtDateTime, fmtRelative, fmtTimeSec } from '../utils/format'

const route = useRoute()
const router = useRouter()
const productStore = useProductStore()
const spaceStore = useSpaceStore()
const deviceRouteId = String(route.params.id)

const device = ref<Device | null>(null)
const telemetry = ref<Telemetry[]>([])
const shadow = ref<Shadow | null>(null)
const loading = ref(true)
const tab = ref('realtime')
let timer: number | undefined
let unsubscribeRealtime: (() => void) | undefined

const businessId = computed(() => device.value?.deviceId ?? '')

const series = computed(() => {
  const ordered = [...telemetry.value].reverse()
  const labels = ordered.map((item) => fmtTimeSec(item.ts))
  const build = (key: string) =>
    ordered.map((item, index) => ({
      label: labels[index],
      value: Number(item.params[key] ?? 0)
    }))
  return [
    { name: '温度 ℃', color: '#f08a3c', axis: 'left' as const, points: build('temperature') },
    { name: '湿度 %RH', color: '#0071e3', axis: 'right' as const, points: build('humidity') }
  ]
})

const latest = computed(() => telemetry.value[0]?.params ?? {})

const miniStats = computed(() => [
  {
    label: '温度',
    value: latest.value.temperature !== undefined ? `${latest.value.temperature} ℃` : '—',
    tone: 'orange'
  },
  {
    label: '湿度',
    value: latest.value.humidity !== undefined ? `${latest.value.humidity} %RH` : '—',
    tone: 'blue'
  },
  {
    label: '最近上报',
    value: telemetry.value[0] ? fmtRelative(telemetry.value[0].ts) : '—',
    tone: 'violet'
  }
])

async function loadDevice() {
  try {
    device.value = await api.getDevice(deviceRouteId)
  } catch (error) {
    ElMessage.error((error as Error).message)
    router.replace('/devices')
  }
}

async function loadTelemetry() {
  if (!businessId.value) return
  try {
    const rows = await api.deviceTelemetry(businessId.value, 60)
    // 实时视图仅展示最近 15 分钟，避免与历史种子数据混排
    const cutoff = Date.now() - 15 * 60_000
    telemetry.value = rows.filter((item) => new Date(item.ts).getTime() >= cutoff)
  } catch {
    // 轮询请求失败时静默，不打断页面浏览
  }
}

async function loadShadow() {
  if (!businessId.value) return
  try {
    shadow.value = await api.getShadow(businessId.value)
  } catch {
    shadow.value = null
  }
}

function handleRealtime(payload: string) {
  try {
    const data = JSON.parse(payload) as {
      ts?: number
      params?: Record<string, number | string | boolean>
    }
    if (!data.params) return
    const item: Telemetry = {
      deviceId: businessId.value,
      ts: new Date(data.ts ?? Date.now()).toISOString(),
      params: data.params,
      receivedAt: new Date().toISOString()
    }
    if (telemetry.value.some((row) => row.ts === item.ts)) return
    telemetry.value = [item, ...telemetry.value].slice(0, 60)
  } catch {
    // 忽略无法解析的消息
  }
}

onMounted(async () => {
  await Promise.all([productStore.ensureLoaded(), spaceStore.ensureLoaded()])
  await loadDevice()
  if (device.value) {
    await Promise.all([loadTelemetry(), loadShadow()])
    unsubscribeRealtime = subscribeRealtime(
      `default/${device.value.deviceId}/thing/property/report`,
      (_topic, payload) => handleRealtime(payload)
    )
  }
  loading.value = false
  // 实时数据走 MQTT，轮询仅作为兜底（30 秒）
  timer = window.setInterval(loadTelemetry, 30_000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
  unsubscribeRealtime?.()
})

const desiredDialog = reactive({
  visible: false,
  saving: false,
  rows: [{ key: 'reportInterval', value: '3' }]
})

function openDesired() {
  const desired = shadow.value?.desired ?? {}
  const rows = Object.entries(desired).map(([key, value]) => ({
    key,
    value: typeof value === 'string' ? value : JSON.stringify(value)
  }))
  desiredDialog.rows = rows.length ? rows : [{ key: 'reportInterval', value: '3' }]
  desiredDialog.visible = true
}

function addRow() {
  desiredDialog.rows.push({ key: '', value: '' })
}

function removeRow(index: number) {
  desiredDialog.rows.splice(index, 1)
}

function parseValue(raw: string): unknown {
  const text = raw.trim()
  if (text === 'true') return true
  if (text === 'false') return false
  if (text !== '' && !Number.isNaN(Number(text))) return Number(text)
  return raw
}

async function saveDesired() {
  if (!shadow.value || !businessId.value) return
  const params: Record<string, unknown> = {}
  for (const row of desiredDialog.rows) {
    const key = row.key.trim()
    if (!key) continue
    params[key] = parseValue(row.value)
  }
  if (Object.keys(params).length === 0) {
    ElMessage.warning('请至少填写一个期望属性')
    return
  }
  desiredDialog.saving = true
  try {
    shadow.value = await api.updateDesired(businessId.value, {
      params,
      version: shadow.value.version
    })
    ElMessage.success('期望值已下发（retained 消息，设备上线即同步）')
    desiredDialog.visible = false
    window.setTimeout(loadShadow, 1_500)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    desiredDialog.saving = false
  }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择')
  }
}

async function refreshAll() {
  await Promise.all([loadTelemetry(), loadShadow()])
  ElMessage.success('已刷新')
}
</script>

<template>
  <div class="detail">
    <div class="page-head detail-head">
      <div class="head-left">
        <el-button
          class="back-btn"
          circle
          :icon="ArrowLeft"
          aria-label="返回设备列表"
          @click="router.push('/devices')"
        />
        <div>
          <div class="title-row">
            <h2 class="page-title">{{ device?.name ?? '设备详情' }}</h2>
            <StatusBadge v-if="device" :status="device.status" />
          </div>
          <div class="id-row">
            <span class="mono muted">{{ device?.deviceId ?? '—' }}</span>
            <el-button
              v-if="device"
              text
              size="small"
              @click="copy(device.deviceId)"
            >
              复制
            </el-button>
            <span class="dot-sep">·</span>
            <span class="tiny">{{ productStore.nameOf(device?.productId) }}</span>
            <span class="dot-sep">·</span>
            <span class="tiny">{{ spaceStore.nameOf(device?.spaceId) }}</span>
            <span class="dot-sep">·</span>
            <span class="tiny">最近在线 {{ fmtRelative(device?.lastOnlineAt) }}</span>
          </div>
        </div>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="refreshAll">刷新</el-button>
        <el-button type="primary" :icon="Edit" @click="openDesired">设置期望值</el-button>
      </div>
    </div>

    <div class="card tabs-card" v-loading="loading">
      <el-tabs v-model="tab">
        <el-tab-pane label="实时监控" name="realtime">
          <div class="mini-grid">
            <div v-for="item in miniStats" :key="item.label" class="mini-stat" :class="item.tone">
              <span class="mini-label">{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
          </div>
          <div class="chart-panel">
            <div class="chart-head">
              <div>
                <h3>近 15 分钟遥测</h3>
                <p class="tiny">MQTT 实时推送 · 数据来自 TDengine</p>
              </div>
              <span class="chip accent">实时</span>
            </div>
            <MultiSeriesChart :series="series" height="320px" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="设备影子" name="shadow">
          <div class="shadow-grid">
            <div class="shadow-card">
              <div class="shadow-head">
                <div>
                  <h3>Desired · 期望状态</h3>
                  <p class="tiny">云端下发 · retained 消息</p>
                </div>
                <span class="chip">v{{ shadow?.version ?? 0 }}</span>
              </div>
              <pre class="json mono">{{ JSON.stringify(shadow?.desired ?? {}, null, 2) }}</pre>
            </div>
            <div class="shadow-card">
              <div class="shadow-head">
                <div>
                  <h3>Reported · 上报状态</h3>
                  <p class="tiny">设备实际状态 · 由设备上报</p>
                </div>
                <span class="chip accent">v{{ shadow?.version ?? 0 }}</span>
              </div>
              <pre class="json mono">{{ JSON.stringify(shadow?.reported ?? {}, null, 2) }}</pre>
            </div>
          </div>
          <p class="shadow-note">
            影子版本 {{ shadow?.version ?? 0 }} · 最后更新
            {{ fmtDateTime(shadow?.updatedAt) }} ·
            设置期望值后，设备上线会自动同步并回传 reported。
          </p>
        </el-tab-pane>

        <el-tab-pane label="历史数据" name="history">
          <el-table :data="telemetry" size="large">
            <el-table-column label="上报时间" width="200">
              <template #default="{ row }">{{ fmtDateTime(row.ts) }}</template>
            </el-table-column>
            <el-table-column label="物模型属性" min-width="320">
              <template #default="{ row }">
                <span v-for="(value, key) in row.params" :key="key" class="param-chip">
                  <em>{{ key }}</em>{{ value }}
                </span>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-state">
                <p>暂无历史数据</p>
                <span class="tiny">设备上报后将在这里保留最近 60 条</span>
              </div>
            </template>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="desiredDialog.visible" title="设置期望值" width="520px">
      <p class="dialog-hint">
        期望值以 retained MQTT 消息下发到设备，设备上线即可同步；版本 {{ shadow?.version ?? 0 }}。
      </p>
      <div class="desired-rows">
        <div v-for="(row, index) in desiredDialog.rows" :key="index" class="desired-row">
          <el-input v-model="row.key" placeholder="属性 identifier，如 reportInterval" />
          <el-input v-model="row.value" placeholder="值，如 3" />
          <el-button text :icon="Plus" aria-label="添加一行" @click="addRow" />
          <el-button
            text
            type="danger"
            :disabled="desiredDialog.rows.length === 1"
            aria-label="删除该行"
            @click="removeRow(index)"
          >
            ×
          </el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="desiredDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="desiredDialog.saving" @click="saveDesired">
          下发期望值
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.detail-head {
  align-items: center;
}

.head-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.back-btn {
  flex: none;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.id-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.dot-sep {
  color: var(--text-3);
}

.head-actions {
  display: flex;
  gap: 10px;
}

.tabs-card {
  padding: 8px 22px 22px;
}

.mini-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin: 8px 0 18px;
}

.mini-stat {
  border-radius: var(--radius-md);
  padding: 14px 16px;
  background: rgba(0, 0, 0, 0.02);
  border: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.mini-label {
  font-size: 12px;
  color: var(--text-2);
}

.mini-stat strong {
  font-size: 22px;
  font-weight: 650;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}

.mini-stat.orange strong {
  color: var(--orange);
}
.mini-stat.blue strong {
  color: var(--accent);
}
.mini-stat.violet strong {
  color: var(--violet);
  font-size: 18px;
}

.chart-panel {
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 16px 18px 8px;
}

.chart-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.chart-head h3 {
  font-size: 14.5px;
  font-weight: 600;
}

.chart-head .tiny {
  margin-top: 4px;
}

.shadow-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 8px;
}

.shadow-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 16px 18px;
  background: rgba(255, 255, 255, 0.55);
}

.shadow-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.shadow-head h3 {
  font-size: 14px;
  font-weight: 600;
}

.shadow-head .tiny {
  margin-top: 4px;
}

.json {
  margin: 0;
  padding: 14px 16px;
  background: #0f172a;
  color: #d7e3f4;
  border-radius: 12px;
  font-size: 12px;
  line-height: 1.75;
  overflow: auto;
  max-height: 280px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.shadow-note {
  margin-top: 14px;
  font-size: 12px;
  color: var(--text-3);
}

.param-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  margin-right: 8px;
  padding: 4px 10px;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.035);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.param-chip em {
  font-style: normal;
  color: var(--text-3);
  font-size: 11px;
}

.dialog-hint {
  font-size: 12.5px;
  color: var(--text-2);
  margin-bottom: 14px;
  line-height: 1.7;
}

.desired-rows {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.desired-row {
  display: grid;
  grid-template-columns: 1.1fr 1fr auto auto;
  gap: 8px;
  align-items: center;
}

@media (max-width: 900px) {
  .shadow-grid {
    grid-template-columns: 1fr;
  }
  .mini-grid {
    grid-template-columns: 1fr;
  }
  .head-actions {
    width: 100%;
  }
}
</style>
