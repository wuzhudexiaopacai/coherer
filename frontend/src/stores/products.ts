import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '../api'
import type { Product } from '../api/types'

export const useProductStore = defineStore('products', () => {
  const products = ref<Product[]>([])
  const loading = ref(false)
  let loaded = false

  async function ensureLoaded(force = false) {
    if (loaded && !force) return
    loading.value = true
    try {
      const page = await api.listProducts({ current: 1, size: 200 })
      products.value = page.records
      loaded = true
    } finally {
      loading.value = false
    }
  }

  function nameOf(productId?: string): string {
    if (!productId) return '—'
    return products.value.find((item) => item.id === productId)?.name ?? `#${productId}`
  }

  /** 租户切换时清空产品缓存。 */
  function reset() {
    products.value = []
    loaded = false
  }

  return { products, loading, ensureLoaded, nameOf, reset }
})
