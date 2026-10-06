<template>
  <div class="row profile-settings-grid">
    <div class="column">
      <form class="card" @submit.prevent="updateSettings">
        <div class="card-title">
          <h2>账户设置</h2>
        </div>

        <div class="card-content account-preferences">
          <section class="settings-section" aria-labelledby="pref-interact">
            <h3 id="pref-interact">交互</h3>
            <div class="check-card-list">
              <label class="check-card">
                <input
                  class="check-card__input"
                  type="checkbox"
                  name="singleClick"
                  v-model="singleClick"
                />
                <span class="check-card__copy">
                  <strong>桌面端单击打开</strong>
                  <small>移动端仍保持双击打开、长按选择</small>
                </span>
              </label>
              <label class="check-card">
                <input
                  class="check-card__input"
                  type="checkbox"
                  name="redirectAfterCopyMove"
                  v-model="redirectAfterCopyMove"
                />
                <span class="check-card__copy">
                  <strong>复制或移动后跳转</strong>
                  <small>操作完成后进入目标目录</small>
                </span>
              </label>
              <label class="check-card">
                <input
                  class="check-card__input"
                  type="checkbox"
                  name="dateFormat"
                  v-model="dateFormat"
                />
                <span class="check-card__copy">
                  <strong>使用绝对日期</strong>
                  <small>关闭时显示“几分钟前”等相对时间</small>
                </span>
              </label>
            </div>
          </section>

          <section class="settings-section" aria-labelledby="pref-player">
            <h3 id="pref-player">播放器</h3>
            <div class="setting-toggle-list">
              <div class="setting-toggle-row setting-control-row">
                <div class="setting-copy">
                  <strong>播放器控件自动隐藏</strong>
                  <small>账户级，跨设备；0 = 不自动隐藏，1–20 秒</small>
                </div>
                <div class="setting-controls">
                  <div class="setting-control-field">
                    <input
                      class="app-number"
                      type="number"
                      min="0"
                      max="20"
                      step="1"
                      v-model.number="controlsTimeoutSec"
                      aria-label="控件自动隐藏秒数"
                    />
                    <span class="setting-unit">秒</span>
                  </div>
                </div>
              </div>
              <div class="setting-toggle-row setting-control-row">
                <div class="setting-copy">
                  <strong>默认播放策略</strong>
                  <small>进入视频时的策略；播放器内切换会立即生效</small>
                </div>
                <div class="setting-controls">
                  <AppSelect
                    v-model="playbackMode"
                    name="playbackMode"
                    aria-label="默认播放策略"
                    :options="playbackModeOptions"
                  />
                </div>
              </div>
              <div class="setting-toggle-row setting-control-row">
                <div class="setting-copy">
                  <strong>进页续播</strong>
                  <small>是否自动跳到上次播放进度（按账号记忆）</small>
                </div>
                <div class="setting-controls">
                  <AppSelect
                    v-model="resumeMode"
                    name="resumeMode"
                    aria-label="进页续播"
                    :options="resumeModeOptions"
                  />
                </div>
              </div>
              <div class="setting-toggle-row setting-control-row">
                <div class="setting-copy">
                  <strong>续播提示阈值</strong>
                  <small>进度超过该秒数才提示/续播；5–600 秒，默认 10</small>
                </div>
                <div class="setting-controls">
                  <div class="setting-control-field">
                    <input
                      class="app-number"
                      type="number"
                      min="5"
                      max="600"
                      step="1"
                      v-model.number="resumeMinSec"
                      name="resumeMinSec"
                      aria-label="续播提示阈值秒数"
                    />
                    <span class="setting-unit">秒</span>
                  </div>
                </div>
              </div>
              <div class="setting-toggle-row setting-control-row">
                <div class="setting-copy">
                  <strong>默认倍速</strong>
                  <small>0.10–5.00，两位小数（如 1.15）</small>
                </div>
                <div class="setting-controls">
                  <div class="setting-control-field">
                    <input
                      class="app-number"
                      type="number"
                      min="0.1"
                      max="5"
                      step="0.01"
                      v-model.number="playbackRate"
                      name="playbackRate"
                      aria-label="默认倍速"
                    />
                    <span class="setting-unit">x</span>
                  </div>
                </div>
              </div>
            </div>
          </section>

          <section class="prefix-preferences" aria-labelledby="prefix-title">
            <div class="prefix-preferences-heading">
              <div>
                <h3 id="prefix-title">特殊前缀</h3>
                <p>
                  控制目录列表中的显示和分组展开状态。直接路径、搜索、收藏和最近访问不受影响。
                </p>
              </div>
              <span>{{ customRuleCount }}/20 自定义</span>
            </div>

            <div class="prefix-rule-list">
              <div
                v-for="(rule, index) in prefixRules"
                :key="rule.prefix"
                class="prefix-rule-row"
              >
                <code>{{ rule.prefix }}</code>
                <span class="prefix-rule-kind">
                  {{
                    listingPreferencesStore.isBuiltIn(rule.prefix)
                      ? "内置"
                      : "自定义"
                  }}
                </span>
                <div class="prefix-rule-actions">
                  <button
                    type="button"
                    class="prefix-state-button"
                    :class="{ active: rule.visible }"
                    :aria-pressed="rule.visible"
                    :aria-label="`${rule.visible ? '隐藏' : '显示'}前缀 ${rule.prefix}`"
                    @click="togglePrefixVisibility(rule)"
                  >
                    <AppIcon
                      :name="rule.visible ? 'eye' : 'eye-off'"
                      :size="18"
                    />
                    {{ rule.visible ? "显示" : "隐藏" }}
                  </button>
                  <button
                    type="button"
                    class="prefix-state-button"
                    :class="{ active: rule.expanded }"
                    :aria-pressed="rule.expanded"
                    :aria-label="`${rule.expanded ? '折叠' : '展开'}前缀 ${rule.prefix}`"
                    @click="togglePrefixExpanded(rule)"
                  >
                    <AppIcon
                      :name="rule.expanded ? 'chevron-up' : 'chevron-down'"
                      :size="18"
                    />
                    {{ rule.expanded ? "展开" : "折叠" }}
                  </button>
                  <button
                    type="button"
                    class="prefix-icon-button"
                    :disabled="index === 0"
                    :aria-label="`上移前缀 ${rule.prefix}`"
                    @click="movePrefix(index, -1)"
                  >
                    <AppIcon name="arrow-up" :size="18" />
                  </button>
                  <button
                    type="button"
                    class="prefix-icon-button"
                    :disabled="index === prefixRules.length - 1"
                    :aria-label="`下移前缀 ${rule.prefix}`"
                    @click="movePrefix(index, 1)"
                  >
                    <AppIcon name="arrow-down" :size="18" />
                  </button>
                  <button
                    v-if="!listingPreferencesStore.isBuiltIn(rule.prefix)"
                    type="button"
                    class="prefix-icon-button danger"
                    :aria-label="`删除前缀 ${rule.prefix}`"
                    @click="removePrefix(rule.prefix)"
                  >
                    <AppIcon name="trash" :size="18" />
                  </button>
                </div>
              </div>
            </div>

            <div class="prefix-add-row">
              <input
                v-model="newPrefix"
                type="text"
                maxlength="8"
                placeholder="输入 1–8 个字符"
                aria-label="自定义特殊前缀"
                @keyup.enter="addPrefix"
              />
              <button
                type="button"
                class="button"
                :disabled="customRuleCount >= 20 || !newPrefix"
                @click="addPrefix"
              >
                添加前缀
              </button>
            </div>
            <p v-if="prefixError" class="prefix-error" role="alert">
              {{ prefixError }}
            </p>
          </section>
        </div>
      </form>
    </div>

    <div class="column">
      <form class="card" @submit.prevent="updateSettings">
        <div class="card-title">
          <h2>编辑器</h2>
        </div>
        <div class="card-content">
          <div class="setting-toggle-row setting-control-row">
            <div class="setting-copy">
              <strong>Ace 主题</strong>
              <small>代码/文本编辑器配色；默认跟随应用</small>
            </div>
            <div class="setting-controls">
              <div class="app-select app-select--wide">
                <AceEditorTheme
                  v-model:aceEditorTheme="aceEditorTheme"
                  id="aceTheme"
                />
              </div>
            </div>
          </div>
        </div>
      </form>

      <form
        v-if="!noAuth && !authStore.user?.lockPassword"
        class="card"
        @submit.prevent="updatePassword"
      >
        <div class="card-title">
          <h2>修改密码</h2>
        </div>
        <div class="card-content password-fields">
          <input
            :class="passwordClass"
            type="password"
            placeholder="新密码"
            v-model="password"
            name="password"
            autocomplete="new-password"
          />
          <input
            :class="passwordClass"
            type="password"
            placeholder="确认新密码"
            v-model="passwordConf"
            name="passwordConf"
            autocomplete="new-password"
          />
          <input
            v-if="isCurrentPasswordRequired"
            :class="passwordClass"
            type="password"
            placeholder="当前密码"
            v-model="currentPassword"
            name="current_password"
            autocomplete="current-password"
          />
        </div>
        <!-- 唯一保存入口：设置页 tab 栏「保存」 -->
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useAuthStore } from "@/stores/auth";
import { useLayoutStore } from "@/stores/layout";
import { useListingPreferencesStore } from "@/stores/listingPreferences";
import { users as api } from "@/api";
import AceEditorTheme from "@/components/settings/AceEditorTheme.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import AppSelect from "@/components/ui/AppSelect.vue";
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { authMethod, noAuth } from "@/utils/constants";
import type { PrefixRule } from "@/types/user";
import {
  MAX_CUSTOM_PREFIX_RULES,
  validatePrefix,
} from "@/utils/listingPreferences";
import {
  resolveControlsTimeoutMs,
  writeControlsTimeoutMs,
} from "@/utils/playerControls";
const layoutStore = useLayoutStore();
const authStore = useAuthStore();
const listingPreferencesStore = useListingPreferencesStore();

