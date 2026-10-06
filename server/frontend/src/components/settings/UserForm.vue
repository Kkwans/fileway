<template>
  <div>
    <p v-if="!isDefault && props.user !== null">
      <label for="username">用户名</label>
      <input
        class="input input--block"
        type="text"
        v-model="user.username"
        id="username"
      />
    </p>

    <p v-if="!isDefault">
      <label for="password">密码</label>
      <input
        class="input input--block"
        type="password"
        :placeholder="passwordPlaceholder"
        v-model="user.password"
        id="password"
      />
    </p>

    <p>
      <label for="scope">作用域</label>
      <input
        :disabled="createUserDirData ?? false"
        :placeholder="scopePlaceholder"
        class="input input--block"
        type="text"
        v-model="user.scope"
        id="scope"
      />
    </p>
    <p class="small" v-if="displayHomeDirectoryCheckbox">
      <input type="checkbox" v-model="createUserDirData" />
      创建用户目录
    </p>

    <p v-if="!isDefault && user.perm">
      <input
        type="checkbox"
        :disabled="user.perm.admin"
        v-model="user.lockPassword"
      />
      锁定密码
    </p>

    <permissions v-if="user.perm" v-model:perm="user.perm" />
    <commands
      v-if="enableExec && user.commands"
      v-model:commands="user.commands"
    />

    <div v-if="!isDefault">
      <h3>{{ "规则" }}</h3>
      <p class="small">规则帮助</p>
      <rules v-if="user.rules" v-model:rules="user.rules" />
    </div>
  </div>
</template>

<script setup lang="ts">
import Rules from "./Rules.vue";
import Permissions from "./Permissions.vue";
import Commands from "./Commands.vue";
import { enableExec } from "@/utils/constants";
import { computed, onMounted, ref, watch } from "vue";
const createUserDirData = ref<boolean | null>(null);
const originalUserScope = ref<string | null>(null);

const props = defineProps<{
  user: any; // IUserForm - relaxed for v-model compatibility
  isNew: boolean;
  isDefault: boolean;
  createUserDir?: boolean;
}>();

onMounted(() => {
  if (props.user.scope) {
    originalUserScope.value = props.user.scope;
    createUserDirData.value = props.createUserDir;
  }
});

const passwordPlaceholder = computed(() => (props.isNew ? "" : "留空则不修改"));
const scopePlaceholder = computed(() =>
  createUserDirData.value ? "用户作用域生成占位符" : ""
);
const displayHomeDirectoryCheckbox = computed(
  () => props.isNew && createUserDirData.value
);

watch(
  () => props.user,
  () => {
    if (!props.user?.perm?.admin) return;
    props.user.lockPassword = false;
  }
);

watch(createUserDirData, () => {
  if (props.user?.scope) {
    props.user.scope = createUserDirData.value
      ? ""
      : (originalUserScope.value ?? "");
  }
});
</script>
