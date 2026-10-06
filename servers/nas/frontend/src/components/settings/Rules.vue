<template>
  <div class="rules small">
    <div v-for="(rule, index) in props.rules" :key="index">
      <input
        type="checkbox"
        v-model="rule.regex"
        :id="`${instanceId}-regex-${index}`"
      />
      <label :for="`${instanceId}-regex-${index}`">使用正则</label>
      <input
        type="checkbox"
        v-model="rule.allow"
        :id="`${instanceId}-allow-${index}`"
      />
      <label :for="`${instanceId}-allow-${index}`">允许</label>

      <input
        @keypress.enter.prevent
        type="text"
        v-if="rule.regex"
        v-model="rule.regexp.raw"
        placeholder="输入正则表达式"
      />
      <input
        @keypress.enter.prevent
        type="text"
        v-else
        placeholder="输入路径"
        v-model="rule.path"
      />

      <button
        type="button"
        class="button button--red"
        @click="remove($event, index)"
      >
        -
      </button>
    </div>

    <div>
      <button type="button" class="button" @click="create">新建</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useId } from "vue";
const instanceId = useId();
interface Rule {
  allow: boolean;
  path: string;
  regex: boolean;
  regexp: { raw: string };
}

const props = defineProps<{
  rules: Rule[];
}>();

const emit = defineEmits<{
  "update:rules": [value: Rule[]];
}>();

const remove = (event: Event, index: number) => {
  event.preventDefault();
  const rules = [...props.rules];
  rules.splice(index, 1);
  emit("update:rules", [...rules]);
};

const create = (event: Event) => {
  event.preventDefault();
  emit("update:rules", [
    ...props.rules,
    {
      allow: true,
      path: "",
      regex: false,
      regexp: { raw: "" },
    },
  ]);
};
</script>