const $showSuccess = inject<IToastSuccess>("$showSuccess")!;
const $showError = inject<IToastError>("$showError")!;

const password = ref<string>("");
const passwordConf = ref<string>("");
const currentPassword = ref<string>("");
const isCurrentPasswordRequired = ref<boolean>(false);
const singleClick = ref<boolean>(false);
const redirectAfterCopyMove = ref<boolean>(false);
const dateFormat = ref<boolean>(false);
const controlsTimeoutSec = ref<number>(4);
const playbackMode = ref<string>("native");
const playbackRate = ref<number>(1);
const resumeMode = ref<string>("resume");
const resumeMinSec = ref<number>(10);

const playbackModeOptions = [
  { label: "原生优先", value: "native" },
  { label: "兼容优先", value: "compat" },
  { label: "每次询问", value: "ask" },
];
const resumeModeOptions = [
  { label: "默认续播", value: "resume" },
  { label: "默认从头播放", value: "from-start" },
  { label: "每次询问", value: "ask" },
];

function persistControlsTimeout() {
  const raw = Number(controlsTimeoutSec.value);
  const sec = !Number.isFinite(raw)
    ? 4
    : Math.min(20, Math.max(0, Math.round(raw)));
  controlsTimeoutSec.value = sec;
  writeControlsTimeoutMs(sec * 1000);
}

