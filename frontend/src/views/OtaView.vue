<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Delete, Refresh, UploadFilled } from '@element-plus/icons-vue'
import type { UploadUserFile } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import type { Device, Firmware, OtaRollout, OtaRolloutDetail, OtaTask } from '../api/types'
import StatusBadge from '../components/StatusBadge.vue'
import { useAuthStore } from '../stores/auth'
import { useProductStore } from '../stores/products'
import { useSpaceStore } from '../stores/spaces'
import { fmtDateTime } from '../utils/format'

const auth = useAuthStore()
const productStore = useProductStore()
const spaceStore = useSpaceStore()

const selectedProductId = ref<string | null>(null)
const firmwares = ref<Firmware[]>([])
const firmwareLoading = ref(false)
const devices = ref<Device[]>([])
const devicesLoading = ref(false)
const taskDeviceId = ref<string | null>(null)
const tasks = ref<OtaTask[]>([])
const tasksLoading = ref(false)
const rollouts = ref<OtaRollout[]>([])
const rolloutLoading = ref(false)
let pollTimer: number | undefined

const activeStatuses = new Set(['PENDING', 'SENT', 'DOWNLOADING'])
const activeRolloutStatuses = new Set(['PENDING', 'RUNNING', 'PAUSED'])
const hasActiveTask = computed(() => tasks.value.some((task) => activeStatuses.has(task.status)))
const hasActiveRollout = computed(() =>
  rollouts.value.some((rollout) => activeRolloutStatuses.has(rollout.status))
)
const selectedProduct = computed(() =>
  productStore.products.find((item) => item.id === selectedProductId.value) ?? null
)

const uploadDialog = reactive({
  visible: false,
  loading: false,
  version: '',
  files: [] as UploadUserFile[]
})

const startDialog = reactive({
  visible: false,
  loading: false,
  firmware: null as Firmware | null,
  deviceId: null as string | null
})

const rolloutDialog = reactive({
  visible: false,
  loading: false,
  firmware: null as Firmware | null,
  name: '',
  targetType: 'SPACE' as 'DEVICE' | 'SPACE' | 'ALL',
  spaceId: null as string | null,
  deviceIds: [] as string[],
  batchSize: 10,
  failureThresholdPercent: 20,
  autoAdvance: true
})

const rolloutDetail = reactive({
  visible: false,
  loading: false,
  data: null as OtaRolloutDetail | null
})

