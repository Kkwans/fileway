<template>
  <div class="card floating">
    <div class="card-content">
      <p>{{ "你确定要删除这个分享吗？" }}</p>
    </div>
    <div class="card-action">
      <button
        @click="closeHovers"
        class="button button--flat button--grey"
        aria-label="取消"
        title="取消"
        tabindex="2"
      >
        取消
      </button>
      <button
        id="focus-prompt"
        @click="submit"
        class="button button--flat button--red"
        aria-label="删除"
        title="取消"
        tabindex="1"
      >
        删除
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

const submit = () => {
  currentPrompt.value?.confirm();
};
</script>