function clampPlaybackRate(raw: number) {
  if (!Number.isFinite(raw)) return 1;
  return Math.min(5, Math.max(0.1, Math.round(raw * 100) / 100));
}

function normalizePlaybackMode(raw: string) {
  const v = (raw || "").toLowerCase();
  if (v === "compat" || v === "ask") return v;
  return "native";
}

function normalizeResumeMode(raw: string) {
  const v = (raw || "").toLowerCase();
  if (v === "from-start" || v === "ask" || v === "resume") return v;
  return "resume";
}

function clampResumeMinSec(raw: number) {
  const n = Number(raw);
  if (!Number.isFinite(n)) return 10;
  return Math.min(600, Math.max(5, Math.round(n)));
}
const aceEditorTheme = ref<string>("");
const newPrefix = ref("");
const prefixError = ref("");
const prefixRules = computed(() => listingPreferencesStore.prefixRules);
const customRuleCount = computed(
  () =>
    prefixRules.value.filter(
      (rule) => !listingPreferencesStore.isBuiltIn(rule.prefix)
    ).length
);

const passwordClass = computed(() => {
  const baseClass = "input input--block";

  if (password.value === "" && passwordConf.value === "") {
    return baseClass;
  }

  if (password.value === passwordConf.value) {
    return `${baseClass} input--green`;
  }

  return `${baseClass} input--red`;
});

