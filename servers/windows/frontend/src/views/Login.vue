<template>
  <div id="login" :class="{ recaptcha: recaptcha }">
    <div class="accent-blob"></div>
    <form @submit="submit">
      <img :src="logoURL" alt="NAS 文件管理" />
      <h1>{{ loginTitle }}</h1>
      <p v-if="reason != null" class="logout-message">
        {{ logoutReasonText }}
      </p>
      <div v-if="error !== ''" class="wrong">{{ error }}</div>

      <input
        autofocus
        class="input input--block"
        type="text"
        autocapitalize="off"
        v-model="username"
        placeholder="用户名"
      />
      <input
        class="input input--block"
        type="password"
        v-model="password"
        placeholder="密码"
      />
      <input
        class="input input--block"
        v-if="createMode"
        type="password"
        v-model="passwordConfirm"
        placeholder="确认密码"
      />

      <div v-if="recaptcha" id="recaptcha"></div>
      <button class="button button--block" type="submit" :disabled="loading">
        <AppIcon
          v-if="loading"
          name="loader"
          class="spin login-loading-icon"
          :size="16"
          :stroke-width="2"
        />
        {{ createMode ? "注册" : "登录" }}
      </button>

      <p @click="toggleMode" v-if="signup">
        {{ createMode ? "已有账号？去登录" : "没有账号？去注册" }}
      </p>
    </form>
  </div>
</template>

<script setup lang="ts">
import { StatusError } from "@/api/utils";
import * as auth from "@/utils/auth";
import { getLoginTitle, getLogoutReasonText } from "@/utils/login";
import {
  name,
  logoURL,
  recaptcha,
  recaptchaKey,
  signup,
} from "@/utils/constants";
import { inject, onMounted, ref, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppIcon from "@/components/ui/AppIcon.vue";

const createMode = ref<boolean>(false);
const loading = ref<boolean>(false);
const error = ref<string>("");
const username = ref<string>("");
const password = ref<string>("");
const passwordConfirm = ref<string>("");

const route = useRoute();
const router = useRouter();

const toggleMode = () => (createMode.value = !createMode.value);

const $showError = inject<IToastError>("$showError")!;

const reason = route.query["logout-reason"] ?? null;
const loginTitle = computed(() => getLoginTitle(name));

const logoutReasonText = computed(() => getLogoutReasonText(reason));

const submit = async (event: Event) => {
  event.preventDefault();
  event.stopPropagation();

  const redirect = (route.query.redirect || "/files/") as string;

  let captcha = "";
  if (recaptcha) {
    captcha = window.grecaptcha.getResponse();

    if (captcha === "") {
      error.value = "验证码验证失败";
      return;
    }
  }

  if (createMode.value) {
    if (password.value !== passwordConfirm.value) {
      error.value = "两次密码输入不一致";
      return;
    }
  }

  loading.value = true;
  error.value = "";
  try {
    if (createMode.value) {
      await auth.signup(username.value, password.value);
    }

    await auth.login(username.value, password.value, captcha);
    router.push({ path: redirect });
  } catch (e: any) {
    if (e instanceof StatusError) {
      if (e.status === 409) {
        error.value = "用户名已被占用";
      } else if (e.status === 403) {
        // 使用服务端返回的错误信息（已是中文）
        error.value = e.message || "用户名或密码错误";
      } else if (e.status === 400) {
        const match = e.message.match(/minimum length is (\d+)/);
        if (match) {
          error.value = `密码长度不能少于 ${match[1]} 位`;
        } else {
          error.value = e.message;
        }
      } else {
        $showError(e);
      }
    }
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  if (!recaptcha) return;

  window.grecaptcha.ready(function () {
    window.grecaptcha.render("recaptcha", {
      sitekey: recaptchaKey,
    });
  });
});
</script>
