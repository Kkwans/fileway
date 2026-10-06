<template>
  <component
    v-if="directModal"
    :is="modal"
    v-bind="layoutStore.currentPrompt?.props || {}"
    @close="close"
  />
  <base-modal
    v-else-if="modal != null"
    :prompt="currentPromptName || undefined"
    @closed="close"
  >
    <keep-alive>
      <component
        :is="modal"
        v-bind="layoutStore.currentPrompt?.props || {}"
        @close="close"
      />
    </keep-alive>
  </base-modal>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { storeToRefs } from "pinia";
import { useLayoutStore } from "@/stores/layout";

import BaseModal from "./BaseModal.vue";
import Help from "./Help.vue";
import Info from "./Info.vue";
import Delete from "./Delete.vue";
import DeleteUser from "./DeleteUser.vue";
import Download from "./Download.vue";
import Rename from "./Rename.vue";
import Move from "./Move.vue";
import Copy from "./Copy.vue";
import NewFile from "./NewFile.vue";
import NewDir from "./NewDir.vue";
import Replace from "./Replace.vue";
import Share from "./Share.vue";
import ShareDelete from "./ShareDelete.vue";
import Upload from "./Upload.vue";
import DiscardEditorChanges from "./DiscardEditorChanges.vue";
import ResolveConflict from "./ResolveConflict.vue";
import CurrentPassword from "./CurrentPassword.vue";
import RiskConfirm from "./RiskConfirm.vue";
import TagManager from "@/components/TagManager.vue";
import QuickPreview from "./QuickPreview.vue";
import ResultAction from "./ResultAction.vue";

const layoutStore = useLayoutStore();

const { currentPromptName } = storeToRefs(layoutStore);

const components = new Map<string, any>([
  ["info", Info],
  ["help", Help],
  ["delete", Delete],
  ["rename", Rename],
  ["move", Move],
  ["copy", Copy],
  ["newFile", NewFile],
  ["newDir", NewDir],
  ["download", Download],
  ["replace", Replace],
  ["share", Share],
  ["upload", Upload],
  ["share-delete", ShareDelete],
  ["deleteUser", DeleteUser],
  ["discardEditorChanges", DiscardEditorChanges],
  ["resolve-conflict", ResolveConflict],
  ["current-password", CurrentPassword],
  ["risk-confirm", RiskConfirm],
  ["tag-manager", TagManager],
  ["quick-preview", QuickPreview],
  ["result-action", ResultAction],
]);

const modal = computed(() => {
  const modal = components.get(currentPromptName.value!);
  if (!modal) return null;
  return modal;
});

const directModal = computed(
  () =>
    currentPromptName.value === "delete" ||
    currentPromptName.value === "copy" ||
    currentPromptName.value === "move" ||
    (currentPromptName.value === "result-action" &&
      layoutStore.currentPrompt?.props?.mode !== "info")
);

const close = () => {
  if (!layoutStore.currentPrompt) return;
  layoutStore.closeHovers();
};
</script>
