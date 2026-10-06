<template>
  <div class="card floating">
    <div class="card-title">
      <h2>上传</h2>
    </div>

    <div class="card-action full upload-choice-grid">
      <button
        @click="uploadFile"
        type="button"
        class="action upload-choice"
        id="focus-prompt"
        aria-label="上传文件"
      >
        <span class="upload-choice-icon-frame" aria-hidden="true">
          <AppIcon
            name="file"
            class="upload-choice-icon"
            :size="56"
            :stroke-width="1.65"
          />
        </span>
        <div class="title">文件</div>
      </button>
      <button
        @click="uploadFolder"
        type="button"
        class="action upload-choice"
        aria-label="上传文件夹"
      >
        <span class="upload-choice-icon-frame" aria-hidden="true">
          <AppIcon
            name="folder"
            class="upload-choice-icon"
            :size="56"
            :stroke-width="1.65"
          />
        </span>
        <div class="title">文件夹</div>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from "vue-router";
import { useLayoutStore } from "@/stores/layout";
import AppIcon from "@/components/ui/AppIcon.vue";
import * as upload from "@/utils/upload";
const route = useRoute();

const layoutStore = useLayoutStore();

const openUpload = (isFolder: boolean) => {
  const input = document.createElement("input");
  input.type = "file";
  input.multiple = true;
  input.webkitdirectory = isFolder;
  input.onchange = (event: Event) => {
    upload.processFileInput(event, route.path, layoutStore);
  };
  input.click();
};

const uploadFile = () => {
  openUpload(false);
};
const uploadFolder = () => {
  openUpload(true);
};
</script>

<style scoped>
.card .card-action.full.upload-choice-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.75rem;
  padding: 0 1rem 1rem;
}

.card .card-action.full .upload-choice {
  display: grid;
  min-width: 0;
  min-height: 9.5rem;
  align-content: center;
  justify-items: center;
  gap: 0.75rem;
  margin: 0;
  padding: 1.25rem 0.75rem;
  border-radius: 0.75rem;
  color: var(--textSecondary);
  background: var(--surfacePrimary);
  cursor: pointer;
  font: inherit;
  text-align: center;
  transition:
    border-color 180ms ease,
    color 180ms ease,
    background-color 180ms ease,
    transform 180ms ease;
}

.card .card-action.full .upload-choice:hover,
.card .card-action.full .upload-choice:focus-visible {
  border-color: color-mix(in srgb, var(--blue) 42%, var(--borderPrimary));
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 5%, var(--surfacePrimary));
  outline: none;
}

.card .card-action.full .upload-choice:active {
  transform: translateY(1px);
}

.upload-choice-icon-frame {
  display: grid;
  width: 4.5rem;
  height: 4.5rem;
  place-items: center;
  overflow: visible;
  color: currentColor;
}

.upload-choice-icon {
  display: block;
  margin: 0;
  overflow: visible;
  border-radius: 0;
}

.card .card-action.full .upload-choice .title {
  min-width: 0;
  color: inherit;
  font-size: 1.25rem;
  font-weight: 650;
  line-height: 1.2;
  white-space: nowrap;
}
</style>
