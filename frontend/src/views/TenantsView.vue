<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Edit, Plus, Search, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import type { Tenant } from '../api/types'
import StatusBadge from '../components/StatusBadge.vue'
import { useAuthStore } from '../stores/auth'
import { fmtDateTime } from '../utils/format'

const auth = useAuthStore()

const state = reactive({
  loading: false,
  records: [] as Tenant[]
})

const filters = reactive({ keyword: '', status: 'ALL' })

const createDialog = reactive({
  visible: false,
  loading: false,
  code: '',
  name: '',
  plan: 'STANDARD',
  description: '',
  adminUsername: 'admin',
  adminPassword: '',
  adminNickname: '租户管理员'
})

const editDialog = reactive({
  visible: false,
  loading: false,
  id: '',
  code: '',
  name: '',
  plan: 'STANDARD',
  status: 'ACTIVE' as 'ACTIVE' | 'DISABLED',
  description: ''
})

const planLabels: Record<string, string> = {
  STANDARD: '标准版',
  PRO: '专业版',
  ENTERPRISE: '企业版',
  TRIAL: '试用版'
}

const filtered = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase()
  return state.records.filter((item) => {
    if (filters.status !== 'ALL' && item.status !== filters.status) return false
    if (!keyword) return true
    return item.id.toLowerCase().includes(keyword)
      || item.name.toLowerCase().includes(keyword)
  })
})

const summary = computed(() => ({
  total: state.records.length,
  active: state.records.filter((item) => item.status === 'ACTIVE').length,
  users: state.records.reduce((sum, item) => sum + Number(item.userCount ?? 0), 0),
  devices: state.records.reduce((sum, item) => sum + Number(item.deviceCount ?? 0), 0)
}))

async function load() {
  state.loading = true
  try {
    state.records = await api.listTenants()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    state.loading = false
  }
}

function openCreate() {
  createDialog.code = ''
  createDialog.name = ''
  createDialog.plan = 'STANDARD'
  createDialog.description = ''
  createDialog.adminUsername = 'admin'
  createDialog.adminPassword = ''
  createDialog.adminNickname = '租户管理员'
  createDialog.visible = true
}

function openEdit(row: Tenant) {
  editDialog.id = row.id
  editDialog.code = row.id
  editDialog.name = row.name
  editDialog.plan = row.plan
  editDialog.status = row.status
  editDialog.description = row.description ?? ''
  editDialog.visible = true
}

async function submitCreate() {
  const code = createDialog.code.trim()
  if (!/^[A-Za-z][A-Za-z0-9_-]{2,31}$/.test(code)) {
    ElMessage.warning('租户代码需以字母开头，3-32 位字母/数字/下划线/短横线')
    return
  }
  if (!createDialog.name.trim()) {
    ElMessage.warning('请输入租户名称')
    return
  }
  if (!/^[A-Za-z][A-Za-z0-9_]{2,31}$/.test(createDialog.adminUsername.trim())) {
    ElMessage.warning('管理员用户名需以字母开头，3-32 位字母/数字/下划线')
    return
  }
  if (createDialog.adminPassword.length < 8) {
    ElMessage.warning('初始密码至少 8 位')
    return
  }
  createDialog.loading = true
  try {
    await api.createTenant({
      code,
      name: createDialog.name.trim(),
      plan: createDialog.plan,
      description: createDialog.description.trim() || undefined,
      adminUsername: createDialog.adminUsername.trim(),
      adminPassword: createDialog.adminPassword,
      adminNickname: createDialog.adminNickname.trim() || undefined
    })
    ElMessage.success(`租户 ${code} 已开通，并初始化默认项目、空间与管理员`)
    createDialog.visible = false
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    createDialog.loading = false
  }
}

async function submitEdit() {
  if (!editDialog.name.trim()) {
    ElMessage.warning('请输入租户名称')
    return
  }
  editDialog.loading = true
  try {
    await api.updateTenant(editDialog.id, {
      name: editDialog.name.trim(),
      plan: editDialog.plan,
      status: editDialog.status,
      description: editDialog.description.trim()
    })
    ElMessage.success('租户已更新')
    editDialog.visible = false
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    editDialog.loading = false
  }
}

