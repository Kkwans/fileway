<template>
  <IconButton
    class="action"
    :class="{ 'action--with-label': Boolean(label) }"
    :icon="resolvedIconName"
    :icon-size="iconSize"
    :label="label"
    :counter="counter"
    :active="active"
    :pressed="active"
    :disabled="disabled"
    @click="action"
  >
    <span v-if="label" class="action-label">{{ label }}</span>
  </IconButton>
</template>

<script setup lang="ts">
import { useLayoutStore } from "@/stores/layout";
import IconButton from "@/components/ui/IconButton.vue";
import {
  resolveLegacyAppIcon,
  type AppIconName,
} from "@/components/ui/iconRegistry";
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    icon?: string;
    appIcon?: AppIconName;
    iconSize?: number;
    label?: string;
    counter?: number;
    active?: boolean;
    show?: string;
    disabled?: boolean;
  }>(),
  { iconSize: 20 }
);

const emit = defineEmits<{
  (e: "action"): void;
}>();

const layoutStore = useLayoutStore();
const resolvedIconName = computed(
  () => props.appIcon ?? resolveLegacyAppIcon(props.icon)
);

const action = () => {
  if (props.disabled) return;
  if (props.show) {
    layoutStore.showHover(props.show);
  }

  emit("action");
};
</script>
