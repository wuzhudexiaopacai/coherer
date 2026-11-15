<script setup lang="ts">
import { onMounted, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import type { Device, RegisterResult } from '../api/types'
import StatusBadge from '../components/StatusBadge.vue'
import { useProductStore } from '../stores/products'
import { useAuthStore } from '../stores/auth'
import { useSpaceStore } from '../stores/spaces'
import { fmtDateTime, fmtRelative } from '../utils/format'

const router = useRouter()
const productStore = useProductStore()
const spaceStore = useSpaceStore()
const auth = useAuthStore()

const filters = reactive({ keyword: '', status: '', productId: null as string | null })
const statusOptions = [
  { label: '全部', value: '' },
  { label: '在线', value: 'ONLINE' },
  { label: '离线', value: 'OFFLINE' },
  { label: '未激活', value: 'INACTIVE' },
  { label: '已禁用', value: 'DISABLED' }
]

const state = reactive({
  current: 1,
  size: 10,
  total: 0,
  records: [] as Device[],
  loading: false
})

async function load() {
  state.loading = true
  try {
    const page = await api.listDevices({
      current: state.current,
      size: state.size,
      keyword: filters.keyword || undefined,
      status: filters.status || undefined,
      productId: filters.productId ?? undefined,
      spaceId: spaceStore.currentSpaceId ?? undefined
    })
    state.records = page.records
    state.total = page.total
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    state.loading = false
  }
}

function applyFilters() {
  state.current = 1
  load()
}

function openDetail(row: Device) {
  router.push(`/devices/${row.id}`)
}

const registerDialog = reactive({
  visible: false,
  loading: false,
  productId: null as string | null,
  spaceId: null as string | null,
  count: 2,
  prefix: 'demo',
  results: [] as RegisterResult[]
})

/** 重置密钥后仅展示一次的新凭证 */
const secretDialog = reactive({
  visible: false,
  deviceId: '',
  name: '',
  secret: ''
})

async function toggleDevice(row: Device) {
  const disabling = row.status !== 'DISABLED'
  try {
    await ElMessageBox.confirm(
      disabling
        ? `禁用后设备密钥立即失效，且无法重新连接 MQTT。确认禁用「${row.name}」？`
        : `确认启用「${row.name}」？启用后设备可重新接入。`,
      disabling ? '禁用设备' : '启用设备',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    if (disabling) {
      await api.disableDevice(row.id)
    } else {
      await api.enableDevice(row.id)
    }
    ElMessage.success(disabling ? '设备已禁用' : '设备已启用')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function rotateSecret(row: Device) {
  try {
    await ElMessageBox.confirm(
      `重置后旧密钥立即失效，设备需使用新密钥重连。确认重置「${row.name}」的密钥？`,
      '重置设备密钥',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const result = await api.rotateDeviceSecret(row.id)
    secretDialog.deviceId = result.deviceId
    secretDialog.name = result.name
    secretDialog.secret = result.deviceSecret
    secretDialog.visible = true
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function removeDevice(row: Device) {
  try {
    await ElMessageBox.confirm(
      `删除后设备将无法接入，历史遥测保留。确认删除「${row.name}」？`,
      '删除设备',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await api.deleteDevice(row.id)
    ElMessage.success('设备已删除')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

function handleDeviceCommand(command: string, row: Device) {
  if (command === 'rotate') {
    void rotateSecret(row)
  } else if (command === 'delete') {
    void removeDevice(row)
  }
}

function openRegister() {
  registerDialog.productId = productStore.products[0]?.id ?? null
  // 受限用户默认落到自己的根空间（空间树只返回可见范围）
  registerDialog.spaceId = spaceStore.currentSpaceId ?? spaceStore.flat[0]?.id ?? null
  registerDialog.count = 2
  registerDialog.prefix = 'demo'
  registerDialog.results = []
  registerDialog.visible = true
}

async function submitRegister() {
  if (!registerDialog.productId) {
    ElMessage.warning('请选择产品')
    return
  }
  registerDialog.loading = true
  try {
    registerDialog.results = await api.registerDevices({
      productId: registerDialog.productId,
      count: registerDialog.count,
      namePrefix: registerDialog.prefix || undefined,
      spaceId: registerDialog.spaceId || undefined
    })
    ElMessage.success(`已注册 ${registerDialog.results.length} 台设备`)
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    registerDialog.loading = false
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

onMounted(async () => {
  await Promise.all([productStore.ensureLoaded(), spaceStore.ensureLoaded()])
  await load()
})

watch(
  () => spaceStore.currentSpaceId,
  () => applyFilters()
)
</script>

<template>
  <div class="devices">
    <div class="page-head">
      <div>
        <h2 class="page-title">设备管理</h2>
        <p class="page-sub">注册设备、查看在线状态，并进入设备详情观测实时数据</p>
      </div>
      <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="openRegister">注册设备</el-button>
    </div>

    <section class="card toolbar">
      <el-input
        v-model="filters.keyword"
        class="search"
        placeholder="搜索设备名称 / DeviceID"
        clearable
        :prefix-icon="Search"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      />
      <div class="segmented status-seg">
        <button
          v-for="option in statusOptions"
          :key="option.value"
          :class="{ active: filters.status === option.value }"
          @click="((filters.status = option.value), applyFilters())"
        >
          {{ option.label }}
        </button>
      </div>
      <el-select
        v-model="filters.productId"
        class="product-select"
        placeholder="全部产品"
        clearable
        @change="applyFilters"
      >
        <el-option
          v-for="product in productStore.products"
          :key="product.id"
          :label="product.name"
          :value="product.id"
        />
      </el-select>
    </section>

    <section class="card table-card">
      <el-table
        v-loading="state.loading"
        :data="state.records"
        size="large"
        @row-click="openDetail"
      >
        <el-table-column label="设备" min-width="220">
          <template #default="{ row }">
            <div class="device-cell">
              <span class="device-name">{{ row.name }}</span>
              <span class="mono device-id">{{ row.deviceId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="所属产品" min-width="140">
          <template #default="{ row }">
            <span>{{ productStore.nameOf(row.productId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="固件版本" width="108">
          <template #default="{ row }">
            <span class="mono tiny">{{ row.firmwareVersion ? 'v' + row.firmwareVersion : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="所属空间" min-width="120">
          <template #default="{ row }">
            <span>{{ spaceStore.nameOf(row.spaceId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="112">
          <template #default="{ row }">
            <StatusBadge :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="最近在线" width="120">
          <template #default="{ row }">
            <span :title="fmtDateTime(row.lastOnlineAt)">{{ fmtRelative(row.lastOnlineAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">
            <span class="tiny">{{ fmtDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.canWrite" label="操作" width="132" align="right">
          <template #default="{ row }">
            <div class="row-actions" @click.stop>
              <el-button size="small" text type="primary" @click="toggleDevice(row)">
                {{ row.status === 'DISABLED' ? '启用' : '禁用' }}
              </el-button>
              <el-dropdown trigger="click" @command="(cmd: string) => handleDeviceCommand(cmd, row)">
                <el-button size="small" text>更多</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="rotate">重置密钥</el-dropdown-item>
                    <el-dropdown-item command="delete" divided>删除设备</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>暂无设备</p>
            <span class="tiny">点击「注册设备」创建首批设备</span>
          </div>
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="state.current"
        v-model:page-size="state.size"
        :total="state.total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="load"
        @size-change="((state.current = 1), load())"
      />
    </section>

    <el-dialog v-model="registerDialog.visible" title="注册设备" width="560px">
      <template v-if="registerDialog.results.length === 0">
        <div class="form-grid">
          <label class="field">
            <span class="field-label">所属产品</span>
            <el-select v-model="registerDialog.productId" placeholder="选择产品">
              <el-option
                v-for="product in productStore.products"
                :key="product.id"
                :label="`${product.name} (${product.productKey})`"
                :value="product.id"
              />
            </el-select>
          </label>
          <label class="field">
            <span class="field-label">所属空间</span>
            <el-select v-model="registerDialog.spaceId" placeholder="选择空间">
              <el-option
                v-for="space in spaceStore.flat"
                :key="space.id"
                :label="'　'.repeat(space.depth) + space.name"
                :value="space.id"
              />
            </el-select>
          </label>
          <label class="field">
            <span class="field-label">注册数量</span>
            <el-input-number v-model="registerDialog.count" :min="1" :max="1000" />
          </label>
          <label class="field">
            <span class="field-label">设备名前缀</span>
            <el-input v-model="registerDialog.prefix" placeholder="demo" />
          </label>
        </div>
        <p class="form-hint">设备密钥仅在注册成功时返回一次，请妥善保存。</p>
      </template>

      <template v-else>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="设备密钥仅显示一次"
          description="请立即复制并保存到设备端；关闭后无法再次查看。"
          class="secret-alert"
        />
        <div class="secret-list">
          <div v-for="item in registerDialog.results" :key="item.deviceId" class="secret-item">
            <div class="secret-meta">
              <span class="mono">{{ item.deviceId }}</span>
              <span class="tiny">{{ item.name }}</span>
            </div>
            <div class="secret-row">
              <code class="mono">{{ item.deviceSecret }}</code>
              <el-button size="small" text :icon="Plus" @click="copy(item.deviceSecret)">复制</el-button>
            </div>
          </div>
        </div>
      </template>

      <template #footer>
        <el-button @click="registerDialog.visible = false">
          {{ registerDialog.results.length ? '完成' : '取消' }}
        </el-button>
        <el-button
          v-if="registerDialog.results.length === 0"
          type="primary"
          :loading="registerDialog.loading"
          @click="submitRegister"
        >
          注册
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="secretDialog.visible" title="设备密钥已重置" width="520px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="新密钥仅显示一次"
        description="旧密钥已立即失效，请把新密钥写入设备端；关闭后无法再次查看。"
        class="secret-alert"
      />
      <div class="secret-item">
        <div class="secret-meta">
          <span class="mono">{{ secretDialog.deviceId }}</span>
          <span class="tiny">{{ secretDialog.name }}</span>
        </div>
        <div class="secret-row">
          <code class="mono">{{ secretDialog.secret }}</code>
          <el-button size="small" text :icon="Plus" @click="copy(secretDialog.secret)">复制</el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="secretDialog.visible = false">完成</el-button>
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

.search {
  width: 280px;
}

.status-seg {
  margin-left: auto;
}

.product-select {
  width: 180px;
}

.table-card {
  padding: 8px 20px 16px;
}

.device-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  cursor: pointer;
}

.device-name {
  font-weight: 550;
  letter-spacing: -0.01em;
}

.device-id {
  color: var(--text-3);
  font-size: 11.5px;
}

.row-arrow {
  color: var(--text-3);
  font-size: 15px;
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

.field :deep(.el-select),
.field :deep(.el-input-number) {
  width: 100%;
}

.form-hint {
  margin-top: 14px;
  font-size: 12px;
  color: var(--text-3);
}

.secret-alert {
  margin-bottom: 16px;
}

.secret-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 320px;
  overflow: auto;
}

.secret-item {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 11px 14px;
  background: rgba(0, 0, 0, 0.015);
}

.secret-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 7px;
}

.secret-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.secret-row code {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-2);
}

@media (max-width: 900px) {
  .search {
    width: 100%;
  }
  .status-seg {
    margin-left: 0;
  }
  .product-select {
    width: 100%;
  }
}
</style>