async function toggleStatus(row: Tenant) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const action = next === 'DISABLED' ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(
      next === 'DISABLED'
        ? `禁用后「${row.name}」下所有账号将无法登录，现有令牌立即失效。确认${action}？`
        : `确认启用租户「${row.name}」？`,
      `${action}租户`,
      { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await api.updateTenant(row.id, { status: next })
    ElMessage.success(`租户已${action}`)
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

function canToggle(row: Tenant) {
  return row.id !== 'default' && row.id !== auth.user?.tenantId
}

onMounted(load)
</script>

<template>
  <div class="tenants">
    <div class="page-head">
      <div>
        <h2 class="page-title">租户管理</h2>
        <p class="page-sub">平台级租户生命周期；每个租户的数据由租户上下文与数据库拦截器完全隔离</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">开通租户</el-button>
    </div>

    <div class="summary-grid">
      <div class="summary-card">
        <span class="summary-label">租户总数</span>
        <strong>{{ summary.total }}</strong>
        <span class="tiny">平台内全部租户</span>
      </div>
      <div class="summary-card">
        <span class="summary-label">正常租户</span>
        <strong>{{ summary.active }}</strong>
        <span class="tiny">可登录并接入设备</span>
      </div>
      <div class="summary-card">
        <span class="summary-label">账号总数</span>
        <strong>{{ summary.users }}</strong>
        <span class="tiny">跨租户合计</span>
      </div>
      <div class="summary-card">
        <span class="summary-label">设备总数</span>
        <strong>{{ summary.devices }}</strong>
        <span class="tiny">跨租户合计</span>
      </div>
    </div>

    <section class="card table-card">
      <div class="toolbar">
        <el-input
          v-model="filters.keyword"
          class="search"
          placeholder="搜索租户代码 / 名称"
          :prefix-icon="Search"
          clearable
        />
        <el-select v-model="filters.status" class="status-filter" aria-label="租户状态筛选">
          <el-option label="全部状态" value="ALL" />
          <el-option label="正常" value="ACTIVE" />
          <el-option label="已禁用" value="DISABLED" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>

      <el-table v-loading="state.loading" :data="filtered" size="large">
        <el-table-column label="租户" min-width="230">
          <template #default="{ row }">
            <div class="tenant-cell">
              <span class="tenant-name">{{ row.name }}</span>
              <span class="mono tiny">{{ row.id }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusBadge :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="套餐" width="110">
          <template #default="{ row }">
            <span class="chip">{{ planLabels[row.plan] ?? row.plan }}</span>
          </template>
        </el-table-column>
        <el-table-column label="资源" min-width="210">
          <template #default="{ row }">
            <div class="counts">
              <span class="count-chip">用户 {{ row.userCount }}</span>
              <span class="count-chip">项目 {{ row.projectCount }}</span>
              <span class="count-chip">设备 {{ row.deviceCount }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="描述" min-width="160">
          <template #default="{ row }">
            <span class="tiny">{{ row.description || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">
            <span class="tiny">{{ fmtDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="right">
          <template #default="{ row }">
            <el-button size="small" text :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-tooltip
              :disabled="canToggle(row)"
              content="默认租户与当前登录租户不可在此禁用"
              placement="top"
            >
              <span>
                <el-button
                  size="small"
                  text
                  :type="row.status === 'ACTIVE' ? 'danger' : 'primary'"
                  :icon="SwitchButton"
                  :disabled="!canToggle(row)"
                  @click="toggleStatus(row)"
                >
                  {{ row.status === 'ACTIVE' ? '禁用' : '启用' }}
                </el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>暂无租户</p>
            <span class="tiny">开通租户时会自动创建默认项目、默认空间和租户管理员</span>
          </div>
        </template>
      </el-table>
    </section>

    <el-dialog v-model="createDialog.visible" title="开通租户" width="620px">
      <p class="dialog-hint">
        租户代码创建后不可修改，同时作为数据隔离键；初始管理员只能登录该租户。
      </p>
      <div class="form-grid">
        <label class="field">
          <span class="field-label">租户代码</span>
          <el-input
            v-model="createDialog.code"
            maxlength="32"
            placeholder="例如 acme"
            autocomplete="off"
          />
        </label>
        <label class="field">
          <span class="field-label">租户名称</span>
          <el-input v-model="createDialog.name" maxlength="64" placeholder="例如 Acme 物联" />
        </label>
        <label class="field">
          <span class="field-label">套餐</span>
          <el-select v-model="createDialog.plan">
            <el-option label="标准版" value="STANDARD" />
            <el-option label="专业版" value="PRO" />
            <el-option label="企业版" value="ENTERPRISE" />
            <el-option label="试用版" value="TRIAL" />
          </el-select>
        </label>
        <label class="field">
          <span class="field-label">管理员昵称</span>
          <el-input v-model="createDialog.adminNickname" maxlength="64" />
        </label>
        <label class="field">
          <span class="field-label">初始管理员用户名</span>
          <el-input v-model="createDialog.adminUsername" maxlength="32" autocomplete="off" />
        </label>
        <label class="field">
          <span class="field-label">初始管理员密码</span>
          <el-input
            v-model="createDialog.adminPassword"
            type="password"
            maxlength="64"
            show-password
            autocomplete="new-password"
            placeholder="至少 8 位"
          />
        </label>
        <label class="field field-wide">
          <span class="field-label">描述</span>
          <el-input
            v-model="createDialog.description"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
          />
        </label>
      </div>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.loading" @click="submitCreate">
          开通
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editDialog.visible" title="编辑租户" width="520px">
      <div class="form-grid">
        <label class="field">
          <span class="field-label">租户代码</span>
          <el-input :model-value="editDialog.code" disabled />
        </label>
        <label class="field">
          <span class="field-label">租户名称</span>
          <el-input v-model="editDialog.name" maxlength="64" />
        </label>
        <label class="field">
          <span class="field-label">套餐</span>
          <el-select v-model="editDialog.plan">
            <el-option label="标准版" value="STANDARD" />
            <el-option label="专业版" value="PRO" />
            <el-option label="企业版" value="ENTERPRISE" />
            <el-option label="试用版" value="TRIAL" />
          </el-select>
        </label>
        <label class="field">
          <span class="field-label">状态</span>
          <el-select v-model="editDialog.status" :disabled="!canToggle({ id: editDialog.code } as Tenant)">
            <el-option label="正常" value="ACTIVE" />
            <el-option label="禁用" value="DISABLED" />
          </el-select>
        </label>
        <label class="field">
          <span class="field-label">描述</span>
          <el-input
            v-model="editDialog.description"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
          />
        </label>
      </div>
      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="editDialog.loading" @click="submitEdit">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.summary-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 17px 19px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-card);
}

.summary-label {
  font-size: 12px;
  color: var(--text-3);
}

.summary-card strong {
  font-size: 27px;
  font-weight: 650;
  letter-spacing: -0.03em;
  line-height: 1.1;
}

.table-card {
  padding: 8px 20px 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 0 12px;
}

.search {
  max-width: 300px;
}

.status-filter {
  width: 130px;
}

.tenant-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.tenant-name {
  font-weight: 550;
  letter-spacing: -0.01em;
}

.counts {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.count-chip {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 9px;
  border-radius: 7px;
  background: rgba(0, 0, 0, 0.035);
  color: var(--text-2);
  font-size: 11.5px;
}

.dialog-hint {
  margin-bottom: 16px;
  padding: 11px 13px;
  border-radius: 11px;
  background: rgba(0, 113, 227, 0.06);
  color: var(--text-2);
  font-size: 12px;
  line-height: 1.65;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 15px 16px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.field-wide {
  grid-column: 1 / -1;
}

.field-label {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text-2);
}

.field :deep(.el-select) {
  width: 100%;
}

@media (max-width: 980px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
  .summary-grid {
    grid-template-columns: 1fr;
  }
  .toolbar {
    flex-wrap: wrap;
  }
  .search {
    max-width: none;
    flex: 1 1 200px;
  }
}
</style>
