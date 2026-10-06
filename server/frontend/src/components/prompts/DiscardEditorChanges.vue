<template>
  <div class="card floating">
    <div class="card-content">
      <p>你确定要放弃所做的更改吗？</p>
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
        @click="currentPrompt?.saveAction"
        aria-label="保存更改"
        title="保存更改"
        tabindex="1"
      >
        保存更改
      </button>
      <button
        id="focus-prompt"
        @click="currentPrompt?.confirm"
        class="button button--flat button--red"
        aria-label="放弃更改"
        title="放弃更改"
        tabindex="2"
      >
        放弃更改
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
