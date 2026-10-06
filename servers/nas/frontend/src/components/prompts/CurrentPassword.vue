<template>
  <div class="card floating">
    <div class="card-title">
      <h2>当前密码</h2>
    </div>

    <div class="card-content">
      <p>请输入密码以确认此操作。</p>
      <input
        id="focus-prompt"
        class="input input--block"
        type="password"
        @keyup.enter="submit"
        v-model="password"
      />
    </div>

    <div class="card-action">
      <button
        class="button button--flat button--grey"
        @click="cancel"
        aria-label="取消"
        title="取消"
      >
        取消
      </button>
      <button
        @click="submit"
        class="button button--flat"
        type="submit"
        aria-label="确定"
        title="确定"
      >
        确认
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useLayoutStore } from "@/stores/layout";
const layoutStore = useLayoutStore();

const { currentPrompt } = layoutStore;

const password = ref("");

const submit = (event: Event) => {
  currentPrompt?.confirm(event, password.value);
};

const cancel = () => {
  layoutStore.closeHovers();
};
</script>
