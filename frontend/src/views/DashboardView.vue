<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Box, Cpu, DataLine, Monitor } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import type { Overview, Telemetry, TrendPoint } from '../api/types'
import StatCard from '../components/StatCard.vue'
import LineAreaChart from '../components/LineAreaChart.vue'
import DonutChart from '../components/DonutChart.vue'
import { subscribeRealtime } from '../realtime/mqtt'
import { useAuthStore } from '../stores/auth'
import { useProjectStore } from '../stores/projects'
import { useSpaceStore } from '../stores/spaces'
import { fmtDateTime, fmtNumber } from '../utils/format'

const spaceStore = useSpaceStore()
const projectStore = useProjectStore()
const auth = useAuthStore()

const overview = ref<Overview | null>(null)
const trend = ref<TrendPoint[]>([])
const recent = ref<Telemetry[]>([])
const range = ref(24)
const loading = ref(true)
let timer: number | undefined
let unsubscribeRealtime: (() => void) | undefined

const onlineRate = computed(() => {
  if (!overview.value || overview.value.deviceTotal === 0) return '—'
  return `${Math.round((overview.value.online / overview.value.deviceTotal) * 100)}%`
})

const trendPoints = computed(() =>
  trend.value.map((point) => ({
    label: new Date(point.time).toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit'
    }),
    value: point.count
  }))
)

const donutData = computed(() => {
  if (!overview.value) return []
  return [
    { name: '在线', value: overview.value.online, color: '#2fb457' },
    { name: '离线', value: overview.value.offline, color: '#c7c7cc' },
    { name: '未激活', value: overview.value.inactive, color: '#e8860c' },
    { name: '已禁用', value: overview.value.disabled, color: '#ff453a' }
  ].filter((item) => item.value > 0)
})

async function loadOverview() {
  try {
    overview.value = await api.overview(
      spaceStore.currentSpaceId,
      projectStore.currentProjectId
    )
  } catch (error) {
    if (loading.value) ElMessage.error((error as Error).message)
  }
}

async function loadTrend() {
  try {
    trend.value = await api.telemetryTrend(
      range.value,
      spaceStore.currentSpaceId,
      projectStore.currentProjectId
    )
  } catch (error) {
    if (loading.value) ElMessage.error((error as Error).message)
  }
}

async function loadRecent() {
  try {
    recent.value = await api.recentTelemetry(
      8,
      spaceStore.currentSpaceId,
      projectStore.currentProjectId
    )
  } catch (error) {
    if (loading.value) ElMessage.error((error as Error).message)
  }
}

async function setRange(hours: number) {
  range.value = hours
  await loadTrend()
}

watch(
  () => [projectStore.currentProjectId, spaceStore.currentSpaceId],
  () => {
    void loadOverview()
    void loadTrend()
    void loadRecent()
  }
)

