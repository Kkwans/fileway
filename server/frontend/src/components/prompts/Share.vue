<template>
  <div class="card floating" id="share">
    <div class="card-title">
      <h2>分享</h2>
    </div>

    <template v-if="listing">
      <div class="card-content">
        <table>
          <thead>
            <tr>
              <th scope="col">#</th>
              <th scope="col">分享期限</th>
              <th scope="col"></th>
              <th scope="col"></th>
              <th scope="col"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="link in links" :key="link.hash">
              <td>{{ link.hash }}</td>
              <td>
                <template v-if="link.expire !== 0">{{
                  humanTime(link.expire)
                }}</template>
                <template v-else>永久</template>
              </td>
              <td class="small">
                <button
                  class="action"
                  aria-label="复制到剪贴板"
                  title="复制到剪贴板"
                  @click="copyToClipboard(buildLink(link))"
                >
                  <AppIcon name="copy" :size="18" :stroke-width="1.9" />
                </button>
              </td>
              <td class="small">
                <button
                  class="action"
                  aria-label="复制下载链接到剪贴板"
                  title="复制下载链接到剪贴板"
                  :disabled="!!link.password_hash"
                  @click="copyToClipboard(buildDownloadLink(link))"
                >
                  <AppIcon name="download" :size="18" :stroke-width="1.9" />
                </button>
              </td>
              <td class="small">
                <button
                  class="action"
                  @click="deleteLink($event, link)"
                  aria-label="删除"
                  title="删除"
                >
                  <AppIcon name="trash" :size="18" :stroke-width="1.9" />
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card-action">
        <button
          class="button button--flat button--grey"
          @click="closeHovers"
          aria-label="关闭"
          title="关闭"
          tabindex="2"
        >
          关闭
        </button>
        <button
          id="focus-prompt"
          class="button button--flat button--blue"
          @click="switchListing"
          aria-label="新建"
          title="新建"
          tabindex="1"
        >
          新建
        </button>
      </div>
    </template>

    <template v-else>
      <div class="card-content">
        <p>分享期限</p>
        <div class="input-group input">
          <vue-number-input
            center
            controls
            size="small"
            :max="2147483647"
            :min="0"
            @keyup.enter="submit"
            v-model="time"
            tabindex="1"
          />
          <select
            class="right"
            v-model="unit"
            aria-label="时间单位"
            tabindex="2"
          >
            <option value="seconds">秒</option>
            <option value="minutes">分钟</option>
            <option value="hours">小时</option>
            <option value="days">天</option>
          </select>
        </div>
        <p>密码（选填，不填即无密码）</p>
        <input
          class="input input--block"
          type="password"
          v-model.trim="password"
          tabindex="3"
        />
      </div>

      <div class="card-action">
        <button
          class="button button--flat button--grey"
          @click="switchListing"
          aria-label="取消"
          title="取消"
          tabindex="5"
        >
          取消
        </button>
        <button
          id="focus-prompt"
          class="button button--flat button--blue"
          @click="submit"
          aria-label="分享"
          title="分享"
          tabindex="4"
        >
          分享
        </button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, onBeforeMount, ref } from "vue";
import { useRoute } from "vue-router";
import { storeToRefs } from "pinia";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import * as api from "@/api/index";
import dayjs from "@/utils/date";
import type { Share } from "@/types/api";
import type { Resource } from "@/types/file";
import AppIcon from "@/components/ui/AppIcon.vue";
import { copy } from "@/utils/clipboard";
const route = useRoute();

const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<(msg: string) => void>("$showSuccess")!;

const fileStore = useFileStore();
const layoutStore = useLayoutStore();
const { closeHovers } = layoutStore;

const { selectedItems, selectedCount, isListing } = storeToRefs(fileStore);

const time = ref(0);
const unit = ref("hours");
const links = ref<Share[]>([]);
const password = ref("");
const listing = ref(true);

const url = computed(() => {
  if (!isListing.value) {
    return route.path;
  }
  if (selectedCount.value === 0 || selectedCount.value > 1) {
    return undefined;
  }
  return selectedItems.value[0].url;
});

onBeforeMount(async () => {
  try {
    const result = await api.share.get(url.value!);
    links.value = Array.isArray(result) ? result : [result];
    sortLinks();

    if (links.value.length === 0) {
      listing.value = false;
    }
  } catch (e: any) {
    $showError(e);
  }
});

const copyToClipboard = (text: string) => {
  copy({ text }).then(
    () => $showSuccess("链接已复制到剪贴板"),
    () => {
      copy({ text }, { permission: true }).then(
        () => $showSuccess("链接已复制到剪贴板"),
        (e: any) => $showError(e)
      );
    }
  );
};

const submit = async () => {
  try {
    let res = null;

    if (!time.value) {
      res = await api.share.create(url.value!, password.value);
    } else {
      res = await api.share.create(
        url.value!,
        password.value,
        String(time.value),
        unit.value
      );
    }

    links.value.push(res as unknown as Share);
    sortLinks();

    time.value = 0;
    unit.value = "hours";
    password.value = "";

    listing.value = true;
  } catch (e: any) {
    $showError(e);
  }
};

const deleteLink = async (event: Event, link: Share) => {
  event.preventDefault();
  try {
    await api.share.remove(link.hash);
    links.value = links.value.filter((item) => item.hash !== link.hash);

    if (links.value.length === 0) {
      listing.value = false;
    }
  } catch (e: any) {
    $showError(e);
  }
};

const humanTime = (time: number | string) =>
  dayjs(Number(time) * 1000).fromNow();

const buildLink = (share: Share) => api.share.getShareURL(share);

const buildDownloadLink = (share: Share) => {
  // Build a minimal Resource-like object for the download URL
  const res = {
    hash: share.hash,
    path: "",
    token: undefined as string | undefined,
  } as unknown as Resource;
  return api.pub.getDownloadURL(res, true);
};

const sortLinks = () => {
  links.value = links.value.sort((a: Share, b: Share) => {
    if (a.expire === 0) return -1;
    if (b.expire === 0) return 1;
    return new Date(a.expire).getTime() - new Date(b.expire).getTime();
  });
};

const switchListing = () => {
  if (links.value.length === 0 && !listing.value) {
    closeHovers();
  }
  listing.value = !listing.value;
};
</script>