onMounted(async () => {
  layoutStore.loading = true;
  if (authStore.user === null) return false;
  listingPreferencesStore.loadFromUser();
  singleClick.value = authStore.user.singleClick;
  redirectAfterCopyMove.value = authStore.user.redirectAfterCopyMove;
  dateFormat.value = authStore.user.dateFormat;
  aceEditorTheme.value = authStore.user.aceEditorTheme || "";
  controlsTimeoutSec.value = Math.round(
    resolveControlsTimeoutMs(
      authStore.user.playerPreferences?.controlsTimeoutSec
    ) / 1000
  );
  playbackMode.value = normalizePlaybackMode(
    authStore.user.playerPreferences?.playbackMode || "native"
  );
  playbackRate.value = clampPlaybackRate(
    authStore.user.playerPreferences?.playbackRate ?? 1
  );
  resumeMode.value = normalizeResumeMode(
    authStore.user.playerPreferences?.resumeMode || "resume"
  );
  resumeMinSec.value = clampResumeMinSec(
    authStore.user.playerPreferences?.resumeMinSec ?? 10
  );
  layoutStore.loading = false;
  isCurrentPasswordRequired.value = authMethod == "json";

  // Player prefs: save immediately on change.
  let prefsReady = false;
  watch(
    [controlsTimeoutSec, playbackMode, playbackRate, resumeMode, resumeMinSec],
    () => {
      if (!prefsReady || !authStore.user?.id) return;
      void persistPlayerPrefsNow();
    }
  );
  // Account toggles + editor theme: save on change (no in-card 保存/更新).
  let accountReady = false;
  watch([singleClick, redirectAfterCopyMove, dateFormat, aceEditorTheme], () => {
    if (!accountReady || !authStore.user?.id) return;
    void persistAccountPrefsNow();
  });
  setTimeout(() => {
    prefsReady = true;
    accountReady = true;
  }, 0);

  window.addEventListener("winfb-settings-save", onSettingsSave);
  return true;
});

onBeforeUnmount(() => {
  window.removeEventListener("winfb-settings-save", onSettingsSave);
});

function onSettingsSave() {
  void (async () => {
    try {
      const wantsPassword =
        password.value &&
        password.value === passwordConf.value &&
        (!isCurrentPasswordRequired.value || currentPassword.value);
      const accountOk = await persistAccountPrefsNow();
      const playerOk = await persistPlayerPrefsNow();
      if (wantsPassword) {
        await updatePassword(new Event("submit"));
        return;
      }
      if (accountOk && playerOk) {
        $showSuccess("设置已保存");
      }
      // failures already toasted inside persist*
    } catch (err) {
      if (err instanceof Error) $showError(err);
    }
  })();
}