function fmtSize(value: number | string): string {
  const bytes = Number(value) || 0
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function shortSha(sha: string): string {
  return sha ? `${sha.slice(0, 12)}…${sha.slice(-6)}` : '—'
}

async function loadFirmwares() {
  if (!selectedProductId.value) {
    firmwares.value = []
    return
  }
  firmwareLoading.value = true
  try {
    firmwares.value = await api.listFirmwares(selectedProductId.value)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    firmwareLoading.value = false
  }
}

async function loadDevices() {
  if (!selectedProductId.value) {
    devices.value = []
    taskDeviceId.value = null
    return
  }
  devicesLoading.value = true
  try {
    const page = await api.listDevices({
      current: 1,
      size: 200,
      productId: selectedProductId.value,
      spaceId: spaceStore.currentSpaceId ?? undefined
    })
    devices.value = page.records
    if (!devices.value.some((device) => device.deviceId === taskDeviceId.value)) {
      taskDeviceId.value = devices.value[0]?.deviceId ?? null
    }
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    devicesLoading.value = false
  }
}

async function loadTasks() {
  if (!taskDeviceId.value) {
    tasks.value = []
    return
  }
  tasksLoading.value = true
  try {
    tasks.value = await api.listOtaTasks(taskDeviceId.value, 20)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    tasksLoading.value = false
  }
}

async function loadRollouts() {
  if (!selectedProductId.value) {
    rollouts.value = []
    return
  }
  rolloutLoading.value = true
  try {
    const page = await api.listOtaRollouts({
      productId: selectedProductId.value,
      current: 1,
      size: 50
    })
    rollouts.value = page.records
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    rolloutLoading.value = false
  }
}

function openRollout(firmware: Firmware) {
  rolloutDialog.firmware = firmware
  rolloutDialog.name = `${firmware.version} 灰度发布`
  rolloutDialog.targetType = 'SPACE'
  rolloutDialog.spaceId = spaceStore.currentSpaceId ?? spaceStore.flat[0]?.id ?? null
  rolloutDialog.deviceIds = []
  rolloutDialog.batchSize = 10
  rolloutDialog.failureThresholdPercent = 20
  rolloutDialog.autoAdvance = true
  rolloutDialog.visible = true
}

async function submitRollout() {
  if (!rolloutDialog.firmware) {
    return
  }
  if (!rolloutDialog.name.trim()) {
    ElMessage.warning('请输入批次名称')
    return
  }
  if (rolloutDialog.targetType === 'DEVICE' && rolloutDialog.deviceIds.length === 0) {
    ElMessage.warning('请选择目标设备')
    return
  }
  if (rolloutDialog.targetType === 'SPACE' && !rolloutDialog.spaceId) {
    ElMessage.warning('请选择目标空间')
    return
  }
  rolloutDialog.loading = true
  try {
    const detail = await api.createOtaRollout({
      firmwareId: rolloutDialog.firmware.id,
      name: rolloutDialog.name.trim(),
      targetType: rolloutDialog.targetType,
      spaceId: rolloutDialog.targetType === 'SPACE'
        ? rolloutDialog.spaceId ?? undefined
        : undefined,
      deviceIds: rolloutDialog.targetType === 'DEVICE'
        ? rolloutDialog.deviceIds
        : undefined,
      batchSize: rolloutDialog.batchSize,
      failureThresholdPercent: rolloutDialog.failureThresholdPercent,
      autoAdvance: rolloutDialog.autoAdvance
    })
    ElMessage.success(`灰度批次已创建，共 ${detail.totalCount} 台设备 / ${detail.totalBatches} 批`)
    rolloutDialog.visible = false
    await loadRollouts()
    await openRolloutDetail(detail.id)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    rolloutDialog.loading = false
  }
}

async function openRolloutDetail(id: string) {
  rolloutDetail.visible = true
  rolloutDetail.loading = true
  try {
    rolloutDetail.data = await api.getOtaRollout(id)
  } catch (error) {
    ElMessage.error((error as Error).message)
    rolloutDetail.visible = false
  } finally {
    rolloutDetail.loading = false
  }
}

async function refreshRolloutDetail() {
  if (!rolloutDetail.data) return
  try {
    rolloutDetail.data = await api.getOtaRollout(rolloutDetail.data.id)
  } catch {
    // 轮询失败不打断抽屉
  }
}

async function pauseRollout(row: OtaRollout) {
  let reason = '人工暂停'
  try {
    const result = await ElMessageBox.prompt('暂停后当前批次继续执行，后续批次不再下发。', '暂停灰度', {
      inputValue: '人工暂停',
      confirmButtonText: '暂停',
      cancelButtonText: '取消',
      inputValidator: (value: string) => (value && value.trim() ? true : '请输入暂停原因')
    })
    reason = result.value.trim()
  } catch {
    return
  }
  try {
    await api.pauseOtaRollout(row.id, reason)
    ElMessage.success('灰度批次已暂停')
    await loadRollouts()
    if (rolloutDetail.visible && rolloutDetail.data?.id === row.id) await refreshRolloutDetail()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function resumeRollout(row: OtaRollout) {
  try {
    await ElMessageBox.confirm(
      `恢复后将继续推进第 ${row.currentBatch + 1} 批及后续批次。确认恢复「${row.name}」？`,
      '恢复灰度',
      { type: 'info', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await api.resumeOtaRollout(row.id)
    ElMessage.success('灰度批次已恢复')
    await loadRollouts()
    if (rolloutDetail.visible && rolloutDetail.data?.id === row.id) await refreshRolloutDetail()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function cancelRollout(row: OtaRollout) {
  try {
    await ElMessageBox.confirm(
      '取消后未下发的设备会标记为已跳过，已下发的设备继续执行。确认取消？',
      '取消灰度',
      { type: 'warning', confirmButtonText: '取消批次', cancelButtonText: '返回' }
    )
  } catch {
    return
  }
  try {
    await api.cancelOtaRollout(row.id)
    ElMessage.success('灰度批次已取消')
    await loadRollouts()
    if (rolloutDetail.visible && rolloutDetail.data?.id === row.id) await refreshRolloutDetail()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

function targetLabel(row: OtaRollout): string {
  switch (row.targetType) {
    case 'DEVICE':
      return '指定设备'
    case 'SPACE':
      return '按空间'
    default:
      return '产品全量'
  }
}

function rolloutProcessed(row: OtaRollout): number {
  return row.successCount + row.failedCount + row.skippedCount
}

function rolloutPercent(row: OtaRollout): number {
  if (!row.totalCount) return 0
  return Math.min(100, Math.round((rolloutProcessed(row) * 100) / row.totalCount))
}

function openUpload() {
  uploadDialog.version = ''
  uploadDialog.files = []
  uploadDialog.visible = true
}

async function submitUpload() {
  if (!selectedProductId.value) {
    ElMessage.warning('请先选择产品')
    return
  }
  if (!uploadDialog.version.trim()) {
    ElMessage.warning('请输入固件版本号')
    return
  }
  const file = uploadDialog.files[0]?.raw
  if (!file) {
    ElMessage.warning('请选择固件文件')
    return
  }
  uploadDialog.loading = true
  try {
    const firmware = await api.uploadFirmware(
      selectedProductId.value,
      uploadDialog.version.trim(),
      file
    )
    ElMessage.success(`固件 ${firmware.version} 已上传`)
    uploadDialog.visible = false
    await loadFirmwares()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    uploadDialog.loading = false
  }
}

async function removeFirmware(firmware: Firmware) {
  try {
    await ElMessageBox.confirm(
      `删除后该版本固件文件将被移除，历史升级记录保留。确认删除 ${firmware.version}？`,
      '删除固件',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await api.deleteFirmware(firmware.id)
    ElMessage.success('固件已删除')
    await loadFirmwares()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

function openStart(firmware: Firmware) {
  startDialog.firmware = firmware
  startDialog.deviceId = devices.value[0]?.deviceId ?? null
  startDialog.visible = true
}

async function submitStart() {
  if (!startDialog.firmware || !startDialog.deviceId) {
    ElMessage.warning('请选择目标设备')
    return
  }
  const device = devices.value.find((item) => item.deviceId === startDialog.deviceId)
  const warning = device && device.status !== 'ONLINE'
    ? `设备当前状态为 ${device.status}，指令可能无法及时送达。仍要下发升级吗？`
    : `确认向设备「${device?.name ?? startDialog.deviceId}」下发固件 ${startDialog.firmware.version}？`
  try {
    await ElMessageBox.confirm(warning, '确认升级', {
      type: device && device.status !== 'ONLINE' ? 'warning' : 'info',
      confirmButtonText: '下发升级',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  startDialog.loading = true
  try {
    const task = await api.startOta(startDialog.deviceId, startDialog.firmware.id)
    ElMessage.success(task.status === 'FAILED' ? `下发失败：${task.error ?? ''}` : '升级指令已下发')
    startDialog.visible = false
    taskDeviceId.value = startDialog.deviceId
    await loadTasks()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    startDialog.loading = false
  }
}

function deviceLabel(device: Device): string {
  const status = device.status === 'ONLINE' ? '在线' : device.status === 'OFFLINE' ? '离线' : device.status
  return `${device.name}（${device.deviceId} · ${status}）`
}

watch(selectedProductId, async () => {
  taskDeviceId.value = null
  tasks.value = []
  rollouts.value = []
  await Promise.all([loadFirmwares(), loadDevices(), loadRollouts()])
  await loadTasks()
})

watch(taskDeviceId, () => {
  void loadTasks()
})

watch(
  () => spaceStore.currentSpaceId,
  () => {
    void loadDevices()
    void loadRollouts()
  }
)

onMounted(async () => {
  await Promise.all([productStore.ensureLoaded(), spaceStore.ensureLoaded()])
  selectedProductId.value = productStore.products[0]?.id ?? null
  pollTimer = window.setInterval(() => {
    if (taskDeviceId.value && (hasActiveTask.value || tasksLoading.value)) {
      void loadTasks()
    }
    if (hasActiveRollout.value) {
      void loadRollouts()
      if (rolloutDetail.visible && rolloutDetail.data
          && activeRolloutStatuses.has(rolloutDetail.data.status)) {
        void refreshRolloutDetail()
      }
    }
  }, 4000)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})
</script>

<template>
  <div class="ota">
    <div class="page-head">
      <div>
        <h2 class="page-title">固件升级</h2>
        <p class="page-sub">固件版本管理 · 签名下载 · OTA 任务进度追踪</p>
      </div>
      <el-button
        v-if="auth.canWrite"
        type="primary"
        :icon="UploadFilled"
        @click="openUpload"
      >
        上传固件
      </el-button>
    </div>

    <section class="card toolbar">
      <span class="toolbar-label">产品</span>
      <el-select
        v-model="selectedProductId"
        class="product-select"
        placeholder="选择产品"
        filterable
      >
        <el-option
          v-for="product in productStore.products"
          :key="product.id"
          :label="`${product.name} (${product.productKey})`"
          :value="product.id"
        />
      </el-select>
      <span v-if="selectedProduct" class="toolbar-hint">
        {{ selectedProduct.category || '未分类' }} · {{ firmwares.length }} 个固件版本
      </span>
      <el-button class="refresh-btn" :icon="Refresh" text @click="loadFirmwares">刷新</el-button>
    </section>

    <section class="card table-card">
      <div class="card-head">
        <div>
          <h3>固件版本</h3>
          <p class="tiny">固件包 SHA256 由服务端计算，设备下载后校验一致才安装</p>
        </div>
      </div>
      <el-table v-loading="firmwareLoading" :data="firmwares" size="large">
        <el-table-column label="版本" width="140">
          <template #default="{ row }">
            <span class="version-pill mono">v{{ row.version }}</span>
          </template>
        </el-table-column>
        <el-table-column label="文件" min-width="180">
          <template #default="{ row }">
            <div class="file-cell">
              <span>{{ row.fileName }}</span>
              <span class="tiny">{{ fmtSize(row.sizeBytes) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="SHA256" min-width="220">
          <template #default="{ row }">
            <span class="mono tiny" :title="row.sha256">{{ shortSha(row.sha256) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="上传人" width="120">
          <template #default="{ row }">
            <span class="tiny">{{ row.createdBy || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="上传时间" width="150">
          <template #default="{ row }">
            <span class="tiny">{{ fmtDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.canWrite" label="操作" width="248" align="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" text @click="openStart(row)">升级设备</el-button>
            <el-button size="small" text @click="openRollout(row)">灰度发布</el-button>
            <el-button size="small" type="danger" text :icon="Delete" @click="removeFirmware(row)" />
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>{{ selectedProductId ? '该产品还没有固件' : '请选择产品' }}</p>
            <span class="tiny">
              {{ auth.canWrite ? '上传后的固件会出现在这里，并可下发到同产品设备' : '暂无固件版本' }}
            </span>
          </div>
        </template>
      </el-table>
    </section>

    <section class="card table-card rollout-card">
      <div class="card-head">
        <div>
          <h3>灰度批次</h3>
          <p class="tiny">按设备/空间/产品全量分批推进；单批失败率超阈值自动暂停，可人工恢复或取消</p>
        </div>
        <el-button :icon="Refresh" text @click="loadRollouts">刷新</el-button>
      </div>
      <el-table v-loading="rolloutLoading" :data="rollouts" size="large" row-key="id">
        <el-table-column label="批次" min-width="200">
          <template #default="{ row }">
            <div class="rollout-name-cell">
              <span class="rollout-name">{{ row.name }}</span>
              <span class="tiny mono">v{{ row.firmwareVersion || '—' }} · {{ targetLabel(row) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="118">
          <template #default="{ row }">
            <StatusBadge :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="230">
          <template #default="{ row }">
            <div class="rollout-progress">
              <el-progress
                :percentage="rolloutPercent(row)"
                :stroke-width="8"
                :status="row.status === 'SUCCESS' ? 'success'
                  : row.status === 'FAILED' || row.status === 'PARTIAL_FAILED' ? 'exception' : undefined"
              />
              <span class="tiny">
                成功 {{ row.successCount }} · 失败 {{ row.failedCount }} · 共 {{ row.totalCount }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="批次" width="96">
          <template #default="{ row }">
            <span class="mono tiny">{{ row.currentBatch }}/{{ row.totalBatches }}</span>
          </template>
        </el-table-column>
        <el-table-column label="熔断阈值" width="96">
          <template #default="{ row }">
            <span class="tiny">{{ row.failureThresholdPercent }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">
            <span class="tiny">{{ fmtDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="196" align="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openRolloutDetail(row.id)">详情</el-button>
            <el-button
              v-if="auth.canWrite && (row.status === 'RUNNING' || row.status === 'PENDING')"
              size="small"
              text
              @click="pauseRollout(row)"
            >
              暂停
            </el-button>
            <el-button
              v-if="auth.canWrite && row.status === 'PAUSED'"
              size="small"
              text
              type="primary"
              @click="resumeRollout(row)"
            >
              恢复
            </el-button>
            <el-button
              v-if="auth.canWrite && activeRolloutStatuses.has(row.status)"
              size="small"
              text
              type="danger"
              @click="cancelRollout(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>暂无灰度批次</p>
            <span class="tiny">在固件列表点击「灰度发布」创建分批升级</span>
          </div>
        </template>
      </el-table>
    </section>

    <section class="card table-card task-card">
      <div class="card-head task-head">
        <div>
          <h3>升级任务</h3>
          <p class="tiny">设备下载、校验、安装的实时进度；进行中任务每 4 秒刷新</p>
        </div>
        <div class="task-actions">
          <el-select
            v-model="taskDeviceId"
            class="device-select"
            placeholder="选择设备查看任务"
            filterable
            :loading="devicesLoading"
          >
            <el-option
              v-for="device in devices"
              :key="device.deviceId"
              :label="deviceLabel(device)"
              :value="device.deviceId"
            />
          </el-select>
          <el-button :icon="Refresh" text @click="loadTasks">刷新</el-button>
        </div>
      </div>
      <el-table v-loading="tasksLoading" :data="tasks" size="large">
        <el-table-column label="固件版本" width="140">
          <template #default="{ row }">
            <span class="version-pill mono">v{{ row.firmwareVersion || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="118">
          <template #default="{ row }">
            <StatusBadge :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="200">
          <template #default="{ row }">
            <el-progress
              :percentage="row.progress"
              :stroke-width="8"
              :status="row.status === 'SUCCESS' ? 'success'
                : row.status === 'FAILED' || row.status === 'TIMEOUT' ? 'exception' : undefined"
            />
          </template>
        </el-table-column>
        <el-table-column label="下发时间" width="150">
          <template #default="{ row }">
            <span class="tiny">{{ fmtDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="完成时间" width="150">
          <template #default="{ row }">
            <span class="tiny">{{ fmtDateTime(row.finishedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="详情" min-width="180">
          <template #default="{ row }">
            <span v-if="row.error" class="error-text" :title="row.error">{{ row.error }}</span>
            <span v-else class="tiny">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>{{ taskDeviceId ? '该设备暂无升级任务' : '请选择设备' }}</p>
            <span class="tiny">在固件列表点击「升级设备」创建任务</span>
          </div>
        </template>
      </el-table>
    </section>

    <el-dialog v-model="uploadDialog.visible" title="上传固件" width="560px">
      <div class="form-grid">
        <label class="field">
          <span class="field-label">目标产品</span>
          <el-input :model-value="selectedProduct?.name ?? '未选择'" disabled />
        </label>
        <label class="field">
          <span class="field-label">版本号</span>
          <el-input
            v-model="uploadDialog.version"
            placeholder="例如 1.0.1"
            maxlength="32"
            show-word-limit
          />
          <span class="field-hint">允许字母、数字、点、下划线、短横线，同产品内唯一</span>
        </label>
        <el-upload
          v-model:file-list="uploadDialog.files"
          class="uploader"
          drag
          :auto-upload="false"
          :limit="1"
          accept=".bin,.img,.hex,.tar,.gz,.zip"
        >
          <el-icon class="upload-icon"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖拽固件到此处，或<em>点击选择</em></div>
          <template #tip>
            <div class="el-upload__tip">单个文件不超过 64 MB；上传后由服务端计算 SHA256</div>
          </template>
        </el-upload>
      </div>
      <template #footer>
        <el-button @click="uploadDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="uploadDialog.loading" @click="submitUpload">上传</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="startDialog.visible" title="下发固件升级" width="560px">
      <div class="form-grid">
        <label class="field">
          <span class="field-label">固件版本</span>
          <el-input :model-value="startDialog.firmware?.version ?? ''" disabled />
        </label>
        <label class="field">
          <span class="field-label">目标设备</span>
          <el-select
            v-model="startDialog.deviceId"
            filterable
            placeholder="选择同产品设备"
            :loading="devicesLoading"
          >
            <el-option
              v-for="device in devices"
              :key="device.deviceId"
              :label="deviceLabel(device)"
              :value="device.deviceId"
            />
          </el-select>
        </label>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="设备将在一个任务内完成下载与安装"
          description="平台生成 10 分钟有效的签名下载 URL；设备校验 SHA256 后安装并回报进度。离线设备会等待超时。"
        />
      </div>
      <template #footer>
        <el-button @click="startDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="startDialog.loading" @click="submitStart">
          下发升级
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rolloutDialog.visible" title="灰度发布" width="640px">
      <div class="form-grid">
        <label class="field">
          <span class="field-label">固件版本</span>
          <el-input :model-value="rolloutDialog.firmware?.version ?? ''" disabled />
        </label>
        <label class="field">
          <span class="field-label">批次名称</span>
          <el-input v-model="rolloutDialog.name" maxlength="64" show-word-limit />
        </label>
        <div class="field">
          <span class="field-label">目标范围</span>
          <el-radio-group v-model="rolloutDialog.targetType" class="target-group">
            <el-radio-button value="SPACE">按空间</el-radio-button>
            <el-radio-button value="DEVICE">指定设备</el-radio-button>
            <el-radio-button value="ALL">产品全量</el-radio-button>
          </el-radio-group>
        </div>
        <label v-if="rolloutDialog.targetType === 'SPACE'" class="field">
          <span class="field-label">目标空间（含子空间）</span>
          <el-select v-model="rolloutDialog.spaceId" placeholder="选择空间">
            <el-option
              v-for="space in spaceStore.flat"
              :key="space.id"
              :label="'　'.repeat(space.depth) + space.name"
              :value="space.id"
            />
          </el-select>
        </label>
        <label v-if="rolloutDialog.targetType === 'DEVICE'" class="field">
          <span class="field-label">目标设备（最多 1000 台）</span>
          <el-select
            v-model="rolloutDialog.deviceIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择同产品设备"
          >
            <el-option
              v-for="device in devices"
              :key="device.deviceId"
              :label="deviceLabel(device)"
              :value="device.deviceId"
            />
          </el-select>
        </label>
        <el-alert
          v-if="rolloutDialog.targetType === 'ALL'"
          type="info"
          :closable="false"
          show-icon
          title="产品全量"
          description="覆盖当前产品下、当前账号数据范围内的所有未禁用设备；创建时冻结成员快照。"
        />
        <div class="strategy-grid">
          <label class="field">
            <span class="field-label">每批设备数</span>
            <el-input-number v-model="rolloutDialog.batchSize" :min="1" :max="500" />
          </label>
          <label class="field">
            <span class="field-label">失败率熔断阈值</span>
            <el-input-number
              v-model="rolloutDialog.failureThresholdPercent"
              :min="0"
              :max="100"
              :step="5"
            />
          </label>
          <label class="field switch-field">
            <span class="field-label">自动推进下一批</span>
            <el-switch v-model="rolloutDialog.autoAdvance" />
          </label>
        </div>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="灰度策略"
          description="同一时间只推进一个批次；该批完成后失败率达到阈值会暂停后续批次。关闭自动推进时，每批完成后需人工恢复。"
        />
      </div>
      <template #footer>
        <el-button @click="rolloutDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="rolloutDialog.loading" @click="submitRollout">
          创建灰度
        </el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="rolloutDetail.visible"
      :title="rolloutDetail.data ? `灰度详情 · ${rolloutDetail.data.name}` : '灰度详情'"
      size="min(820px, 96vw)"
    >
      <div v-loading="rolloutDetail.loading" class="rollout-detail">
        <template v-if="rolloutDetail.data">
          <div class="detail-head">
            <StatusBadge :status="rolloutDetail.data.status" />
            <span class="mono tiny">v{{ rolloutDetail.data.firmwareVersion || '—' }}</span>
            <span class="tiny">{{ targetLabel(rolloutDetail.data) }}</span>
            <span class="tiny">{{ rolloutDetail.data.currentBatch }}/{{ rolloutDetail.data.totalBatches }} 批</span>
          </div>
          <el-alert
            v-if="rolloutDetail.data.pauseReason"
            type="warning"
            :closable="false"
            show-icon
            :title="rolloutDetail.data.pauseReason"
            class="pause-alert"
          />
          <div class="stat-grid">
            <div class="stat-cell">
              <span class="stat-label">总设备</span>
              <strong>{{ rolloutDetail.data.totalCount }}</strong>
            </div>
            <div class="stat-cell">
              <span class="stat-label">成功</span>
              <strong class="ok">{{ rolloutDetail.data.successCount }}</strong>
            </div>
            <div class="stat-cell">
              <span class="stat-label">失败</span>
              <strong class="bad">{{ rolloutDetail.data.failedCount }}</strong>
            </div>
            <div class="stat-cell">
              <span class="stat-label">执行中</span>
              <strong>{{ rolloutDetail.data.runningCount }}</strong>
            </div>
            <div class="stat-cell">
              <span class="stat-label">熔断阈值</span>
              <strong>{{ rolloutDetail.data.failureThresholdPercent }}%</strong>
            </div>
          </div>
          <el-table :data="rolloutDetail.data.devices" size="small" max-height="520">
            <el-table-column label="批次" width="64">
              <template #default="{ row }">
                <span class="mono tiny">#{{ row.batchNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="设备" min-width="180">
              <template #default="{ row }">
                <div class="rollout-name-cell">
                  <span>{{ row.deviceName || row.deviceId }}</span>
                  <span class="mono tiny">{{ row.deviceId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="108">
              <template #default="{ row }">
                <StatusBadge :status="row.status" />
              </template>
            </el-table-column>
            <el-table-column label="任务进度" width="150">
              <template #default="{ row }">
                <el-progress
                  v-if="row.progress !== undefined && row.progress !== null"
                  :percentage="row.progress"
                  :stroke-width="6"
                  :status="row.taskStatus === 'FAILED' || row.taskStatus === 'TIMEOUT'
                    ? 'exception' : row.taskStatus === 'SUCCESS' ? 'success' : undefined"
                />
                <span v-else class="tiny">—</span>
              </template>
            </el-table-column>
            <el-table-column label="失败原因" min-width="160">
              <template #default="{ row }">
                <span v-if="row.error" class="error-text" :title="row.error">{{ row.error }}</span>
                <span v-else class="tiny">—</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="auth.canWrite" class="drawer-actions">
            <el-button
              v-if="rolloutDetail.data.status === 'RUNNING' || rolloutDetail.data.status === 'PENDING'"
              @click="pauseRollout(rolloutDetail.data)"
            >
              暂停
            </el-button>
            <el-button
              v-if="rolloutDetail.data.status === 'PAUSED'"
              type="primary"
              @click="resumeRollout(rolloutDetail.data)"
            >
              恢复
            </el-button>
            <el-button
              v-if="activeRolloutStatuses.has(rolloutDetail.data.status)"
              type="danger"
              plain
              @click="cancelRollout(rolloutDetail.data)"
            >
              取消批次
            </el-button>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.toolbar-label {
  font-size: 12.5px;
  font-weight: 550;
  color: var(--text-2);
}

.product-select {
  width: 260px;
}

.toolbar-hint {
  font-size: 12px;
  color: var(--text-3);
}

.refresh-btn {
  margin-left: auto;
}

.table-card {
  padding: 8px 20px 16px;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 2px 10px;
}

.card-head h3 {
  margin: 0 0 3px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.card-head p {
  margin: 0;
}

.task-card {
  margin-top: 16px;
}

.task-head {
  align-items: center;
}

.task-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.device-select {
  width: 300px;
}

.version-pill {
  display: inline-flex;
  align-items: center;
  height: 25px;
  padding: 0 9px;
  border-radius: 7px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
  font-weight: 600;
}

.file-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.error-text {
  color: var(--danger);
  font-size: 12px;
  display: inline-block;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

.form-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.field-label {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text-2);
}

.field-hint {
  font-size: 11.5px;
  color: var(--text-3);
}

.field :deep(.el-select) {
  width: 100%;
}

.uploader {
  width: 100%;
}

.uploader :deep(.el-upload),
.uploader :deep(.el-upload-dragger) {
  width: 100%;
}

.upload-icon {
  font-size: 34px;
  color: var(--text-3);
  margin-bottom: 6px;
}

.rollout-card {
  margin-top: 16px;
}

.rollout-name-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rollout-name {
  font-weight: 550;
  letter-spacing: -0.01em;
}

.rollout-progress {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.strategy-grid {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 16px;
  align-items: end;
}

.switch-field {
  min-width: 110px;
}

.target-group {
  width: 100%;
}

.target-group :deep(.el-radio-button) {
  flex: 1;
}

.target-group :deep(.el-radio-button__inner) {
  width: 100%;
}

.rollout-detail {
  min-height: 240px;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.pause-alert {
  margin-bottom: 16px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 18px;
}

.stat-cell {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.012);
}

.stat-label {
  font-size: 11.5px;
  color: var(--text-3);
}

.stat-cell strong {
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.stat-cell strong.ok {
  color: var(--success);
}

.stat-cell strong.bad {
  color: var(--danger);
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 18px;
}

@media (max-width: 900px) {
  .product-select,
  .device-select {
    width: 100%;
  }
  .toolbar-hint {
    display: none;
  }
  .task-head {
    flex-direction: column;
    align-items: stretch;
  }
  .strategy-grid {
    grid-template-columns: 1fr;
  }
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
