<template>
  <div class="dashboard">
    <header-bar show-menu show-logo title="设置" title-icon="system-options" />

    <div id="nav">
      <div class="wrapper">
        <ul>
          <li :class="{ active: $route.path === '/settings/profile' }">
            <router-link to="/settings/profile">账户设置</router-link>
          </li>
          <li
            v-if="user?.perm.share"
            :class="{ active: $route.path === '/settings/shares' }"
          >
            <router-link to="/settings/shares">分享管理</router-link>
          </li>
          <li
            v-if="user?.perm.admin"
            :class="{ active: $route.path === '/settings/global' }"
          >
            <router-link to="/settings/global">全局设置</router-link>
          </li>
          <li
            v-if="user?.perm.admin"
            :class="{
              active:
                $route.path === '/settings/users' || $route.name === 'User',
            }"
          >
            <router-link to="/settings/users">用户管理</router-link>
          </li>
        </ul>
        <button
          v-if="showSave"
          type="button"
          class="button settings-nav-save"
          :disabled="
            loading || settingsPage?.saving || settingsPage?.canSave === false
          "
          @click="requestSave"
        >
          {{ settingsPage?.saving ? "保存中…" : "保存" }}
        </button>
      </div>
    </div>

    <div v-if="loading">
      <h2 class="message delayed">
        <div class="spinner">
          <div class="bounce1"></div>
          <div class="bounce2"></div>
          <div class="bounce3"></div>
        </div>
        <span>加载中...</span>
      </h2>
    </div>

    <router-view v-slot="{ Component }"
      ><component :is="Component" ref="settingsPage"
    /></router-view>
  </div>
</template>

<script setup lang="ts">
import { useAuthStore } from "@/stores/auth";
import { useLayoutStore } from "@/stores/layout";
import HeaderBar from "@/components/header/HeaderBar.vue";
import { computed, ref } from "vue";
const authStore = useAuthStore();
const layoutStore = useLayoutStore();
const settingsPage = ref<{
  saveSettings?: () => unknown;
  saving?: boolean;
  canSave?: boolean;
} | null>(null);

const user = computed(() => authStore.user);
const loading = computed(() => layoutStore.loading);

/** PC: single 保存 on the tab row; tabs own their success toast. */
const showSave = computed(() => Boolean(settingsPage.value?.saveSettings));
function requestSave() {
  if (
    !loading.value &&
    !settingsPage.value?.saving &&
    settingsPage.value?.canSave !== false
  )
    void settingsPage.value?.saveSettings?.();
}
</script>

<style scoped>
#nav .wrapper {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.settings-nav-save {
  flex-shrink: 0;
  min-width: 72px;
}
</style>
