<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: 'admin', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username.trim() || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    router.push(redirect)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="white" stroke-width="1.9" stroke-linecap="round">
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

      <h1>登录控制台</h1>
      <p class="sub">设备接入 · 数据流转 · 告警与空间管理</p>

      <form class="login-form" @submit.prevent="submit">
        <label class="field">
          <span class="field-label">用户名</span>
          <el-input v-model="form.username" placeholder="admin" :prefix-icon="User" autocomplete="username" />
        </label>
        <label class="field">
          <span class="field-label">密码</span>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            show-password
            autocomplete="current-password"
          />
        </label>
        <el-button type="primary" native-type="submit" :loading="loading" class="login-button">
          登录
        </el-button>
      </form>

      <p class="hint">开发环境默认账号 admin / Admin@123（由 backend/.env 的 COHERER_ADMIN_PASSWORD 初始化）</p>
    </div>
    <p class="copyright">Coherer · 企业级物联网设备管理平台</p>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 22px;
  padding: 32px;
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 34px 34px 28px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(24px) saturate(1.4);
  -webkit-backdrop-filter: blur(24px) saturate(1.4);
  border: 1px solid rgba(255, 255, 255, 0.75);
  box-shadow: var(--shadow-lift);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 28px;
}

.brand-mark {
  width: 42px;
  height: 42px;
  border-radius: 13px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #3aa0ff 0%, #0071e3 100%);
  box-shadow: 0 8px 20px rgba(0, 113, 227, 0.4);
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.brand-text strong {
  font-size: 19px;
  font-weight: 650;
  letter-spacing: -0.02em;
}

.brand-text span {
  font-size: 10.5px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--text-3);
}

h1 {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
}

.sub {
  margin-top: 6px;
  margin-bottom: 24px;
  font-size: 13px;
  color: var(--text-2);
}

.login-form {
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

.login-button {
  margin-top: 6px;
  height: 42px;
  font-size: 14px;
}

.hint {
  margin-top: 20px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--text-3);
}

.copyright {
  font-size: 11.5px;
  letter-spacing: 0.04em;
  color: var(--text-3);
}
</style>
