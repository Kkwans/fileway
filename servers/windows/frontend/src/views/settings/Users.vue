<template>
  <errors v-if="error" :errorCode="error.status" />
  <div class="row" v-else-if="!layoutStore.loading">
    <div class="column">
      <div class="card">
        <div class="card-title">
          <h2>用户管理</h2>
          <router-link class="button" to="/settings/users/new">新建</router-link>
        </div>

        <div class="card-content full">
          <table>
            <thead>
              <tr>
                <th scope="col">用户名</th>
                <th scope="col">管理员</th>
                <th scope="col">作用域</th>
                <th scope="col"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="user in users" :key="user.id">
                <td>{{ user.username }}</td>
                <td>
                  <AppIcon
                    v-if="user.perm.admin"
                    name="circle-check"
                    :size="18"
                    title="管理员"
                    aria-label="管理员"
                  />
                  <AppIcon
                    v-else
                    name="circle-alert"
                    :size="18"
                    title="普通用户"
                    aria-label="普通用户"
                  />
                </td>
                <td>{{ user.scope }}</td>
                <td class="small">
                  <router-link :to="'/settings/users/' + user.id">
                    <AppIcon name="rename" :size="18" />
                  </router-link>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useLayoutStore } from "@/stores/layout";
import { users as api } from "@/api";
import AppIcon from "@/components/ui/AppIcon.vue";
import Errors from "@/views/Errors.vue";
import { onMounted, ref } from "vue";
import { StatusError } from "@/api/utils";
import type { IUser } from "@/types/user";
const error = ref<StatusError | null>(null);
const users = ref<IUser[]>([]);

const layoutStore = useLayoutStore();

onMounted(async () => {
  layoutStore.loading = true;

  try {
    users.value = await api.getAll();
  } catch (err) {
    if (err instanceof Error) {
      error.value = err;
    }
  } finally {
    layoutStore.loading = false;
  }
});
</script>
