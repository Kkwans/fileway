<template>
  <div class="card floating">
    <div class="card-title">
      <h2>新建文件</h2>
    </div>

    <div class="card-content">
      <p>输入文件名</p>
      <input
        id="focus-prompt"
        class="input input--block"
        type="text"
        @keyup.enter="submit"
        v-model.trim="name"
      />
      <CreateFilePath :name="name" />
    </div>

    <div class="card-action">
      <button
        class="button button--flat button--grey"
        @click="layoutStore.closeHovers"
        aria-label="取消"
        title="取消"
      >
        {{ "取消" }}
      </button>
      <button
        class="button button--flat"
        @click="submit"
        aria-label="创建"
        title="创建"
      >
        {{ "创建" }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { inject, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import CreateFilePath from "@/components/prompts/CreateFilePath.vue";

import { files as api } from "@/api";
import url from "@/utils/url";
const $showError = inject<IToastError>("$showError")!;

const fileStore = useFileStore();
const layoutStore = useLayoutStore();

const route = useRoute();
const router = useRouter();

const name = ref<string>("");

const submit = async (event: Event) => {
  event.preventDefault();
  if (name.value === "") return;

  // Build the path of the new directory.
  let uri = fileStore.isFiles ? route.path + "/" : "/";

  if (!fileStore.isListing) {
    uri = url.removeLastDir(uri) + "/";
  }

  uri += encodeURIComponent(name.value);
  uri = uri.replace("//", "/");

  try {
    await api.post(uri);
    router.push({ path: uri });
  } catch (e) {
    if (e instanceof Error) {
      $showError(e);
    }
  }

  layoutStore.closeHovers();
};
</script>
