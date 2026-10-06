<template>
  <div class="card floating">
    <div class="card-title">
      <h2>新建文件夹</h2>
    </div>

    <div class="card-content">
      <p>输入文件夹名</p>
      <input
        id="focus-prompt"
        class="input input--block"
        type="text"
        @keyup.enter="submit"
        v-model.trim="name"
        tabindex="1"
      />
      <CreateFilePath :name="name" :is-dir="true" :path="base" />
    </div>

    <div class="card-action">
      <button
        class="button button--flat button--grey"
        @click="layoutStore.closeHovers"
        aria-label="取消"
        title="取消"
        tabindex="3"
      >
        {{ "取消" }}
      </button>
      <button
        class="button button--flat"
        aria-label="创建"
        title="创建"
        @click="submit"
        tabindex="2"
      >
        {{ "创建" }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, ref } from "vue";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";

import { files as api } from "@/api";
import url from "@/utils/url";
import { useRoute, useRouter } from "vue-router";
import CreateFilePath from "@/components/prompts/CreateFilePath.vue";
const $showError = inject<IToastError>("$showError")!;

const fileStore = useFileStore();
const layoutStore = useLayoutStore();

const base = computed(() => {
  return layoutStore.currentPrompt?.props?.base;
});

const route = useRoute();
const router = useRouter();

const name = ref<string>("");

const submit = async (event: Event) => {
  event.preventDefault();
  if (name.value === "") return;

  // Build the path of the new directory.
  let uri: string;
  if (base.value) uri = base.value;
  else if (fileStore.isFiles) uri = route.path + "/";
  else uri = "/";

  if (!fileStore.isListing) {
    uri = url.removeLastDir(uri) + "/";
  }

  uri += encodeURIComponent(name.value) + "/";
  uri = uri.replace("//", "/");

  try {
    await api.post(uri);
    if (layoutStore.currentPrompt?.props?.redirect) {
      router.push({ path: uri });
    } else if (!base.value) {
      const res = await api.fetch(url.removeLastDir(uri) + "/");
      fileStore.updateRequest(res);
    }
    if (layoutStore.currentPrompt?.confirm) {
      layoutStore.currentPrompt?.confirm(uri);
    }
  } catch (e) {
    if (e instanceof Error) {
      $showError(e);
    }
  }

  layoutStore.closeHovers();
};
</script>
