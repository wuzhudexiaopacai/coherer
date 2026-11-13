<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Box, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import type { Product, ThingModel } from '../api/types'
import { useProductStore } from '../stores/products'
import { fmtDateTime } from '../utils/format'

const productStore = useProductStore()
const products = computed(() => productStore.products)

const createDialog = reactive({
  visible: false,
  loading: false,
  name: '',
  category: 'SENSOR',
  nodeType: 'DIRECT'
})

const drawer = reactive({
  visible: false,
  product: null as Product | null,
  models: [] as ThingModel[],
  loading: false
})

const activeGroup = ref<'PROPERTY' | 'EVENT' | 'SERVICE'>('PROPERTY')

const groups = computed(() => ({
  PROPERTY: drawer.models.filter((item) => item.type === 'PROPERTY'),
  EVENT: drawer.models.filter((item) => item.type === 'EVENT'),
  SERVICE: drawer.models.filter((item) => item.type === 'SERVICE')
}))

const activeRows = computed(() => groups.value[activeGroup.value])

function openCreate() {
  createDialog.name = ''
  createDialog.category = 'SENSOR'
  createDialog.nodeType = 'DIRECT'
  createDialog.visible = true
}

async function submitCreate() {
  if (!createDialog.name.trim()) {
    ElMessage.warning('请输入产品名称')
    return
  }
  createDialog.loading = true
  try {
    await api.createProduct({
      name: createDialog.name.trim(),
      category: createDialog.category,
      nodeType: createDialog.nodeType
    })
    ElMessage.success('产品已创建')
    createDialog.visible = false
    await productStore.ensureLoaded(true)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    createDialog.loading = false
  }
}

async function openModel(product: Product) {
  drawer.visible = true
  drawer.product = product
  drawer.models = []
  activeGroup.value = 'PROPERTY'
  drawer.loading = true
  try {
    drawer.models = await api.thingModel(product.id)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    drawer.loading = false
  }
}

function specText(spec: Record<string, unknown>): string {
  const text = JSON.stringify(spec ?? {})
  return text === '{}' ? '—' : text
}

onMounted(() => productStore.ensureLoaded())
</script>

