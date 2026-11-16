import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '../api'
import type { Project } from '../api/types'

const STORAGE_KEY = 'coherer.projectId'

export const useProjectStore = defineStore('projects', () => {
  const projects = ref<Project[]>([])
  const loading = ref(false)
  const currentProjectId = ref<string | null>(localStorage.getItem(STORAGE_KEY))
  let loaded = false

  const current = computed(
    () => projects.value.find((item) => item.id === currentProjectId.value) ?? null
  )

  async function ensureLoaded(force = false) {
    if (loaded && !force) return
    loading.value = true
    try {
      projects.value = await api.listProjects()
      loaded = true
      if (!currentProjectId.value
          || !projects.value.some((item) => item.id === currentProjectId.value)) {
        setCurrent(projects.value[0]?.id ?? null)
      }
    } finally {
      loading.value = false
    }
  }

  function setCurrent(id: string | null) {
    currentProjectId.value = id
    if (id) {
      localStorage.setItem(STORAGE_KEY, id)
    } else {
      localStorage.removeItem(STORAGE_KEY)
    }
  }

  /** 租户切换时清空租户内项目缓存。 */
  function reset() {
    projects.value = []
    loaded = false
    setCurrent(null)
  }

  function nameOf(id?: string | null): string {
    if (!id) return '未选择项目'
    return projects.value.find((item) => item.id === id)?.name ?? '—'
  }

  return {
    projects,
    loading,
    currentProjectId,
    current,
    ensureLoaded,
    setCurrent,
    reset,
    nameOf
  }
})
