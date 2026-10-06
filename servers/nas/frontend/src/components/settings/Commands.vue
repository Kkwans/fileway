<template>
  <div>
    <h3>用户命令（Shell 命令）</h3>
    <p class="small">
      指定该用户可以执行的命令（Shell 命令），用空格分隔。例如：
      <i>git svn hg</i>.
    </p>
    <input class="input input--block" type="text" v-model.trim="raw" />
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
const props = defineProps<{
  commands: string[];
}>();

const emit = defineEmits<{
  "update:commands": [value: string[]];
}>();

const raw = computed({
  get: () => props.commands.join(" "),
  set: (value: string) => {
    if (value !== "") {
      emit("update:commands", value.split(" "));
    } else {
      emit("update:commands", []);
    }
  },
});
</script>