<template>
  <div class="products">
    <div class="page-head">
      <div>
        <h2 class="page-title">产品与物模型</h2>
        <p class="page-sub">定义设备模板与数据契约：属性、事件和服务</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建产品</el-button>
    </div>

    <div v-if="productStore.loading && products.length === 0" class="card loading-card">
      <el-skeleton :rows="4" animated />
    </div>

    <div v-else-if="products.length === 0" class="card empty-card">
      <div class="empty-state">
        <div class="icon"><el-icon><Box /></el-icon></div>
        <p>还没有产品</p>
        <span class="tiny">创建产品后即可定义物模型并注册设备</span>
      </div>
    </div>

    <section v-else class="product-grid">
      <article
        v-for="product in products"
        :key="product.id"
        class="product-card card"
        role="button"
        tabindex="0"
        @click="openModel(product)"
        @keyup.enter="openModel(product)"
      >
        <div class="product-head">
          <div class="product-icon"><el-icon><Box /></el-icon></div>
          <span class="chip" :class="{ accent: product.status === 'ACTIVE' }">
            {{ product.status === 'ACTIVE' ? '已启用' : product.status }}
          </span>
        </div>
        <h3>{{ product.name }}</h3>
        <p class="mono product-key">{{ product.productKey }}</p>
        <div class="product-chips">
          <span class="chip">{{ product.category || 'GENERIC' }}</span>
          <span class="chip">{{ product.nodeType }}</span>
          <span class="chip">{{ product.authType }}</span>
        </div>
        <div class="product-foot">
          <span class="tiny">{{ fmtDateTime(product.createdAt) }}</span>
          <span class="link">查看物模型 →</span>
        </div>
      </article>
    </section>

    <el-dialog v-model="createDialog.visible" title="新建产品" width="460px">
      <div class="form-grid">
        <label class="field">
          <span class="field-label">产品名称</span>
          <el-input v-model="createDialog.name" placeholder="例如：冷链温湿度计" maxlength="64" />
        </label>
        <label class="field">
          <span class="field-label">品类</span>
          <el-input v-model="createDialog.category" placeholder="SENSOR" maxlength="32" />
        </label>
        <label class="field">
          <span class="field-label">节点类型</span>
          <el-select v-model="createDialog.nodeType">
            <el-option label="DIRECT · 直连设备" value="DIRECT" />
            <el-option label="GATEWAY · 网关设备" value="GATEWAY" />
          </el-select>
        </label>
      </div>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.loading" @click="submitCreate">
          创建
        </el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawer.visible" size="620px" :with-header="false">
      <div v-if="drawer.product" class="drawer-body" v-loading="drawer.loading">
        <div class="drawer-head">
          <div class="product-icon large"><el-icon><Box /></el-icon></div>
          <div>
            <h3>{{ drawer.product.name }}</h3>
            <p class="mono tiny">{{ drawer.product.productKey }}</p>
          </div>
        </div>

        <div class="drawer-meta">
          <span class="chip">{{ drawer.product.category || 'GENERIC' }}</span>
          <span class="chip">{{ drawer.product.nodeType }}</span>
          <span class="chip">{{ drawer.product.authType }}</span>
          <span class="chip accent">{{ groups.PROPERTY.length }} 个属性</span>
        </div>

        <div class="segmented drawer-tabs">
          <button :class="{ active: activeGroup === 'PROPERTY' }" @click="activeGroup = 'PROPERTY'">
            属性 {{ groups.PROPERTY.length }}
          </button>
          <button :class="{ active: activeGroup === 'EVENT' }" @click="activeGroup = 'EVENT'">
            事件 {{ groups.EVENT.length }}
          </button>
          <button :class="{ active: activeGroup === 'SERVICE' }" @click="activeGroup = 'SERVICE'">
            服务 {{ groups.SERVICE.length }}
          </button>
        </div>

        <el-table :data="activeRows" size="large">
          <el-table-column label="标识符" min-width="140">
            <template #default="{ row }">
              <span class="mono">{{ row.identifier }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="110" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <span class="tiny">{{ row.dataType || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="读写" width="70">
            <template #default="{ row }">
              <span class="tiny">{{ row.accessMode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="扩展定义" min-width="160">
            <template #default="{ row }">
              <span class="mono spec" :title="specText(row.spec)">{{ specText(row.spec) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-state">
              <p>该分组暂无定义</p>
              <span class="tiny">物模型编辑能力将在 M3 开放</span>
            </div>
          </template>
        </el-table>

        <p class="drawer-note">
          物模型是遥测校验与设备影子的数据契约；当前版本支持查看，编辑能力将在 M3 提供。
        </p>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.loading-card,
.empty-card {
  padding: 32px;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.product-card {
  padding: 20px 22px 16px;
  cursor: pointer;
  transition: transform var(--transition), box-shadow var(--transition);
  display: flex;
  flex-direction: column;
}

.product-card:hover,
.product-card:focus-visible {
  transform: translateY(-2px);
  box-shadow: var(--shadow-lift);
  outline: none;
}

.product-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.product-icon {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: rgba(0, 113, 227, 0.1);
  color: var(--accent);
  font-size: 18px;
}

.product-icon.large {
  width: 44px;
  height: 44px;
  border-radius: 14px;
}

.product-card h3 {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.015em;
}

.product-key {
  margin-top: 4px;
  color: var(--text-3);
  font-size: 11.5px;
}

.product-chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin: 14px 0 16px;
}

.product-foot {
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.link {
  color: var(--accent);
  font-size: 12.5px;
  font-weight: 500;
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

.drawer-body {
  padding: 8px 6px 24px;
}

.drawer-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 14px;
}

.drawer-head h3 {
  font-size: 19px;
  font-weight: 650;
  letter-spacing: -0.02em;
}

.drawer-head .tiny {
  margin-top: 3px;
}

.drawer-meta {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.drawer-tabs {
  margin-bottom: 14px;
}

.spec {
  color: var(--text-3);
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.drawer-note {
  margin-top: 14px;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.7;
}

@media (max-width: 1100px) {
  .product-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .product-grid {
    grid-template-columns: 1fr;
  }
}
</style>
