import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '../layout/AppLayout.vue'
import { getAccessToken } from '../auth/token'

const LoginView = () => import('../views/LoginView.vue')
const DashboardView = () => import('../views/DashboardView.vue')
const DevicesView = () => import('../views/DevicesView.vue')
const DeviceDetailView = () => import('../views/DeviceDetailView.vue')
const ProductsView = () => import('../views/ProductsView.vue')
const AlarmsView = () => import('../views/AlarmsView.vue')
const SpacesView = () => import('../views/SpacesView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { public: true }
    },
    {
      path: '/',
      component: AppLayout,
      children: [
        {
          path: '',
          name: 'dashboard',
          component: DashboardView,
          meta: { title: '运行总览', subtitle: '设备接入 · 数据流转 · 平台状态' }
        },
        {
          path: 'devices',
          name: 'devices',
          component: DevicesView,
          meta: { title: '设备管理', subtitle: '设备注册 · 在线状态 · 遥测与影子' }
        },
        {
          path: 'devices/:id',
          name: 'device-detail',
          component: DeviceDetailView,
          meta: { title: '设备详情', subtitle: '实时监控 · 设备影子 · 历史数据' }
        },
        {
          path: 'products',
          name: 'products',
          component: ProductsView,
          meta: { title: '产品与物模型', subtitle: '产品定义 · 属性 / 事件 / 服务' }
        },
        {
          path: 'alarms',
          name: 'alarms',
          component: AlarmsView,
          meta: { title: '告警中心', subtitle: '告警记录 · 规则配置 · 确认与关闭' }
        },
        {
          path: 'spaces',
          name: 'spaces',
          component: SpacesView,
          meta: { title: '空间管理', subtitle: '项目 / 区域 / 楼栋 / 楼层统一空间树' }
        }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const token = getAccessToken()
  if (to.meta.public) {
    if (token && to.path === '/login') return '/'
    return true
  }
  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
