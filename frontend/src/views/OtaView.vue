<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Delete, Refresh, UploadFilled } from '@element-plus/icons-vue'
import type { UploadUserFile } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import type { Device, Firmware, OtaTask } from '../api/types'
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
let pollTimer: number | undefined

const activeStatuses = new Set(['PENDING', 'SENT', 'DOWNLOADING'])
const hasActiveTask = computed(() => tasks.value.some((task) => activeStatuses.has(task.status)))
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
  await Promise.all([loadFirmwares(), loadDevices()])
  await loadTasks()
})

watch(taskDeviceId, () => {
  void loadTasks()
})

watch(
  () => spaceStore.currentSpaceId,
  () => {
    void loadDevices()
  }
)

onMounted(async () => {
  await Promise.all([productStore.ensureLoaded(), spaceStore.ensureLoaded()])
  selectedProductId.value = productStore.products[0]?.id ?? null
  pollTimer = window.setInterval(() => {
    if (taskDeviceId.value && (hasActiveTask.value || tasksLoading.value)) {
      void loadTasks()
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
        <el-table-column v-if="auth.canWrite" label="操作" width="176" align="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" text @click="openStart(row)">升级设备</el-button>
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
}
</style>
