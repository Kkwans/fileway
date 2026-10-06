<template>
  <errors v-if="error" :errorCode="error.status" />
  <div class="row" v-else-if="!layoutStore.loading">
    <div class="column">
      <div class="card">
        <div class="card-title">
          <h2>分享管理</h2>
        </div>

        <div class="card-content full" v-if="links.length > 0">
          <table>
            <thead>
              <tr>
                <th scope="col">路径</th>
                <th scope="col">分享时长</th>
                <th v-if="authStore.user?.perm.admin" scope="col">用户名</th>
                <th scope="col"></th>
                <th scope="col"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="link in links" :key="link.hash">
                <td>
                  <a :href="buildLink(link)" target="_blank">{{ link.path }}</a>
                </td>
                <td>
                  <template v-if="link.expire !== 0">{{
                    humanTime(link.expire)
                  }}</template>
                  <template v-else>永久</template>
                </td>
                <td v-if="authStore.user?.perm.admin">{{ link.username }}</td>
                <td class="small">
                  <button
                    class="action"
                    @click="deleteLink($event, link)"
                    aria-label="删除"
                    title="删除"
                  >
                    <AppIcon name="trash" :size="18" />
                  </button>
                </td>
                <td class="small">
                  <button
                    class="action copy-clipboard"
                    aria-label="复制到剪贴板"
                    title="复制到剪贴板"
                    @click="copyToClipboard(buildLink(link))"
                  >
                    <AppIcon name="copy" :size="18" />
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="shares-empty">
          <AppIcon name="share" :size="40" class="shares-empty-icon" />
          <h3>暂无分享链接</h3>
          <p>生成后的链接会出现在这里，可随时复制或删除。</p>
          <ol class="shares-steps">
            <li>在文件列表选中文件或文件夹</li>
            <li>点击「分享」并设置有效期</li>
            <li>复制链接发给需要的人</li>
          </ol>
          <router-link class="button shares-cta" to="/files">
            去文件列表
          </router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useAuthStore } from "@/stores/auth";
import { useLayoutStore } from "@/stores/layout";
import AppIcon from "@/components/ui/AppIcon.vue";
import { share as api, users } from "@/api";
import type { Share } from "@/types/api";
import dayjs from "@/utils/date";
import Errors from "@/views/Errors.vue";
import { inject, ref, onMounted } from "vue";
import { StatusError } from "@/api/utils";
import { copy } from "@/utils/clipboard";

const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<IToastSuccess>("$showSuccess")!;

const layoutStore = useLayoutStore();
const authStore = useAuthStore();

const error = ref<StatusError | null>(null);
const links = ref<Share[]>([]);

onMounted(async () => {
  layoutStore.loading = true;
  try {
    const newLinks = await api.list();
    if (authStore.user?.perm.admin) {
      const userMap = new Map<number, string>();
      for (const user of await users.getAll())
        userMap.set(user.id, user.username);
      for (const link of newLinks) {
        if (link.userID && userMap.has(link.userID))
          link.username = userMap.get(link.userID);
      }
    }
    links.value = newLinks;
  } catch (err) {
    if (err instanceof Error) error.value = err as StatusError;
  } finally {
    layoutStore.loading = false;
  }
});

const copyToClipboard = (text: string) => {
  copy({ text }).then(
    () => $showSuccess("链接已复制到剪贴板"),
    () => {
      copy({ text }, { permission: true }).then(
        () => $showSuccess("链接已复制到剪贴板"),
        (e) => $showError(e)
      );
    }
  );
};

const deleteLink = (event: Event, link: Share) => {
  event.preventDefault();
  layoutStore.showHover({
    prompt: "share-delete",
    confirm: () => {
      layoutStore.closeHovers();
      try {
        api.remove(link.hash);
        links.value = links.value.filter((item) => item.hash !== link.hash);
        $showSuccess("分享已删除");
      } catch (err) {
        if (err instanceof Error) $showError(err);
      }
    },
  });
};

const humanTime = (time: number) => dayjs(time * 1000).fromNow();

const buildLink = (share: Share) => api.getShareURL(share);
</script>

<style scoped>
.shares-empty {
  display: grid;
  justify-items: center;
  gap: 10px;
  padding: 36px 20px 40px;
  color: var(--textSecondary, #667085);
  font-size: 14px;
  text-align: center;
}

.shares-empty-icon {
  opacity: 0.45;
  color: var(--textPrimary, #334155);
}

.shares-empty h3 {
  margin: 0;
  font-size: 1.05em;
  font-weight: 600;
  color: var(--textPrimary, #1f2937);
}

.shares-empty p {
  margin: 0;
  max-width: 36em;
}

.shares-steps {
  margin: 4px 0 8px;
  padding: 12px 16px 12px 32px;
  text-align: left;
  color: var(--textSecondary, #667085);
  font-size: 13px;
  line-height: 1.7;
  background: var(--surfaceSecondary, rgba(0, 0, 0, 0.03));
  border: 1px solid var(--divider, #e5e7eb);
  border-radius: 10px;
}

.shares-empty .button,
.shares-cta {
  display: inline-flex;
  min-height: 40px;
  align-items: center;
  justify-content: center;
  padding: 0.45em 1.35em;
  border: 0;
  border-radius: 8px;
  color: #fff;
  background: var(--blue, #2979ff);
  text-decoration: none;
  font-weight: 600;
  font-size: 13px;
}

.shares-cta:hover {
  filter: brightness(1.06);
}
</style>
