<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { Delete, Edit, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import type { Project } from '../api/types'
import StatusBadge from '../components/StatusBadge.vue'
import { useProjectStore } from '../stores/projects'
import { useSpaceStore } from '../stores/spaces'
import { fmtDateTime } from '../utils/format'

const router = useRouter()
const projectStore = useProjectStore()
const spaceStore = useSpaceStore()

const dialog = reactive({
  visible: false,
  loading: false,
  editingId: null as string | null,
  name: '',
  code: '',
  description: '',
  status: 'ACTIVE'
})

function openCreate() {
  dialog.editingId = null
  dialog.name = ''
  dialog.code = ''
  dialog.description = ''
  dialog.status = 'ACTIVE'
  dialog.visible = true
}

function openEdit(project: Project) {
  dialog.editingId = project.id
  dialog.name = project.name
  dialog.code = project.code
  dialog.description = project.description ?? ''
  dialog.status = project.status
  dialog.visible = true
}

async function submit() {
  if (!dialog.name.trim()) {
    ElMessage.warning('请输入项目名称')
    return
  }
  dialog.loading = true
  try {
    if (dialog.editingId) {
      await api.updateProject(dialog.editingId, {
        name: dialog.name.trim(),
        code: dialog.code.trim() || undefined,
        description: dialog.description.trim() || undefined,
        status: dialog.status
      })
      ElMessage.success('项目已更新')
    } else {
      const created = await api.createProject({
        name: dialog.name.trim(),
        code: dialog.code.trim() || undefined,
        description: dialog.description.trim() || undefined,
        status: dialog.status
      })
      ElMessage.success('项目已创建')
      projectStore.setCurrent(created.id)
      await spaceStore.ensureLoaded(true)
    }
    dialog.visible = false
    await projectStore.ensureLoaded(true)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    dialog.loading = false
  }
}

async function remove(project: Project) {
  try {
    await ElMessageBox.confirm(
      `删除项目「${project.name}」前必须先删除其下所有空间。确认删除？`,
      '删除项目',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await api.deleteProject(project.id)
    ElMessage.success('项目已删除')
    await projectStore.ensureLoaded(true)
    await spaceStore.ensureLoaded(true)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function enter(project: Project) {
  projectStore.setCurrent(project.id)
  await spaceStore.ensureLoaded(true)
  router.push('/')
}

onMounted(() => projectStore.ensureLoaded(true))
</script>

<template>
  <div class="projects">
    <div class="page-head">
      <div>
        <h2 class="page-title">项目管理</h2>
        <p class="page-sub">项目是最外层业务边界；空间、主机与设备通过空间归属项目</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建项目</el-button>
    </div>

    <section class="card table-card">
      <el-table v-loading="projectStore.loading" :data="projectStore.projects" size="large">
        <el-table-column label="项目" min-width="220">
          <template #default="{ row }">
            <div class="project-cell">
              <span class="project-name">{{ row.name }}</span>
              <span class="mono tiny">{{ row.code }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusBadge :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="资源" min-width="220">
          <template #default="{ row }">
            <div class="counts">
              <span class="count-chip">空间 {{ row.spaceCount }}</span>
              <span class="count-chip">主机 {{ row.hostCount }}</span>
              <span class="count-chip">设备 {{ row.deviceCount }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="描述" min-width="180">
          <template #default="{ row }">
            <span class="tiny">{{ row.description || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">
            <span class="tiny">{{ fmtDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" align="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="enter(row)">进入</el-button>
            <el-button size="small" text :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" text type="danger" :icon="Delete" @click="remove(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>暂无项目</p>
            <span class="tiny">创建项目后，在空间管理中建立该项目的空间树</span>
          </div>
        </template>
      </el-table>
    </section>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.editingId ? '编辑项目' : '新建项目'"
      width="520px"
    >
      <div class="form-grid">
        <label class="field">
          <span class="field-label">项目名称</span>
          <el-input v-model="dialog.name" maxlength="64" show-word-limit />
        </label>
        <label class="field">
          <span class="field-label">项目编码</span>
          <el-input v-model="dialog.code" maxlength="64" placeholder="留空自动生成" />
        </label>
        <label class="field">
          <span class="field-label">状态</span>
          <el-select v-model="dialog.status">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="禁用" value="DISABLED" />
          </el-select>
        </label>
        <label class="field">
          <span class="field-label">描述</span>
          <el-input
            v-model="dialog.description"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
          />
        </label>
      </div>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.loading" @click="submit">
          {{ dialog.editingId ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-card {
  padding: 8px 20px 16px;
}

.project-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.project-name {
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

.field :deep(.el-select) {
  width: 100%;
}
</style>
