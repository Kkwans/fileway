<template>
  <errors v-if="error" :errorCode="error.status" />
  <div class="row" v-else-if="!layoutStore.loading">
    <div class="column">
      <form ref="userFormEl" @submit="save" class="card">
        <div class="card-title">
          <h2 v-if="user?.id === 0">{{ "新建用户" }}</h2>
          <h2 v-else>{{ "编辑用户" }}</h2>
        </div>

        <div class="card-content" v-if="user">
          <user-form
            v-model:user="user"
            v-model:createUserDir="createUserDir"
            :isDefault="false"
            :isNew="isNew"
          />
        </div>

        <div class="card-action">
          <button
            v-if="!isNew"
            @click.prevent="deletePrompt"
            type="button"
            class="button button--flat button--red"
            aria-label="删除"
            title="删除"
          >
            删除
          </button>
          <router-link
            class="button button--flat button--grey"
            to="/settings/users"
            aria-label="取消"
            title="取消"
          >
            取消
          </router-link>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useAuthStore } from "@/stores/auth";
import { useLayoutStore } from "@/stores/layout";
import { users as api, settings } from "@/api";
import UserForm from "@/components/settings/UserForm.vue";
import Errors from "@/views/Errors.vue";
import { computed, inject, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { StatusError } from "@/api/utils";
import { authMethod } from "@/utils/constants";
import { logout } from "@/utils/auth";
import type { IUser } from "@/types/user";
import { useUnsavedChangesGuard } from "@/utils/unsavedChanges";

const saving = ref(false);
const error = ref<StatusError>();
const user = ref<IUser>();
const createUserDir = ref<boolean>(false);
const isCurrentPasswordRequired = ref<boolean>(false);
const userFormEl = ref<HTMLFormElement | null>(null);
const savedSignature = ref("");

const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<IToastSuccess>("$showSuccess")!;

const authStore = useAuthStore();
const layoutStore = useLayoutStore();
const route = useRoute();
const router = useRouter();

onMounted(() => {
  fetchData();
});

function requestSave() {
  userFormEl.value?.requestSubmit?.();
}

const currentSignature = () => (user.value ? JSON.stringify(user.value) : "");
const hasUnsavedChanges = () =>
  savedSignature.value !== "" && currentSignature() !== savedSignature.value;

const isNew = computed(() => route.path === "/settings/users/new");

watch(route, () => fetchData());
watch(user, () => {
  if (!user.value?.perm.admin) return;
  user.value.lockPassword = false;
});

const fetchData = async () => {
  layoutStore.loading = true;

  try {
    if (isNew.value) {
      const { defaults, createUserDir: _createUserDir } = await settings.get();
      isCurrentPasswordRequired.value = authMethod == "json";
      createUserDir.value = _createUserDir;
      user.value = {
        ...defaults,
        username: "",
        password: "",
        rules: [],
        lockPassword: false,
        id: 0,
      };
    } else {
      const { authMethod } = await settings.get();
      isCurrentPasswordRequired.value = authMethod == "json";
      const id = Array.isArray(route.params.id)
        ? route.params.id.join("")
        : route.params.id;
      user.value = { ...(await api.get(parseInt(id))) };
    }
    if (user.value?.perm.admin) user.value.lockPassword = false;
    savedSignature.value = currentSignature();
  } catch (err) {
    if (err instanceof Error) {
      error.value = err;
    }
  } finally {
    layoutStore.loading = false;
  }
};

const deletePrompt = () => {
  if (isCurrentPasswordRequired.value) {
    layoutStore.showHover({
      prompt: "current-password",
      confirm: (event: Event, currentPassword: string) => {
        event.preventDefault();
        layoutStore.closeHovers();
        deleteUser(currentPassword);
      },
    });
  } else {
    layoutStore.showHover({
      prompt: "deleteUser",
      confirm: () => deleteUser(""),
    });
  }
};

const deleteUser = async (currentPassword: string) => {
  if (!user.value) {
    return false;
  }
  try {
    await api.remove(user.value.id, currentPassword);
    savedSignature.value = currentSignature();
    if (user.value.id == authStore.user?.id) {
      logout();
    } else {
      router.push({ path: "/settings/users" });
    }
    $showSuccess("用户已删除");
  } catch (err) {
    if (err instanceof StatusError) {
      err.status === 403 ? $showError("无权访问") : $showError(err);
    } else if (err instanceof Error) {
      $showError(err);
    }
  }

  return true;
};

const save = (event: Event) => {
  event.preventDefault();
  if (saving.value || !user.value) return false;
  if (isCurrentPasswordRequired.value) {
    layoutStore.showHover({
      prompt: "current-password",
      confirm: (event: Event, currentPassword: string) => {
        event.preventDefault();
        layoutStore.closeHovers();
        void send(currentPassword);
      },
    });
  } else {
    void send("");
  }

  return true;
};

const send = async (currentPassword: string, navigateOnCreate = true) => {
  if (saving.value || !user.value) {
    return false;
  }

  saving.value = true;
  const accountId = authStore.user?.id;
  const submitted = JSON.parse(JSON.stringify(user.value)) as IUser;
  try {
    if (isNew.value) {
      const newUser: IUser = {
        ...submitted,
      };

      const loc = await api.create(newUser, currentPassword);
      savedSignature.value = currentSignature();
      if (navigateOnCreate)
        await router.push({ path: loc || "/settings/users" });
      $showSuccess("用户已创建");
    } else {
      await api.update(submitted, ["all"], currentPassword);

      if (
        submitted.id === authStore.user?.id &&
        accountId === authStore.user?.id
      ) {
        const safeUser = { ...submitted, password: "" };
        authStore.updateUser(safeUser);
      }
      user.value.password = "";
      savedSignature.value = currentSignature();

      $showSuccess("用户已更新");
    }
    return true;
  } catch (e: any) {
    $showError(e);
    return false;
  } finally {
    saving.value = false;
  }
};

const saveForNavigation = async (): Promise<boolean> => {
  if (!isCurrentPasswordRequired.value) return send("", false);
  return new Promise<boolean>((resolve) => {
    let submitted = false;
    layoutStore.showHover({
      prompt: "current-password",
      close: async () => {
        if (!submitted) resolve(false);
        return "";
      },
      confirm: async (event: Event, currentPassword: string) => {
        event.preventDefault();
        if (submitted) return;
        submitted = true;
        layoutStore.closeHovers();
        resolve(await send(currentPassword, false));
      },
    });
  });
};

useUnsavedChangesGuard(hasUnsavedChanges, saveForNavigation);
defineExpose({
  saveSettings: requestSave,
  saving,
  canSave: computed(() => Boolean(user.value) && !error.value),
});
</script>
