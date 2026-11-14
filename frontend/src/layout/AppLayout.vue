<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, Box, Document, Fold, Monitor, Odometer, OfficeBuilding, User } from '@element-plus/icons-vue'
import { mqttConnected, subscribeRealtime } from '../realtime/mqtt'
import { useAuthStore } from '../stores/auth'
import { useSpaceStore } from '../stores/spaces'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const spaceStore = useSpaceStore()
const menuOpen = ref(false)
let unsubscribeRealtime: (() => void) | undefined

onMounted(async () => {
  // 服务端数据范围可能已调整：同步最新用户信息后再决定订阅与空间树
  try {
    await auth.refreshUser()
  } catch {
    // 刷新失败不阻塞页面，后续接口会按 401 统一跳转登录
  }
  // 全平台角色才做全局通配符订阅；空间受限用户的通配符会被 ACL 拒绝
  if (auth.user?.spaceId == null) {
    unsubscribeRealtime = subscribeRealtime('default/+/thing/property/report', () => {})
  }
  void spaceStore.ensureLoaded(true)
})

onBeforeUnmount(() => unsubscribeRealtime?.())

async function handleUserCommand(command: string) {
  if (command === 'logout') {
    await auth.logout()
    router.push('/login')
  }
}

const spaceValue = computed({
  get: () => spaceStore.currentSpaceId ?? '__all__',
  set: (value: string) => spaceStore.setCurrent(value === '__all__' ? null : value)
})

const spaceOptions = computed(() => {
  const allLabel = auth.user?.spaceId == null ? '全部空间' : '全部可见空间'
  return [
    { id: '__all__', label: allLabel, depth: 0 },
    ...spaceStore.flat.map((item) => ({ id: item.id, label: item.name, depth: item.depth }))
  ]
})

const title = computed(() => (route.meta.title as string) ?? 'Coherer')
const subtitle = computed(() => (route.meta.subtitle as string) ?? '')

watch(
  () => route.fullPath,
  () => {
    menuOpen.value = false
  }
)
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ open: menuOpen }">
      <div class="brand">
        <div class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="white" stroke-width="1.9" stroke-linecap="round">
            <circle cx="6" cy="18" r="1.7" fill="white" stroke="none" />
            <path d="M6 12a6 6 0 0 1 6 6" />
            <path d="M6 6a12 12 0 0 1 12 12" />
          </svg>
        </div>
        <div class="brand-text">
          <strong>Coherer</strong>
          <span>IoT Platform</span>
        </div>
      </div>

      <nav class="nav" aria-label="主导航">
        <p class="nav-label">Platform</p>
        <router-link to="/" class="nav-item" active-class="active" exact-active-class="active">
          <el-icon><Odometer /></el-icon>
          <span>运行总览</span>
        </router-link>
        <router-link to="/devices" class="nav-item" active-class="active">
          <el-icon><Monitor /></el-icon>
          <span>设备管理</span>
        </router-link>
        <router-link to="/products" class="nav-item" active-class="active">
          <el-icon><Box /></el-icon>
          <span>产品与物模型</span>
        </router-link>
        <router-link to="/alarms" class="nav-item" active-class="active">
          <el-icon><Bell /></el-icon>
          <span>告警中心</span>
        </router-link>
        <router-link to="/spaces" class="nav-item" active-class="active">
          <el-icon><OfficeBuilding /></el-icon>
          <span>空间管理</span>
        </router-link>
        <router-link
          v-if="auth.user?.role === 'ADMIN'"
          to="/users"
          class="nav-item"
          active-class="active"
        >
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </router-link>
        <router-link
          v-if="auth.user?.role === 'ADMIN'"
          to="/audit"
          class="nav-item"
          active-class="active"
        >
          <el-icon><Document /></el-icon>
          <span>审计日志</span>
        </router-link>
      </nav>

      <div class="sidebar-footer">
        <div class="env-card">
          <div class="env-row"><span class="env-dot" />MQTT Broker · EMQX</div>
          <div class="env-row">时序数据库 · TDengine</div>
          <div class="env-row">业务库 · PostgreSQL</div>
        </div>
        <p class="version">M2 · v0.2.0</p>
      </div>
    </aside>

    <div v-if="menuOpen" class="sidebar-scrim" @click="menuOpen = false" />

    <div class="main">
      <header class="topbar">
        <div class="topbar-left">
          <button class="menu-btn" aria-label="打开菜单" @click="menuOpen = !menuOpen">
            <el-icon><Fold /></el-icon>
          </button>
          <nav class="crumbs" aria-label="面包屑">
            <span class="crumb-root">Coherer</span>
            <span class="crumb-sep">/</span>
            <span class="crumb-current">{{ title }}</span>
          </nav>
        </div>
        <div class="topbar-right">
          <el-select v-model="spaceValue" class="space-select" placeholder="全部空间" aria-label="切换空间">
            <el-option
              v-for="item in spaceOptions"
              :key="item.id"
              :label="'　'.repeat(item.depth) + item.label"
              :value="item.id"
            />
          </el-select>
          <span class="live-pill" :class="{ offline: !mqttConnected }">
            <span class="pulse" />
            {{ mqttConnected ? '实时数据' : '轮询模式' }}
          </span>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <div class="user-chip">
              <div class="avatar" aria-hidden="true">
                {{ auth.user?.nickname?.slice(0, 1) ?? 'U' }}
              </div>
              <div class="user-meta">
                <strong>{{ auth.user?.nickname ?? '用户' }}</strong>
                <span>{{ auth.roleLabel }}</span>
              </div>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ auth.user?.username }}</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade-up" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}

.sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  width: var(--sidebar-w);
  padding: 22px 16px 18px;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #101828 0%, #0b1220 100%);
  color: #fff;
  z-index: 30;
  transition: transform var(--transition);
}

.sidebar::after {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(320px 200px at 20% 0%, rgba(41, 151, 255, 0.18), transparent 70%),
    radial-gradient(260px 220px at 100% 100%, rgba(124, 108, 246, 0.12), transparent 70%);
}

.brand {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 10px 28px;
}

.brand-mark {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #3aa0ff 0%, #0071e3 100%);
  box-shadow: 0 8px 20px rgba(0, 113, 227, 0.45);
  flex: none;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.brand-text strong {
  font-size: 17px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.brand-text span {
  font-size: 10.5px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.48);
}

.nav {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-label {
  margin: 4px 12px 8px;
  font-size: 10.5px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.34);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 11px;
  color: rgba(255, 255, 255, 0.64);
  font-size: 13.5px;
  font-weight: 500;
  transition: background var(--transition), color var(--transition);
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

.nav-item.active {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.07);
}

.nav-item :deep(.el-icon) {
  font-size: 18px;
}

.sidebar-footer {
  position: relative;
  z-index: 1;
  margin-top: auto;
  padding: 0 4px;
}

.env-card {
  border-radius: 14px;
  padding: 12px 13px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.07);
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.52);
  line-height: 2.1;
}

.env-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.env-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #35d07f;
  box-shadow: 0 0 0 3px rgba(53, 208, 127, 0.16);
}

.version {
  margin-top: 12px;
  text-align: center;
  font-size: 10.5px;
  letter-spacing: 0.08em;
  color: rgba(255, 255, 255, 0.3);
}

.sidebar-scrim {
  display: none;
}

.main {
  margin-left: var(--sidebar-w);
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 32px;
  background: rgba(245, 245, 247, 0.72);
  backdrop-filter: blur(22px) saturate(1.5);
  -webkit-backdrop-filter: blur(22px) saturate(1.5);
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.topbar-title {
  display: none;
}

.crumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--text-3);
}

.crumb-root {
  font-weight: 500;
}

.crumb-sep {
  color: rgba(0, 0, 0, 0.22);
}

.crumb-current {
  color: var(--text);
  font-weight: 550;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.space-select {
  width: 176px;
}

.space-select :deep(.el-select__wrapper) {
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 0 0 1px var(--border) inset;
  border-radius: 10px;
  min-height: 34px;
}

.live-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 30px;
  padding: 0 13px;
  border-radius: 999px;
  background: rgba(47, 180, 87, 0.1);
  color: #1f9b4b;
  font-size: 12px;
  font-weight: 500;
}

.pulse {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--success);
  box-shadow: 0 0 0 0 rgba(47, 180, 87, 0.5);
  animation: pulse 2.2s infinite;
}

.live-pill.offline {
  background: rgba(0, 0, 0, 0.05);
  color: var(--text-3);
}

.live-pill.offline .pulse {
  background: var(--text-3);
  box-shadow: none;
  animation: none;
}

@keyframes pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(47, 180, 87, 0.45);
  }
  70% {
    box-shadow: 0 0 0 7px rgba(47, 180, 87, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(47, 180, 87, 0);
  }
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(135deg, #2f80ed, #7c6cf6);
  box-shadow: 0 4px 12px rgba(47, 128, 237, 0.28);
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 9px;
  cursor: pointer;
  padding: 3px 6px 3px 3px;
  border-radius: 999px;
  transition: background var(--transition);
}

.user-chip:hover {
  background: rgba(0, 0, 0, 0.04);
}

.user-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.user-meta strong {
  font-size: 12.5px;
  font-weight: 600;
}

.user-meta span {
  font-size: 10.5px;
  color: var(--text-3);
}

.menu-btn {
  display: none;
  width: 34px;
  height: 34px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.7);
  border-radius: 10px;
  cursor: pointer;
  color: var(--text-2);
  align-items: center;
  justify-content: center;
}

.content {
  width: 100%;
  max-width: 1440px;
  margin: 0 auto;
  padding: 28px 32px 56px;
}

.fade-up-enter-active,
.fade-up-leave-active {
  transition: opacity 180ms ease, transform 180ms ease;
}

.fade-up-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.fade-up-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 1180px) {
  .sidebar {
    width: 76px;
    padding: 22px 12px 16px;
  }
  .brand {
    justify-content: center;
    padding: 4px 0 26px;
  }
  .brand-text,
  .nav-item span,
  .nav-label,
  .sidebar-footer {
    display: none;
  }
  .nav-item {
    justify-content: center;
    padding: 12px;
  }
  .main {
    margin-left: 76px;
  }
}

@media (max-width: 780px) {
  .sidebar {
    width: var(--sidebar-w);
    transform: translateX(-100%);
    border-radius: 0 22px 22px 0;
  }
  .sidebar.open {
    transform: translateX(0);
  }
  .sidebar.open .brand-text,
  .sidebar.open .nav-item span,
  .sidebar.open .nav-label,
  .sidebar.open .sidebar-footer {
    display: flex;
  }
  .sidebar.open .nav-label,
  .sidebar.open .version {
    display: block;
  }
  .sidebar.open .nav-item {
    justify-content: flex-start;
    padding: 10px 12px;
  }
  .main {
    margin-left: 0;
  }
  .menu-btn {
    display: inline-flex;
  }
  .sidebar-scrim {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 25;
    background: rgba(11, 18, 32, 0.42);
    backdrop-filter: blur(2px);
  }
  .topbar {
    padding: 14px 18px;
  }
  .content {
    padding: 20px 18px 44px;
  }
  .live-pill {
    display: none;
  }
  .user-meta {
    display: none;
  }
}
</style>
