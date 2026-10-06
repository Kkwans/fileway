<template>
  <header
    ref="headerElement"
    class="app-header-bar"
    :class="{ 'app-header-bar--branded': showLogo }"
  >
    <div class="header-leading">
      <img v-if="showLogo" :src="logoURL" :alt="name" />
      <Action
        v-if="showMenu && isMobileViewport"
        class="menu-button"
        app-icon="menu"
        :icon-size="22"
        label="切换侧边栏"
        @action="layoutStore.toggleTransient('sidebar')"
      />
      <div v-if="showLogo" class="header-instance" aria-label="当前实例">
        <strong class="header-instance__name">{{ name }}</strong>
        <span
          v-if="authStore.isLoggedIn && authStore.instanceHostname"
          class="header-instance__host"
        >
          {{ authStore.instanceHostname }}
        </span>
      </div>
      <IconButton
        v-if="title && backPlacement === 'leading'"
        class="header-back header-back--leading"
        icon="arrow-left"
        :label="backLabel"
        @click="goBack"
      >
        <span class="header-back-label">{{ backLabel }}</span>
      </IconButton>
    </div>

    <div
      class="header-center"
      :class="{ 'header-center--page': Boolean(title) }"
    >
      <IconButton
        v-if="title && backPlacement !== 'leading'"
        class="header-back"
        icon="arrow-left"
        :label="backLabel"
        @click="goBack"
      />
      <PageTitle v-if="title" :title="title" :icon="titleIcon || 'info'" />
      <slot v-else />
    </div>

    <div class="header-trailing">
      <div v-if="hasPrimaryActions" class="header-primary-actions">
        <slot name="primary-actions" />
      </div>
      <div v-if="hasDirectActions" class="header-direct-actions">
        <slot name="actions" />
      </div>
      <div v-if="hasMobileActions" class="header-mobile-actions">
        <slot name="mobile-actions" />
      </div>
      <router-link
        v-if="authStore.isLoggedIn && showTaskCenter"
        class="header-task-center"
        to="/tasks"
        aria-label="任务中心"
        title="任务中心"
      >
        <AppIcon name="tasks" :size="20" :stroke-width="1.9" />
        <span
          v-if="taskCenterBadgeCount > 0"
          class="header-task-center__badge"
          :aria-label="`有 ${taskCenterBadgeCount} 项进行中的任务或传输`"
          >{{ taskCenterBadgeText }}</span
        >
      </router-link>
      <div
        v-if="hasOverflowActions"
        id="dropdown"
        ref="dropdownElement"
        @pointerdown.stop
        @click.stop="onDropdownClick"
        :class="{
          active: layoutStore.currentPromptName === 'more',
          'has-primary-actions': hasPrimaryActions,
        }"
      >
        <slot name="actions" v-if="hasPrimaryActions || isMobileViewport" />
      </div>

      <Action
        v-if="hasOverflowActions"
        id="more"
        app-icon="more"
        :icon-size="22"
        label="更多"
        :aria-expanded="layoutStore.currentPromptName === 'more'"
        aria-controls="dropdown"
        @action="layoutStore.toggleTransient('more')"
      />
    </div>

    <div
      class="overlay header-more-overlay"
      aria-hidden="true"
      v-show="layoutStore.currentPromptName == 'more'"
      @click.self="closeMore"
    />
  </header>
</template>

<script setup lang="ts">
import { useLayoutStore } from "@/stores/layout";
import { useAuthStore } from "@/stores/auth";
import { useTasksStore } from "@/stores/tasks";
import { useTransfersStore } from "@/stores/transfers";

import { logoURL, name } from "@/utils/constants";

import Action from "@/components/header/Action.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import PageTitle from "./PageTitle.vue";
import IconButton from "@/components/ui/IconButton.vue";
import { useNavigationStore } from "@/stores/navigation";
import type { AppIconName } from "@/components/ui/iconRegistry";
import {
  Comment,
  Fragment,
  Text,
  computed,
  onMounted,
  onUnmounted,
  ref,
  useSlots,
  watch,
  type VNode,
} from "vue";
import { useRoute, useRouter } from "vue-router";
withDefaults(
  defineProps<{
    showLogo?: boolean;
    showMenu?: boolean;
    showTaskCenter?: boolean;
    title?: string;
    titleIcon?: AppIconName;
    backPlacement?: "center" | "leading";
    backLabel?: string;
  }>(),
  {
    backPlacement: "leading",
    backLabel: "返回",
  }
);

