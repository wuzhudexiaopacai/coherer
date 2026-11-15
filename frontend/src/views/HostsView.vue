<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { Delete, Link, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import type { Device, Host, HostRegisterResult } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useProjectStore } from '../stores/projects'
import { useSpaceStore } from '../stores/spaces'
import { fmtDateTime, fmtRelative } from '../utils/format'

const auth = useAuthStore()
const projectStore = useProjectStore()
const spaceStore = useSpaceStore()

const filters = reactive({ keyword: '' })
const state = reactive({
  current: 1,
  size: 10,
  total: 0,
  records: [] as Host[],
  loading: false
})

const registerDialog = reactive({
  visible: false,
  loading: false,
  name: '',
  spaceId: '',
  model: '',
  firmwareVersion: ''
})

const secretDialog = reactive({
  visible: false,
  title: '',
  hostId: '',
  name: '',
  secret: ''
})

const bindDialog = reactive({
  visible: false,
  loading: false,
  host: null as Host | null,
  bound: [] as Device[],
  candidates: [] as Device[]
})

const statusLabels: Record<string, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
  INACTIVE: '未激活',
  DISABLED: '已禁用'
}

async function load() {
  state.loading = true
  try {
    const page = await api.listHosts({
      current: state.current,
      size: state.size,
      keyword: filters.keyword || undefined,
      projectId: projectStore.currentProjectId ?? undefined,
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

function openRegister() {
  registerDialog.name = ''
  registerDialog.spaceId = spaceStore.currentSpaceId ?? spaceStore.flat[0]?.id ?? ''
  registerDialog.model = ''
  registerDialog.firmwareVersion = ''
  registerDialog.visible = true
}

async function submitRegister() {
  if (!registerDialog.name.trim()) {
    ElMessage.warning('请输入主机名称')
    return
  }
  registerDialog.loading = true
  try {
    const result = await api.registerHost({
      name: registerDialog.name.trim(),
      spaceId: registerDialog.spaceId || undefined,
      model: registerDialog.model.trim() || undefined,
      firmwareVersion: registerDialog.firmwareVersion.trim() || undefined
    })
    registerDialog.visible = false
    showSecret(result, '主机已注册')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    registerDialog.loading = false
  }
}

function showSecret(result: HostRegisterResult, title: string) {
  secretDialog.title = title
  secretDialog.hostId = result.hostId
  secretDialog.name = result.name
  secretDialog.secret = result.hostSecret
  secretDialog.visible = true
}

async function toggleHost(row: Host) {
  const disabling = row.status !== 'DISABLED'
  try {
    await ElMessageBox.confirm(
      disabling
        ? `禁用后主机无法接入，绑定设备将无法经主机转发命令。确认禁用「${row.name}」？`
        : `确认启用「${row.name}」？`,
      disabling ? '禁用主机' : '启用主机',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    if (disabling) {
      await api.disableHost(row.id)
    } else {
      await api.enableHost(row.id)
    }
    ElMessage.success(disabling ? '主机已禁用' : '主机已启用')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function rotateSecret(row: Host) {
  try {
    await ElMessageBox.confirm(
      `重置后旧密钥立即失效。确认重置「${row.name}」的主机密钥？`,
      '重置主机密钥',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const result = await api.rotateHostSecret(row.id)
    showSecret(result, '主机密钥已重置')
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function removeHost(row: Host) {
  try {
    await ElMessageBox.confirm(
      `删除后子设备会自动解绑并转为直连。确认删除「${row.name}」？`,
      '删除主机',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await api.deleteHost(row.id)
    ElMessage.success('主机已删除')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

function handleCommand(command: string, row: Host) {
  if (command === 'rotate') {
    void rotateSecret(row)
  } else if (command === 'delete') {
    void removeHost(row)
  }
}

async function openBind(row: Host) {
  bindDialog.host = row
  bindDialog.visible = true
  await loadBindData()
}

async function loadBindData() {
  if (!bindDialog.host) return
  bindDialog.loading = true
  try {
    const [bound, page] = await Promise.all([
      api.hostDevices(bindDialog.host.id),
      api.listDevices({
        current: 1,
        size: 100,
        projectId: projectStore.currentProjectId ?? undefined,
        spaceId: spaceStore.currentSpaceId ?? undefined
      })
    ])
    bindDialog.bound = bound
    bindDialog.candidates = page.records.filter((device) => !device.hostId)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    bindDialog.loading = false
  }
}

async function bind(deviceId: string) {
  if (!bindDialog.host) return
  try {
    await api.bindHostDevice(bindDialog.host.id, deviceId)
    ElMessage.success('设备已绑定')
    await loadBindData()
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function unbind(deviceId: string) {
  if (!bindDialog.host) return
  try {
    await api.unbindHostDevice(bindDialog.host.id, deviceId)
    ElMessage.success('设备已解绑')
    await loadBindData()
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
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

onMounted(() => {
  void spaceStore.ensureLoaded()
  void load()
})

watch(
  () => [projectStore.currentProjectId, spaceStore.currentSpaceId],
  () => {
    state.current = 1
    void load()
  }
)
</script>

<template>
  <div class="hosts">
    <div class="page-head">
      <div>
        <h2 class="page-title">主机管理</h2>
        <p class="page-sub">边缘接入主机/网关 · 子设备绑定 · 非 Wi-Fi 设备经主机接入</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="openRegister">
          注册主机
        </el-button>
      </div>
    </div>

    <section class="card">
      <div class="toolbar">
        <el-input
          v-model="filters.keyword"
          class="search"
          placeholder="搜索主机名称 / HostID"
          :prefix-icon="Search"
          clearable
          @keyup.enter="((state.current = 1), load())"
          @clear="((state.current = 1), load())"
        />
        <el-button @click="((state.current = 1), load())">查询</el-button>
      </div>

      <el-table v-loading="state.loading" :data="state.records" size="large">
        <el-table-column label="主机" min-width="210">
          <template #default="{ row }">
            <div class="host-cell">
              <span class="host-name">{{ row.name }}</span>
              <span class="mono host-id">{{ row.hostId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="空间" min-width="120">
          <template #default="{ row }">{{ spaceStore.nameOf(row.spaceId) }}</template>
        </el-table-column>
        <el-table-column label="型号 / 固件" min-width="150">
          <template #default="{ row }">
            <span class="tiny">{{ row.model || '—' }}</span>
            <span class="tiny"> / {{ row.firmwareVersion || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="子设备" width="90" align="center">
          <template #default="{ row }">
            <span class="chip">{{ row.childCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="chip" :class="{ accent: row.status === 'ONLINE', danger: row.status === 'DISABLED' }">
              {{ statusLabels[row.status] ?? row.status }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最近在线" width="120">
          <template #default="{ row }">
            <span :title="fmtDateTime(row.lastOnlineAt)">{{ fmtRelative(row.lastOnlineAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.canWrite" label="操作" width="190" align="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button size="small" text :icon="Link" @click="openBind(row)">设备</el-button>
              <el-button size="small" text type="primary" @click="toggleHost(row)">
                {{ row.status === 'DISABLED' ? '启用' : '禁用' }}
              </el-button>
              <el-dropdown trigger="click" @command="(cmd: string) => handleCommand(cmd, row)">
                <el-button size="small" text>更多</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="rotate">重置密钥</el-dropdown-item>
                    <el-dropdown-item command="delete" divided>删除主机</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>暂无主机</p>
            <span class="tiny">注册主机后，可把非 Wi-Fi 设备绑定到主机接入</span>
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

    <el-dialog v-model="registerDialog.visible" title="注册主机" width="520px">
      <div class="form-grid">
        <label class="field">
          <span class="field-label">主机名称</span>
          <el-input v-model="registerDialog.name" placeholder="例如：车间网关-01" maxlength="64" />
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
        <div class="form-row">
          <label class="field">
            <span class="field-label">型号（可选）</span>
            <el-input v-model="registerDialog.model" placeholder="GW-100" maxlength="64" />
          </label>
          <label class="field">
            <span class="field-label">固件版本（可选）</span>
            <el-input v-model="registerDialog.firmwareVersion" placeholder="1.0.0" maxlength="64" />
          </label>
        </div>
      </div>
      <p class="form-hint">主机密钥仅在注册/重置时返回一次，请写入主机配置。</p>
      <template #footer>
        <el-button @click="registerDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="registerDialog.loading" @click="submitRegister">
          注册
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="secretDialog.visible" :title="secretDialog.title" width="520px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="主机密钥仅显示一次"
        description="请立即写入主机端；关闭后无法再次查看。"
      />
      <div class="secret-item">
        <div class="secret-meta">
          <span class="mono">{{ secretDialog.hostId }}</span>
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

    <el-dialog
      v-model="bindDialog.visible"
      :title="`设备绑定 · ${bindDialog.host?.name ?? ''}`"
      width="720px"
    >
      <div v-loading="bindDialog.loading">
        <div class="bind-section">
          <div class="section-head">
            <h4>已绑定设备（{{ bindDialog.bound.length }}）</h4>
          </div>
          <el-table :data="bindDialog.bound" size="small">
            <el-table-column label="设备" min-width="190">
              <template #default="{ row }">
                <span class="mono tiny">{{ row.deviceId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="名称" min-width="130" prop="name" />
            <el-table-column label="状态" width="90" prop="status" />
            <el-table-column label="操作" width="90" align="right">
              <template #default="{ row }">
                <el-button size="small" text type="danger" @click="unbind(row.deviceId)">
                  解绑
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-state"><p>尚未绑定设备</p></div>
            </template>
          </el-table>
        </div>

        <div class="bind-section">
          <div class="section-head">
            <h4>可绑定设备（{{ bindDialog.candidates.length }}）</h4>
            <span class="tiny">仅显示当前空间下未绑定主机的设备</span>
          </div>
          <el-table :data="bindDialog.candidates.slice(0, 20)" size="small">
            <el-table-column label="设备" min-width="190">
              <template #default="{ row }">
                <span class="mono tiny">{{ row.deviceId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="名称" min-width="130" prop="name" />
            <el-table-column label="状态" width="90" prop="status" />
            <el-table-column label="操作" width="80" align="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="bind(row.deviceId)">
                  绑定
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-state"><p>没有可绑定设备</p></div>
            </template>
          </el-table>
        </div>
      </div>
      <template #footer>
        <el-button @click="bindDialog.visible = false">完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  gap: 10px;
}

.toolbar {
  display: flex;
  gap: 12px;
  padding: 12px 16px;
  margin-bottom: 8px;
}

.search {
  width: 280px;
}

.host-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.host-name {
  font-weight: 600;
}

.host-id {
  color: var(--text-3);
  font-size: 11.5px;
}

.chip.danger {
  background: rgba(255, 69, 58, 0.1);
  color: #d70015;
}

.row-actions {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
}

.row-actions :deep(.el-button) {
  margin-left: 0;
}

.form-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-row {
  display: flex;
  gap: 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex: 1;
}

.field-label {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text-2);
}

.field :deep(.el-select) {
  width: 100%;
}

.form-hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--text-3);
}

.secret-item {
  margin-top: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.02);
}

.secret-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.secret-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.secret-row code {
  flex: 1;
  overflow-wrap: anywhere;
  font-size: 12px;
}

.bind-section + .bind-section {
  margin-top: 18px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.section-head h4 {
  font-size: 13.5px;
  font-weight: 600;
}

@media (max-width: 900px) {
  .form-row {
    flex-direction: column;
  }
}
</style>
