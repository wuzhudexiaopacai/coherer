<script setup lang="ts">
import { computed, onMounted, reactive } from 'vue'
import { Delete, OfficeBuilding, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import type { SpaceNode } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useProjectStore } from '../stores/projects'
import { useSpaceStore } from '../stores/spaces'

const spaceStore = useSpaceStore()
const projectStore = useProjectStore()
const auth = useAuthStore()

const typeLabels: Record<string, string> = {
  PROJECT: '根空间',
  AREA: '区域',
  BUILDING: '楼栋',
  FLOOR: '楼层',
  ROOM: '房间',
  GENERIC: '空间'
}

const dialog = reactive({
  visible: false,
  loading: false,
  parentKey: '__root__',
  name: '',
  code: '',
  type: 'GENERIC',
  sort: 0,
  description: ''
})

const parentOptions = computed(() => [
  { id: '__root__', label: '根空间（无父级）', depth: 0 },
  ...spaceStore.flat.map((item) => ({ id: item.id, label: item.name, depth: item.depth }))
])

function openCreate(parent?: SpaceNode) {
  dialog.parentKey = parent?.id ?? '__root__'
  dialog.name = ''
  dialog.code = ''
  dialog.type = parent ? 'AREA' : 'GENERIC'
  dialog.sort = 0
  dialog.description = ''
  dialog.visible = true
}

async function submit() {
  if (!dialog.name.trim()) {
    ElMessage.warning('请输入空间名称')
    return
  }
  dialog.loading = true
  try {
    await api.createSpace({
      projectId: projectStore.currentProjectId ?? undefined,
      parentId: dialog.parentKey === '__root__' ? undefined : dialog.parentKey,
      name: dialog.name.trim(),
      code: dialog.code.trim() || undefined,
      type: dialog.type,
      sort: dialog.sort,
      description: dialog.description.trim() || undefined
    })
    ElMessage.success('空间已创建')
    dialog.visible = false
    await spaceStore.ensureLoaded(true)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    dialog.loading = false
  }
}

async function remove(node: SpaceNode) {
  try {
    await ElMessageBox.confirm(`确定删除空间「${node.name}」吗？`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await api.deleteSpace(node.id)
    if (spaceStore.currentSpaceId === node.id) {
      spaceStore.setCurrent(null)
    }
    ElMessage.success('空间已删除')
    await spaceStore.ensureLoaded(true)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

onMounted(() => spaceStore.ensureLoaded())
</script>

<template>
  <div class="spaces">
    <div class="page-head">
      <div>
        <h2 class="page-title">空间管理 · {{ projectStore.nameOf(projectStore.currentProjectId) }}</h2>
        <p class="page-sub">当前项目下的区域 / 楼栋 / 楼层 / 房间空间树；设备归属空间</p>
      </div>
      <el-button
        v-if="auth.canWrite"
        type="primary"
        :icon="Plus"
        :disabled="!projectStore.currentProjectId"
        @click="openCreate()"
      >
        新建根空间
      </el-button>
    </div>

    <section class="card tree-card" v-loading="spaceStore.loading">
      <el-tree
        v-if="spaceStore.tree.length > 0"
        :data="spaceStore.tree"
        node-key="id"
        default-expand-all
        :expand-on-click-node="false"
        :props="{ label: 'name', children: 'children' }"
      >
        <template #default="{ data }">
          <div class="tree-node">
            <span class="node-icon"><el-icon><OfficeBuilding /></el-icon></span>
            <span class="node-name">{{ data.name }}</span>
            <span class="chip type-chip">{{ typeLabels[data.type] ?? data.type }}</span>
            <span v-if="data.code" class="mono node-code">{{ data.code }}</span>
            <span v-if="spaceStore.currentSpaceId === data.id" class="chip accent">当前空间</span>
            <span v-if="auth.canWrite" class="node-actions">
              <el-button text size="small" :icon="Plus" @click.stop="openCreate(data)">
                子空间
              </el-button>
              <el-button
                text
                size="small"
                type="danger"
                :icon="Delete"
                @click.stop="remove(data)"
              >
                删除
              </el-button>
            </span>
          </div>
        </template>
      </el-tree>
      <div v-else class="empty-state">
        <p>暂无空间</p>
        <span class="tiny">新建根空间后，可在其下继续创建子空间</span>
      </div>
    </section>

    <p class="tree-note">
      顶栏空间切换器会联动设备列表、运行总览与告警中心的过滤范围；空间下存在子空间或设备时不允许删除。
    </p>

    <el-dialog v-model="dialog.visible" title="新建空间" width="500px">
      <div class="form-grid">
        <label class="field">
          <span class="field-label">父空间</span>
          <el-select v-model="dialog.parentKey" placeholder="选择父空间">
            <el-option
              v-for="option in parentOptions"
              :key="option.id"
              :label="'　'.repeat(option.depth) + option.label"
              :value="option.id"
            />
          </el-select>
        </label>
        <div class="form-row">
          <label class="field">
            <span class="field-label">空间名称</span>
            <el-input v-model="dialog.name" placeholder="例如：1 号楼 / 3 层机房" maxlength="64" />
          </label>
          <label class="field narrow">
            <span class="field-label">类型</span>
            <el-select v-model="dialog.type">
              <el-option label="区域" value="AREA" />
              <el-option label="楼栋" value="BUILDING" />
              <el-option label="楼层" value="FLOOR" />
              <el-option label="房间" value="ROOM" />
              <el-option label="通用" value="GENERIC" />
            </el-select>
          </label>
        </div>
        <div class="form-row">
          <label class="field">
            <span class="field-label">空间编码（可选）</span>
            <el-input v-model="dialog.code" placeholder="例如：BLDG-01" maxlength="64" />
          </label>
          <label class="field narrow">
            <span class="field-label">排序</span>
            <el-input-number v-model="dialog.sort" :min="0" :max="9999" />
          </label>
        </div>
        <label class="field">
          <span class="field-label">描述（可选）</span>
          <el-input v-model="dialog.description" type="textarea" :rows="2" maxlength="255" />
        </label>
      </div>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.loading" @click="submit">创建空间</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tree-card {
  padding: 14px 18px;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 4px 2px;
}

.node-icon {
  color: var(--accent);
  display: inline-flex;
  font-size: 16px;
}

.node-name {
  font-size: 13.5px;
  font-weight: 550;
}

.type-chip {
  height: 22px;
  font-size: 11px;
  background: rgba(0, 113, 227, 0.08);
  color: var(--accent);
}

.node-code {
  color: var(--text-3);
  font-size: 11.5px;
}

.node-actions {
  margin-left: auto;
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity var(--transition);
}

.tree-node:hover .node-actions,
.tree-node:focus-within .node-actions {
  opacity: 1;
}

.node-actions :deep(.el-button) {
  margin-left: 0;
}

.tree-note {
  margin-top: 12px;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.7;
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

.field.narrow {
  flex: 0 0 140px;
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

@media (max-width: 900px) {
  .form-row {
    flex-direction: column;
  }
  .field.narrow {
    flex: 1;
  }
  .node-actions {
    opacity: 1;
  }
}
</style>
