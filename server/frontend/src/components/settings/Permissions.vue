<template>
  <div>
    <h3>权限</h3>
    <p class="small">
      你可以将该用户设置为管理员或单独选择各项权限。如果你选择了“管理员”，则其他的选项会被自动选中，同时该用户可以管理其他用户。
    </p>

    <p>
      <input type="checkbox" v-model="admin" />
      管理员
    </p>

    <p>
      <input type="checkbox" :disabled="admin" v-model="perm.create" />
      创建文件和文件夹
    </p>
    <p>
      <input type="checkbox" :disabled="admin" v-model="perm.delete" />
      删除文件和文件夹
    </p>
    <p>
      <input
        type="checkbox"
        :disabled="admin || perm.share"
        v-model="perm.download"
      />
      下载
    </p>
    <p>
      <input type="checkbox" :disabled="admin" v-model="perm.modify" />
      编辑
    </p>
    <p v-if="isExecEnabled">
      <input type="checkbox" :disabled="admin" v-model="perm.execute" />
      执行命令
    </p>
    <p>
      <input type="checkbox" :disabled="admin" v-model="perm.rename" />
      重命名或移动文件和文件夹
    </p>
    <p>
      <input type="checkbox" :disabled="admin" v-model="perm.share" />
      分享文件（需要下载权限）
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, watch } from "vue";
import { enableExec } from "@/utils/constants";

interface Permissions {
  admin: boolean;
  create: boolean;
  delete: boolean;
  download: boolean;
  modify: boolean;
  execute: boolean;
  rename: boolean;
  share: boolean;
}

const props = defineProps<{
  perm: Permissions;
}>();

const isExecEnabled = enableExec;

const admin = computed({
  get: () => props.perm.admin,
  set: (value: boolean) => {
    if (value) {
      for (const key in props.perm) {
        (props.perm as unknown as Record<string, boolean>)[key] = true;
      }
    }
    props.perm.admin = value;
  },
});

watch(
  () => props.perm,
  () => {
    if (props.perm.share === true) {
      props.perm.download = true;
    }
  },
  { deep: true }
);
</script>
