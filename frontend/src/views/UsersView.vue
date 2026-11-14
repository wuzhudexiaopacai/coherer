<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Delete, Edit, Key, Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import type { UserItem } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useSpaceStore } from '../stores/spaces'
import { fmtDateTime } from '../utils/format'

const auth = useAuthStore()
const spaceStore = useSpaceStore()

const roleOptions = [
  { label: '管理员', value: 'ADMIN' },
  { label: '运维', value: 'OPERATOR' },
  { label: '只读', value: 'VIEWER' }
]
const roleLabels: Record<string, string> = {
  ADMIN: '管理员',
  OPERATOR: '运维',
  VIEWER: '只读'
}

const filters = reactive({ keyword: '' })
const state = reactive({
  current: 1,
  size: 10,
  total: 0,
  records: [] as UserItem[],
  loading: false
})

const dialog = reactive({
  visible: false,
  editing: false,
  id: '',
  loading: false,
  username: '',
  nickname: '',
  password: '',
  role: 'VIEWER',
  spaceId: '' as string,
  status: 'ACTIVE'
})

const passwordDialog = reactive({
  visible: false,
  id: '',
  username: '',
  password: '',
  loading: false
})

async function load() {
  state.loading = true
  try {
    const page = await api.listUsers({
      current: state.current,
      size: state.size,
      keyword: filters.keyword || undefined
    })
    state.records = page.records
    state.total = page.total
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    state.loading = false
  }
}

function openCreate() {
  dialog.editing = false
  dialog.id = ''
  dialog.username = ''
  dialog.nickname = ''
  dialog.password = ''
  dialog.role = 'VIEWER'
  dialog.spaceId = ''
  dialog.status = 'ACTIVE'
  dialog.visible = true
}

function openEdit(row: UserItem) {
  dialog.editing = true
  dialog.id = row.id
  dialog.username = row.username
  dialog.nickname = row.nickname
  dialog.password = ''
  dialog.role = row.role
  dialog.spaceId = row.spaceId ?? ''
  dialog.status = row.status
  dialog.visible = true
}

async function submit() {
  if (!dialog.nickname.trim()) {
    ElMessage.warning('请输入昵称')
    return
  }
  if (!dialog.editing && !dialog.username.trim()) {
    ElMessage.warning('请输入用户名')
    return
  }
  if (!dialog.editing && !dialog.password) {
    ElMessage.warning('请输入初始密码')
    return
  }
  dialog.loading = true
  try {
    if (dialog.editing) {
      await api.updateUser(dialog.id, {
        nickname: dialog.nickname.trim(),
        role: dialog.role,
        spaceId: dialog.spaceId || 0,
        status: dialog.status
      })
      ElMessage.success('用户已更新')
    } else {
      await api.createUser({
        username: dialog.username.trim(),
        password: dialog.password,
        nickname: dialog.nickname.trim(),
        role: dialog.role,
        spaceId: dialog.spaceId || null
      })
      ElMessage.success('用户已创建')
    }
    dialog.visible = false
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    dialog.loading = false
  }
}

function openResetPassword(row: UserItem) {
  passwordDialog.id = row.id
  passwordDialog.username = row.username
  passwordDialog.password = ''
  passwordDialog.visible = true
}

async function submitPassword() {
  if (!passwordDialog.password) {
    ElMessage.warning('请输入新密码')
    return
  }
  passwordDialog.loading = true
  try {
    await api.resetUserPassword(passwordDialog.id, passwordDialog.password)
    ElMessage.success('密码已重置')
    passwordDialog.visible = false
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    passwordDialog.loading = false
  }
}

