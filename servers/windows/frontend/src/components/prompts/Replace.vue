<template>
  <div class="card floating">
    <div class="card-title">
      <h2>替换</h2>
    </div>

    <div class="card-content">
      <p>
        你尝试上传的文件中有一个与现有文件的名称存在冲突。是否替换现有的同名文件？
      </p>
    </div>

    <div class="card-action">
      <button
        class="button button--flat button--grey"
        @click="closeHovers"
        aria-label="取消"
        title="取消"
        tabindex="3"
      >
        取消
      </button>
      <button
        class="button button--flat button--blue"
        @click="currentPrompt?.action"
        aria-label="继续"
        title="继续"
        tabindex="2"
      >
        继续
      </button>
      <button
        id="focus-prompt"
        class="button button--flat button--red"
        @click="currentPrompt?.confirm"
        aria-label="替换"
        title="替换"
        tabindex="1"
      >
        替换
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useLayoutStore } from "@/stores/layout";
import { storeToRefs } from "pinia";

const layoutStore = useLayoutStore();
const { currentPrompt } = storeToRefs(layoutStore);
const { closeHovers } = layoutStore;
</script>