async function persistPlayerPrefsNow(): Promise<boolean> {
  if (!authStore.user?.id) return false;
  persistControlsTimeout();
  playbackRate.value = clampPlaybackRate(playbackRate.value);
  playbackMode.value = normalizePlaybackMode(playbackMode.value);
  resumeMode.value = normalizeResumeMode(resumeMode.value);
  resumeMinSec.value = clampResumeMinSec(resumeMinSec.value);
  const data = {
    ...authStore.user,
    id: authStore.user.id,
    playerPreferences: {
      controlsTimeoutSec: controlsTimeoutSec.value,
      playbackMode: playbackMode.value,
      playbackRate: playbackRate.value,
      resumeMode: resumeMode.value,
      resumeMinSec: resumeMinSec.value,
    },
  };
  try {
    await api.update(data, ["PlayerPreferences"]);
    authStore.updateUser(data);
    return true;
  } catch (err) {
    if (err instanceof Error) $showError(err);
    return false;
  }
}

async function persistAccountPrefsNow(): Promise<boolean> {
  if (!authStore.user?.id) return false;
  const data = {
    ...authStore.user,
    id: authStore.user.id,
    singleClick: singleClick.value,
    redirectAfterCopyMove: redirectAfterCopyMove.value,
    dateFormat: dateFormat.value,
    aceEditorTheme: aceEditorTheme.value || "",
  };
  try {
    await api.update(data, [
      "singleClick",
      "redirectAfterCopyMove",
      "dateFormat",
      "aceEditorTheme",
    ]);
    authStore.updateUser(data);
    return true;
  } catch (err) {
    if (err instanceof Error) $showError(err);
    return false;
  }
}

const updatePassword = async (event: Event) => {
  event.preventDefault();

  if (
    password.value !== passwordConf.value ||
    password.value === "" ||
    (isCurrentPasswordRequired.value && currentPassword.value === "") ||
    authStore.user === null
  ) {
    return;
  }

  try {
    const data = {
      ...authStore.user,
      id: authStore.user.id,
      password: password.value,
    };
    await api.update(data, ["password"], currentPassword.value || undefined);
    authStore.updateUser(data);
    $showSuccess("密码已更新");
  } catch (e: any) {
    $showError(e);
  } finally {
    password.value = passwordConf.value = "";
  }
};

const updateSettings = async (event: Event) => {
  event.preventDefault();
  await persistAccountPrefsNow();
  await persistPlayerPrefsNow();
};

const withPrefixError = async (operation: () => Promise<void>) => {
  prefixError.value = "";
  try {
    await operation();
  } catch (error) {
    prefixError.value = error instanceof Error ? error.message : "偏好保存失败";
    $showError(error instanceof Error ? error : new Error(prefixError.value));
  }
};

const togglePrefixVisibility = (rule: PrefixRule) =>
  withPrefixError(() =>
    listingPreferencesStore.updateRule(rule.prefix, {
      visible: !rule.visible,
    })
  );

const togglePrefixExpanded = (rule: PrefixRule) =>
  withPrefixError(() =>
    listingPreferencesStore.updateRule(rule.prefix, {
      expanded: !rule.expanded,
    })
  );

const movePrefix = (index: number, direction: -1 | 1) => {
  const target = index + direction;
  if (target < 0 || target >= prefixRules.value.length) return;
  const rules = prefixRules.value.map((rule) => ({ ...rule }));
  [rules[index], rules[target]] = [rules[target], rules[index]];
  void withPrefixError(() => listingPreferencesStore.setRules(rules));
};

const removePrefix = (prefix: string) => {
  if (listingPreferencesStore.isBuiltIn(prefix)) return;
  void withPrefixError(() =>
    listingPreferencesStore.setRules(
      prefixRules.value.filter((rule) => rule.prefix !== prefix)
    )
  );
};

const addPrefix = () => {
  const prefix = newPrefix.value;
  const validation = validatePrefix(prefix);
  if (validation) {
    prefixError.value = validation;
    return;
  }
  if (prefixRules.value.some((rule) => rule.prefix === prefix)) {
    prefixError.value = `前缀 ${prefix} 已存在`;
    return;
  }
  if (customRuleCount.value >= MAX_CUSTOM_PREFIX_RULES) {
    prefixError.value = `自定义前缀最多 ${MAX_CUSTOM_PREFIX_RULES} 个`;
    return;
  }
  void withPrefixError(async () => {
    await listingPreferencesStore.setRules([
      ...prefixRules.value,
      {
        prefix,
        visible: true,
        expanded: true,
        order: prefixRules.value.length,
      },
    ]);
    newPrefix.value = "";
  });
};
</script>