async function remove(row: UserItem) {
  try {
    await ElMessageBox.confirm(
      `删除后「${row.nickname}」将无法登录，确认删除？`,
      '删除用户',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await api.deleteUser(row.id)
    ElMessage.success('用户已删除')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

onMounted(() => {
  void spaceStore.ensureLoaded()
  void load()
})
</script>

<template>
  <div class="users">
    <div class="page-head">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-sub">账号、角色与数据范围；ADMIN 仅能由管理员管理</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
    </div>

    <section class="card">
      <div class="toolbar">
        <el-input
          v-model="filters.keyword"
          class="search"
          placeholder="搜索用户名 / 昵称"
          :prefix-icon="Search"
          clearable
          @keyup.enter="((state.current = 1), load())"
          @clear="((state.current = 1), load())"
        />
        <el-button @click="((state.current = 1), load())">查询</el-button>
      </div>

      <el-table v-loading="state.loading" :data="state.records" size="large">
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }">
            <div class="user-cell">
              <span class="user-name">{{ row.nickname }}</span>
              <span class="mono user-id">{{ row.username }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <span class="chip" :class="{ accent: row.role === 'ADMIN' }">
              {{ roleLabels[row.role] ?? row.role }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="数据范围" min-width="150">
          <template #default="{ row }">
            <span>{{ row.spaceId ? spaceStore.nameOf(row.spaceId) : '全平台' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="chip" :class="{ danger: row.status === 'DISABLED' }">
              {{ row.status === 'ACTIVE' ? '正常' : '已禁用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">
            <span class="tiny">{{ fmtDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" align="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button size="small" text :icon="Edit" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" text :icon="Key" @click="openResetPassword(row)">
                重置密码
              </el-button>
              <el-button
                size="small"
                text
                type="danger"
                :icon="Delete"
                :disabled="row.username === auth.user?.username"
                @click="remove(row)"
              >
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>
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

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.editing ? '编辑用户' : '新建用户'"
      width="520px"
    >
      <div class="form-grid">
        <label class="field">
          <span class="field-label">用户名</span>
          <el-input
            v-model="dialog.username"
            :disabled="dialog.editing"
            placeholder="字母开头，3-32 位字母/数字/下划线"
            maxlength="32"
          />
        </label>
        <label v-if="!dialog.editing" class="field">
          <span class="field-label">初始密码</span>
          <el-input
            v-model="dialog.password"
            type="password"
            show-password
            placeholder="8-64 位，需包含字母和数字"
          />
        </label>
        <label class="field">
          <span class="field-label">昵称</span>
          <el-input v-model="dialog.nickname" placeholder="显示名称" maxlength="64" />
        </label>
        <div class="form-row">
          <label class="field">
            <span class="field-label">角色</span>
            <el-select v-model="dialog.role">
              <el-option
                v-for="option in roleOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </label>
          <label class="field">
            <span class="field-label">数据范围</span>
            <el-select v-model="dialog.spaceId" placeholder="全平台">
              <el-option label="全平台" value="" />
              <el-option
                v-for="space in spaceStore.flat"
                :key="space.id"
                :label="'　'.repeat(space.depth) + space.name"
                :value="space.id"
              />
            </el-select>
          </label>
        </div>
        <label v-if="dialog.editing" class="field">
          <span class="field-label">状态</span>
          <el-select v-model="dialog.status">
            <el-option label="正常" value="ACTIVE" />
            <el-option label="已禁用" value="DISABLED" />
          </el-select>
        </label>
      </div>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.loading" @click="submit">
          {{ dialog.editing ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="passwordDialog.visible" title="重置密码" width="460px">
      <p class="dialog-hint">为「{{ passwordDialog.username }}」设置新密码，旧密码立即失效。</p>
      <el-input
        v-model="passwordDialog.password"
        type="password"
        show-password
        placeholder="8-64 位，需包含字母和数字"
      />
      <template #footer>
        <el-button @click="passwordDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="passwordDialog.loading" @click="submitPassword">
          重置
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
  margin-bottom: 8px;
}

.search {
  width: 280px;
}

.user-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.user-name {
  font-weight: 600;
}

.user-id {
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

.dialog-hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--text-2);
}

@media (max-width: 900px) {
  .form-row {
    flex-direction: column;
  }
}
</style>
