<template>
  <div id="recent-page" class="activity-page">
    <header-bar show-menu show-logo title="最近访问" title-icon="history">
      <template #actions>
        <button
          type="button"
          class="activity-header-action"
          aria-label="刷新"
          title="刷新"
          :disabled="recentStore.loading"
          @click="load"
        >
          <app-icon name="refresh" :size="19" />
          刷新
        </button>
      </template>
    </header-bar>

    <main class="activity-workspace">
      <section
        v-if="recentStore.error"
        class="activity-state activity-state--error"
      >
        <app-icon name="cloud-off" :size="24" />
        <div>
          <strong>无法读取最近访问</strong>
          <p>{{ recentStore.error }}</p>
        </div>
        <button type="button" @click="load">重试</button>
      </section>

      <section
        v-else-if="recentStore.loading && !recentStore.loaded"
        class="activity-list activity-list--loading"
        aria-label="正在加载最近访问"
      >
        <div
          v-for="index in 6"
          :key="index"
          class="activity-skeleton activity-skeleton--slim"
        ></div>
      </section>

      <section
        v-else-if="recentStore.items.length === 0"
        class="activity-empty"
      >
        <div aria-hidden="true"><app-icon name="history" :size="28" /></div>
        <h2>还没有最近访问</h2>
        <p>成功进入目录或打开文件后，会出现在这里。</p>
        <router-link to="/files/">浏览文件</router-link>
      </section>

      <section v-else class="recent-list" aria-label="最近访问列表">
        <router-link
          v-for="entry in recentStore.items"
          :key="entry.id"
          :to="entryRoute(entry)"
          class="recent-entry"
        >
          <span class="recent-entry-icon" :class="{ folder: entry.isDir }">
            <app-icon :name="entryIcon(entry)" :size="22" />
          </span>
          <span class="recent-entry-copy">
            <strong :title="displayPath(entry.name)">{{
              displayPath(entry.name)
            }}</strong>
            <small :title="displayPath(entry.path)">{{
              displayPath(entry.path)
            }}</small>
          </span>
          <time :datetime="new Date(entry.accessedAt).toISOString()">
            {{ dayjs(entry.accessedAt).fromNow() }}
          </time>
          <app-icon
            name="chevron-right"
            :size="20"
            class="recent-entry-arrow"
          />
        </router-link>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { inject, onMounted } from "vue";
import HeaderBar from "@/components/header/HeaderBar.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import type { RecentEntry } from "@/api/recent";
import { useRecentStore } from "@/stores/recent";
import dayjs from "@/utils/date";
import { getResourceIconName } from "@/utils/fileIcons";
import { encodePath } from "@/utils/url";
import { resourceOpenRoute } from "@/utils/archivePath";
import { displayPath } from "@/utils/displayPath";

const recentStore = useRecentStore();
const $showError = inject<IToastError>("$showError")!;

async function load() {
  try {
    await recentStore.load();
  } catch (error) {
    $showError(error as Error, false);
  }
}

function entryRoute(entry: RecentEntry) {
  const suffix = entry.isDir && entry.path !== "/" ? "/" : "";
  return resourceOpenRoute({
    isDir: entry.isDir,
    path: entry.path,
    url: `/files${encodePath(entry.path)}${suffix}`,
  });
}

function entryIcon(entry: RecentEntry) {
  return getResourceIconName(entry.name, "", entry.isDir);
}

onMounted(load);
</script>