<style scoped>
/* PC dual column — left long form, right short modules. No dead bottom stretch. */
.profile-settings-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(300px, 0.9fr);
  gap: 16px;
  align-items: start;
}

.profile-settings-grid > .column {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.profile-settings-grid > .column > .card {
  height: auto;
}

@media (max-width: 960px) {
  .profile-settings-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.account-preferences {
  display: grid;
  gap: 8px;
}

.settings-section {
  display: grid;
  gap: 8px;
}

.settings-section h3 {
  margin: 8px 0 0;
}

/* Interaction prefs — flex row: checkbox | title+desc (same visual as 全局设置) */
.check-card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.check-card {
  display: flex;
  flex-direction: row;
  flex-wrap: nowrap;
  align-items: flex-start;
  gap: 12px;
  width: 100%;
  box-sizing: border-box;
  margin: 0;
  padding: 14px 16px;
  border: 1px solid var(--borderPrimary, #e5e7eb);
  border-radius: 10px;
  background: var(--surfacePrimary, #fff);
  cursor: pointer;
}

.check-card__input {
  flex: 0 0 18px;
  width: 18px;
  height: 18px;
  margin: 2px 0 0;
  padding: 0;
  appearance: none;
  -webkit-appearance: none;
  border: 2px solid var(--borderSecondary, #94a3b8);
  border-radius: 4px;
  background-color: var(--surfacePrimary, #fff);
  background-repeat: no-repeat;
  background-position: center;
  background-size: 12px 12px;
  cursor: pointer;
  position: relative;
  vertical-align: middle;
}

/* Single clean tick — SVG fill; kill any inherited text/border ::after check */
.dashboard .card-content input.check-card__input:checked,
.check-card__input:checked {
  background-color: var(--blue, #2979ff);
  border-color: var(--blue, #2979ff);
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none'%3E%3Cpath d='M3.2 8.3l3.1 3.2 6.5-7' stroke='%23ffffff' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
}

.dashboard .card-content input.check-card__input::after,
.dashboard .card-content input.check-card__input:checked::after,
.check-card__input::after,
.check-card__input:checked::after {
  content: none !important;
  display: none !important;
  width: 0 !important;
  height: 0 !important;
  border: 0 !important;
  background: none !important;
  box-shadow: none !important;
}

.check-card__copy {
  flex: 1 1 auto;
  min-width: 0;
  display: block;
}

.check-card__copy strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--textPrimary, #1f2937);
}

.check-card__copy small {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--textSecondary, #667085);
}

.setting-toggle-list {
  display: grid;
  gap: 0;
}

.setting-toggle-row {
  display: grid;
  gap: 8px 16px;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid var(--borderPrimary, #e5e7eb);
}

.setting-toggle-row:last-child {
  border-bottom: none;
}

/* Shared control column — right edges align across rows */
.setting-control-row {
  grid-template-columns: minmax(0, 1fr) 200px;
  cursor: default;
}

.setting-control-row--full {
  grid-template-columns: minmax(0, 1fr) 200px;
}

.setting-copy {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.setting-copy strong {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.45;
  color: var(--textPrimary, #1f2937);
}

.setting-copy small {
  color: var(--textSecondary, #667085);
  font-size: 12px;
  line-height: 1.55;
}

.setting-controls {
  display: block;
  width: 200px;
  min-width: 0;
}

.setting-controls :deep(.app-select),
.setting-controls :deep(.app-select__trigger) {
  width: 200px;
}

/* Same visual box as select: unit overlays inside the 200px control */
.setting-control-field {
  position: relative;
  width: 200px;
  height: 36px;
}

.setting-control-field .app-number {
  box-sizing: border-box;
  width: 200px;
  height: 36px;
  margin: 0;
  padding-right: 28px;
  color: var(--textPrimary, #111);
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--borderPrimary, #d0d5dd);
  border-radius: 8px;
  outline: none;
}

.setting-control-field .setting-unit {
  position: absolute;
  top: 50%;
  right: 10px;
  min-width: 14px;
  color: var(--textSecondary, #667085);
  font-size: 13px;
  line-height: 1;
  text-align: right;
  transform: translateY(-50%);
  pointer-events: none;
}

/* AppSelect is width-constrained via .setting-controls :deep(.app-select) */

.app-number {
  padding: 0 8px;
  font-size: 14px;
  color: var(--textPrimary, #111);
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--borderPrimary, #d0d5dd);
  border-radius: 8px;
  outline: none;
}

.app-number:focus {
  border-color: #2979ff;
}

.app-select {
  width: 100%;
  min-width: 0;
}

.password-fields {
  display: grid;
  gap: 10px;
}

.prefix-preferences {
  display: grid;
  gap: 12px;
  padding-top: 8px;
  margin-top: 4px;
  border-top: 1px solid var(--borderPrimary, #e5e7eb);
}

.prefix-preferences-heading {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}

.prefix-preferences-heading h3 {
  margin: 0;
}

.prefix-preferences-heading p {
  margin: 4px 0 0;
  color: var(--textSecondary, #667085);
  font-size: 12px;
}

.prefix-rule-list {
  display: grid;
  gap: 8px;
}

.prefix-rule-row {
  display: grid;
  grid-template-columns: 48px 56px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  border: 1px solid var(--borderPrimary, #e5e7eb);
  border-radius: 8px;
}

.prefix-rule-actions {
  display: flex;
  flex-wrap: nowrap;
  gap: 6px;
  justify-content: flex-end;
  align-items: center;
}

.prefix-state-button,
.prefix-icon-button {
  display: inline-flex;
  min-height: 32px;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 0 10px;
  border: 1px solid var(--borderPrimary, #d0d5dd);
  border-radius: 8px;
  color: var(--textSecondary, #667085);
  background: var(--surfacePrimary, #fff);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    color 0.15s ease,
    background 0.15s ease;
}

.prefix-icon-button {
  width: 32px;
  padding: 0;
}

.prefix-state-button:hover,
.prefix-icon-button:hover:not(:disabled) {
  color: var(--blue, #2979ff);
  border-color: color-mix(in srgb, var(--blue, #2979ff) 40%, transparent);
  background: color-mix(in srgb, var(--blue, #2979ff) 6%, transparent);
}

.prefix-state-button.active {
  color: var(--blue, #2979ff);
  border-color: color-mix(in srgb, var(--blue, #2979ff) 35%, transparent);
  background: color-mix(in srgb, var(--blue, #2979ff) 8%, transparent);
}

.prefix-icon-button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.prefix-icon-button.danger:hover:not(:disabled) {
  color: #b42318;
  border-color: rgba(180, 35, 24, 0.35);
  background: rgba(180, 35, 24, 0.06);
}

.prefix-add-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.prefix-add-row input {
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--borderPrimary, #d0d5dd);
  border-radius: 8px;
  color: var(--textPrimary, #111);
  background: var(--surfacePrimary, #fff);
}

.prefix-add-row .button {
  min-height: 36px;
  padding: 0 16px;
  border-radius: 8px;
  white-space: nowrap;
}

.prefix-error {
  margin: 0;
  color: #b42318;
  font-size: 12px;
}

@media (max-width: 720px) {
  .setting-control-row {
    grid-template-columns: minmax(0, 1fr);
  }

  .setting-controls {
    width: 100%;
  }

  .setting-toggle-list--checks {
    grid-template-columns: minmax(0, 1fr);
  }

  .prefix-rule-row {
    grid-template-columns: 40px 48px minmax(0, 1fr);
  }
}
</style>