const layoutStore = useLayoutStore();
const authStore = useAuthStore();
const tasksStore = useTasksStore();
const transfersStore = useTransfersStore();
const slots = useSlots();
const route = useRoute();
const router = useRouter();
const navigation = useNavigationStore();
function goBack() {
  const target = navigation.returnEntry;
  navigation.prepareReturn(target.path);
  const current = navigation.trail.at(-1);
  const position = window.history.state?.position;
  if (
    target.position !== null &&
    Number.isSafeInteger(position) &&
    target.position < position &&
    current &&
    current.position === position &&
    current.path === route.fullPath
  ) {
    router.go(target.position - position);
  } else {
    void router.replace(target.path);
  }
}
const headerElement = ref<HTMLElement | null>(null);
const dropdownElement = ref<HTMLElement | null>(null);
const closeMore = () => layoutStore.closeTransient("more");

/** Empty slot functions are common when pages compose a shared header. Vue
 * wraps conditional templates in fragments, so inspect descendants instead
 * of treating the fragment symbol itself as rendered content. */
const slotHasContent = (
  name: "actions" | "mobile-actions" | "primary-actions"
) => {
  const slot = slots[name];
  if (!slot) return false;
  const hasVNodeContent = (node: VNode): boolean => {
    if (node.type === Comment || node.type === Text) return false;
    if (node.type === Fragment) {
      return (node.children as VNode[]).some(
        (child) => typeof child !== "string" && hasVNodeContent(child)
      );
    }
    return true;
  };
  try {
    return slot().some(hasVNodeContent);
  } catch {
    return false;
  }
};

const hasPrimaryActions = computed(() => slotHasContent("primary-actions"));
const hasActions = computed(() => slotHasContent("actions"));
const hasMobileActions = computed(() => slotHasContent("mobile-actions"));

// A page with only ordinary actions keeps them visible on desktop. File
// listings provide a primary group as well, so their secondary actions stay
// behind the More control. Mobile always uses the overflow surface.
const hasDirectActions = computed(
  () => hasActions.value && !hasPrimaryActions.value && !isMobileViewport.value
);

const hasOverflowActions = computed(
  () =>
    (hasPrimaryActions.value && hasActions.value) ||
    (isMobileViewport.value && (hasActions.value || hasMobileActions.value))
);

function onDropdownClick(event: MouseEvent) {
  const target = event.target;
  // View/sort controls own a nested menu. Do not close More before their
  // toggle receives the click; option buttons use stopPropagation themselves.
  if (
    target instanceof Element &&
    target.closest(".view-mode-dropdown > .action, .sort-dropdown > .action")
  )
    return;
  closeMore();
}

const moreButton = () =>
  headerElement.value?.querySelector<HTMLElement>("#more");

function onOutsideInteraction(event: Event) {
  if (layoutStore.currentPromptName !== "more") return;
  const target = event.target;
  if (
    target instanceof Node &&
    !dropdownElement.value?.contains(target) &&
    !moreButton()?.contains(target)
  )
    closeMore();
}

function onMenuKeydown(event: KeyboardEvent) {
  if (event.key !== "Escape" || layoutStore.currentPromptName !== "more")
    return;
  event.preventDefault();
  event.stopPropagation();
  closeMore();
  moreButton()?.focus();
}

watch(() => route.fullPath, closeMore);
const taskCenterBadgeCount = computed(
  () => tasksStore.counts.active + transfersStore.active.length
);
const taskCenterBadgeText = computed(() =>
  taskCenterBadgeCount.value > 99 ? "99+" : String(taskCenterBadgeCount.value)
);

// The desktop header must not render the transient sidebar toggle at all.
// Keeping this contract in the component (instead of relying on a cascade of
// media-query overrides) prevents the button from becoming visible when an
// older page stylesheet wins the cascade.
const isMobileViewport = ref(
  typeof window !== "undefined" &&
    window.matchMedia("(max-width: 899px)").matches
);
let mobileMediaQuery: MediaQueryList | undefined;
const updateMobileViewport = (event?: MediaQueryListEvent) => {
  isMobileViewport.value =
    event?.matches ?? mobileMediaQuery?.matches ?? isMobileViewport.value;
};

onMounted(() => {
  document.addEventListener("pointerdown", onOutsideInteraction);
  document.addEventListener("focusin", onOutsideInteraction);
  document.addEventListener("keydown", onMenuKeydown, true);
  window.addEventListener("blur", closeMore);
  mobileMediaQuery = window.matchMedia("(max-width: 899px)");
  updateMobileViewport();
  if (mobileMediaQuery.addEventListener) {
    mobileMediaQuery.addEventListener("change", updateMobileViewport);
  } else {
    mobileMediaQuery.addListener(updateMobileViewport);
  }
});

onUnmounted(() => {
  document.removeEventListener("pointerdown", onOutsideInteraction);
  document.removeEventListener("focusin", onOutsideInteraction);
  document.removeEventListener("keydown", onMenuKeydown, true);
  window.removeEventListener("blur", closeMore);
  closeMore();
  if (mobileMediaQuery) {
    if (mobileMediaQuery.removeEventListener) {
      mobileMediaQuery.removeEventListener("change", updateMobileViewport);
    } else {
      mobileMediaQuery.removeListener(updateMobileViewport);
    }
  }
});
</script>