onMounted(async () => {
  await spaceStore.ensureLoaded()
  await Promise.all([loadOverview(), loadTrend(), loadRecent()])
  loading.value = false
  // 空间受限用户不使用全平台通配符订阅，实时数据由轮询兜底
  const tenantId = auth.user?.tenantId
  if (tenantId && auth.user?.spaceId == null) {
    unsubscribeRealtime = subscribeRealtime(`${tenantId}/+/thing/property/report`, (topic, payload) => {
    try {
      const data = JSON.parse(payload) as {
        ts?: number
        params?: Record<string, number | string | boolean>
      }
      if (!data.params) return
      const levels = topic.split('/')
      const item: Telemetry = {
        deviceId: levels[1] ?? '—',
        ts: new Date(data.ts ?? Date.now()).toISOString(),
        params: data.params,
        receivedAt: new Date().toISOString()
      }
      if (recent.value.some((row) => row.deviceId === item.deviceId && row.ts === item.ts)) return
      recent.value = [item, ...recent.value].slice(0, 8)
    } catch {
      // 忽略无法解析的消息
    }
    })
  }
  timer = window.setInterval(() => {
    loadOverview()
    loadRecent()
  }, 15_000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
  unsubscribeRealtime?.()
})
</script>

<template>
  <div class="dashboard">
    <div class="page-head">
      <div>
        <h2 class="page-title">运行总览</h2>
        <p class="page-sub">全平台设备接入、消息吞吐与运行状态一览</p>
      </div>
      <div class="segmented">
        <button :class="{ active: range === 24 }" @click="setRange(24)">近 24 小时</button>
        <button :class="{ active: range === 168 }" @click="setRange(168)">近 7 天</button>
      </div>
    </div>

    <section class="kpi-grid">
      <StatCard
        label="设备总数"
        :value="fmtNumber(overview?.deviceTotal)"
        :note="`覆盖 ${overview?.productTotal ?? 0} 个产品`"
        tone="blue"
      >
        <template #icon><el-icon><Cpu /></el-icon></template>
      </StatCard>
      <StatCard
        label="在线设备"
        :value="fmtNumber(overview?.online)"
        :note="`设备在线率 ${onlineRate}`"
        tone="green"
      >
        <template #icon><el-icon><Monitor /></el-icon></template>
      </StatCard>
      <StatCard
        label="今日消息"
        :value="fmtNumber(overview?.telemetryToday)"
        :note="`累计入库 ${fmtNumber(overview?.telemetryTotal)} 条`"
        tone="violet"
      >
        <template #icon><el-icon><DataLine /></el-icon></template>
      </StatCard>
      <StatCard
        label="产品数量"
        :value="fmtNumber(overview?.productTotal)"
        note="设备模板与物模型定义"
        tone="orange"
      >
        <template #icon><el-icon><Box /></el-icon></template>
      </StatCard>
    </section>

    <section class="charts-grid">
      <div class="card panel">
        <div class="panel-head">
          <div>
            <h3>消息吞吐趋势</h3>
            <p class="tiny">按小时聚合 · 物模型校验通过的上报</p>
          </div>
          <span class="chip accent">QoS 1</span>
        </div>
        <LineAreaChart :points="trendPoints" height="264px" />
      </div>

      <div class="card panel">
        <div class="panel-head">
          <div>
            <h3>设备状态分布</h3>
            <p class="tiny">实时在线与历史设备</p>
          </div>
        </div>
        <DonutChart :data="donutData" height="196px" center-label="台设备" />
        <ul class="legend">
          <li v-for="item in donutData" :key="item.name">
            <span class="dot" :style="{ background: item.color }" />
            <span>{{ item.name }}</span>
            <strong>{{ item.value }}</strong>
          </li>
          <li v-if="donutData.length === 0" class="legend-empty">暂无设备数据</li>
        </ul>
      </div>
    </section>

    <section class="card panel recent-panel">
      <div class="panel-head">
        <div>
          <h3>最近遥测</h3>
          <p class="tiny">来自 TDengine 的实时数据流 · 每 15 秒刷新</p>
        </div>
        <router-link to="/devices" class="link">查看全部设备 →</router-link>
      </div>

      <el-table v-loading="loading" :data="recent" size="large">
        <el-table-column label="设备" min-width="190">
          <template #default="{ row }">
            <span class="mono">{{ row.deviceId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="物模型属性" min-width="280">
          <template #default="{ row }">
            <span v-for="(value, key) in row.params" :key="key" class="param-chip">
              <em>{{ key }}</em>{{ value }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="上报时间" width="190" align="right">
          <template #default="{ row }">{{ fmtDateTime(row.ts) }}</template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>暂无遥测数据</p>
            <span class="tiny">启动模拟器后，数据将在此实时呈现</span>
          </div>
        </template>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.charts-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(300px, 1fr);
  gap: 16px;
  margin-top: 16px;
}

.panel {
  padding: 20px 22px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.panel-head h3 {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.panel-head .tiny {
  margin-top: 4px;
}

.legend {
  list-style: none;
  margin: 8px 0 0;
  padding: 12px 2px 0;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.legend li {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 12.5px;
  color: var(--text-2);
}

.legend strong {
  margin-left: auto;
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
  font-variant-numeric: tabular-nums;
}

.legend .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.legend-empty {
  color: var(--text-3);
}

.link {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--accent);
  white-space: nowrap;
}

.recent-panel {
  margin-top: 16px;
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
  color: var(--text);
  font-variant-numeric: tabular-nums;
}

.param-chip em {
  font-style: normal;
  color: var(--text-3);
  font-size: 11px;
}

@media (max-width: 1100px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .charts-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .kpi-grid {
    grid-template-columns: 1fr;
  }
}
</style>
