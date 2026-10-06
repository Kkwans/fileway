<template>
  <div
    class="tag-manager"
    role="dialog"
    aria-modal="true"
    aria-labelledby="tag-manager-title"
  >
    <header class="tag-manager-header">
      <div>
        <h3 id="tag-manager-title">管理标签</h3>
        <p>为标签选择容易辨认且不重复的颜色</p>
      </div>
      <button
        class="icon-button close-button"
        type="button"
        aria-label="关闭"
        title="关闭"
        @click="close"
      >
        <AppIcon name="x" :size="20" />
      </button>
    </header>

    <section class="tag-create" aria-label="创建标签">
      <div class="tag-create-controls">
        <input
          v-model="newTagName"
          class="tag-input"
          type="text"
          placeholder="输入标签名称"
          maxlength="20"
          @keyup.enter="createTag"
        />
        <button
          class="primary-button"
          type="button"
          :disabled="!canCreateTag"
          @click="createTag"
        >
          <AppIcon name="plus" :size="18" />
          创建标签
        </button>
      </div>

      <div class="color-grid" aria-label="标签颜色">
        <button
          v-for="color in TAG_COLORS"
          :key="color"
          class="color-swatch"
          :class="{
            selected: isSameColor(newTagColor, color),
            unavailable: !isColorAvailable(color),
          }"
          :style="{ '--swatch-color': color }"
          type="button"
          :disabled="!isColorAvailable(color)"
          :aria-label="colorLabel(color)"
          :aria-pressed="isSameColor(newTagColor, color)"
          @click="newTagColor = color"
        >
          <AppIcon
            v-if="isSameColor(newTagColor, color)"
            name="circle-check"
            :size="16"
          />
        </button>
        <label
          class="color-swatch custom-swatch"
          :class="{ selected: isCustomColorSelected(newTagColor) }"
          title="自定义颜色"
          aria-label="自定义颜色"
        >
          <span class="custom-swatch-center" aria-hidden="true">
            <AppIcon name="color-picker" :size="14" />
          </span>
          <input
            v-model="newTagColor"
            type="color"
            aria-label="选择自定义颜色"
          />
        </label>
      </div>
      <p v-if="createError" class="form-error" role="alert">
        {{ createError }}
      </p>
    </section>

    <section class="tag-list" aria-label="已有标签">
      <p v-if="tagsStore.sortedTags.length === 0" class="tag-empty">
        暂无标签，可以先创建一个标签
      </p>

      <article
        v-for="tag in tagsStore.sortedTags"
        :key="tag.id"
        class="tag-entry"
      >
        <div v-if="editingId !== tag.id" class="tag-row">
          <span
            class="tag-dot"
            :style="{ backgroundColor: tag.color }"
            aria-hidden="true"
          ></span>
          <span class="tag-name" :title="tag.name">{{ tag.name }}</span>
          <span class="tag-count" :aria-label="`${tag.paths.length} 个项目`">{{
            tag.paths.length
          }}</span>
          <button
            class="icon-button"
            type="button"
            aria-label="编辑标签"
            title="编辑标签"
            @click="startEdit(tag)"
          >
            <AppIcon name="rename" :size="18" />
          </button>
          <button
            class="icon-button danger-button"
            type="button"
            aria-label="删除标签"
            title="删除标签"
            @click="confirmDelete(tag)"
          >
            <AppIcon name="trash" :size="18" />
          </button>
        </div>

        <div v-else class="tag-edit-panel">
          <div class="tag-edit-controls">
            <input
              v-model="editName"
              class="tag-input"
              type="text"
              maxlength="20"
              aria-label="标签名称"
              @keyup.enter="saveEdit(tag.id)"
              @keyup.escape="cancelEdit"
            />
            <button
              class="icon-button save-button"
              type="button"
              aria-label="保存"
              title="保存"
              @click="saveEdit(tag.id)"
            >
              <AppIcon name="circle-check" :size="18" />
            </button>
            <button
              class="icon-button"
              type="button"
              aria-label="取消"
              title="取消"
              @click="cancelEdit"
            >
              <AppIcon name="x" :size="18" />
            </button>
          </div>

          <div class="color-grid edit-color-grid" aria-label="修改标签颜色">
            <button
              v-for="color in TAG_COLORS"
              :key="color"
              class="color-swatch small"
              :class="{
                selected: isSameColor(editColor, color),
                unavailable: !isColorAvailable(color, tag.id),
              }"
              :style="{ '--swatch-color': color }"
              type="button"
              :disabled="!isColorAvailable(color, tag.id)"
              :aria-label="colorLabel(color, tag.id)"
              :aria-pressed="isSameColor(editColor, color)"
              @click="editColor = color"
            >
              <AppIcon
                v-if="isSameColor(editColor, color)"
                name="circle-check"
                :size="14"
              />
            </button>
            <label
              class="color-swatch custom-swatch small"
              :class="{ selected: isCustomColorSelected(editColor) }"
              title="自定义颜色"
              aria-label="自定义颜色"
            >
              <span class="custom-swatch-center" aria-hidden="true">
                <AppIcon name="color-picker" :size="12" />
              </span>
              <input
                v-model="editColor"
                type="color"
                aria-label="选择自定义颜色"
              />
            </label>
          </div>
          <p v-if="editError" class="form-error" role="alert">
            {{ editError }}
          </p>
        </div>

        <div
          v-if="deleteTarget?.id === tag.id"
          class="delete-confirmation"
          role="alertdialog"
        >
          <div>
            <strong>删除“{{ tag.name }}”？</strong>
            <p>标签关联会一并移除，但不会删除文件。</p>
          </div>
          <div class="delete-actions">
            <button
              class="secondary-button"
              type="button"
              @click="deleteTarget = null"
            >
              取消
            </button>
            <button class="delete-button" type="button" @click="doDelete">
              确认删除
            </button>
          </div>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import { TAG_COLORS, type Tag, useTagsStore } from "@/stores/tags";
import { isTagColorAvailable, normalizeTagColor } from "@/utils/tagColors";

const emit = defineEmits<{ close: [] }>();
const tagsStore = useTagsStore();

const newTagName = ref("");
const newTagColor = ref(
  TAG_COLORS.find((color) => isTagColorAvailable(tagsStore.tags, color)) ??
    "#2E90FA"
);
const createError = ref("");

const editingId = ref<string | null>(null);
const editName = ref("");
const editColor = ref("");
const editError = ref("");
const deleteTarget = ref<Tag | null>(null);

const canCreateTag = computed(
  () =>
    newTagName.value.trim().length > 0 && isColorAvailable(newTagColor.value)
);

function isSameColor(first: string, second: string) {
  return normalizeTagColor(first) === normalizeTagColor(second);
}

function isColorAvailable(color: string, excludedTagId?: string) {
  return isTagColorAvailable(tagsStore.tags, color, excludedTagId);
}

function isCustomColorSelected(color: string) {
  return !TAG_COLORS.some((preset) => isSameColor(color, preset));
}

function colorLabel(color: string, excludedTagId?: string) {
  return isColorAvailable(color, excludedTagId)
    ? `选择颜色 ${color}`
    : `颜色 ${color} 已被其他标签使用`;
}

function selectNextAvailableColor() {
  const nextColor = TAG_COLORS.find((color) => isColorAvailable(color));
  if (nextColor) newTagColor.value = nextColor;
}

async function createTag() {
  if (!newTagName.value.trim()) return;
  createError.value = "";
  if (!isColorAvailable(newTagColor.value)) {
    createError.value = "该颜色已被其他标签使用，请选择其他颜色。";
    return;
  }
  const created = await tagsStore.createTag(
    newTagName.value,
    newTagColor.value
  );
  if (!created) {
    createError.value = "该颜色已被其他标签使用，请选择其他颜色。";
    return;
  }
  newTagName.value = "";
  selectNextAvailableColor();
}

function startEdit(tag: Tag) {
  deleteTarget.value = null;
  editingId.value = tag.id;
  editName.value = tag.name;
  editColor.value = tag.color;
  editError.value = "";
}

async function saveEdit(id: string) {
  if (!editName.value.trim()) {
    editError.value = "标签名称不能为空。";
    return;
  }
  if (!isColorAvailable(editColor.value, id)) {
    editError.value = "该颜色已被其他标签使用，请选择其他颜色。";
    return;
  }
  editError.value = "";
  const saved = await tagsStore.updateTag(id, {
    name: editName.value,
    color: editColor.value,
  });
  if (!saved) {
    editError.value = "保存失败，请稍后重试。";
    return;
  }
  editingId.value = null;
}

function cancelEdit() {
  editingId.value = null;
  editError.value = "";
}

function confirmDelete(tag: Tag) {
  editingId.value = null;
  deleteTarget.value = deleteTarget.value?.id === tag.id ? null : tag;
}

async function doDelete() {
  if (!deleteTarget.value) return;
  await tagsStore.deleteTag(deleteTarget.value.id);
  deleteTarget.value = null;
}

function close() {
  emit("close");
}
</script>

<style scoped>
.tag-manager {
  box-sizing: border-box;
  width: min(42rem, calc(100vw - 2rem));
  max-height: min(46rem, calc(100dvh - 2rem));
  padding: 1.5rem;
  overflow-y: auto;
  color: var(--textPrimary, #1e293b);
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--divider, #e2e8f0);
  border-radius: 1rem;
  box-shadow: 0 1.5rem 4rem rgba(15, 23, 42, 0.2);
}

.tag-manager-header,
.tag-create-controls,
.tag-row,
.tag-edit-controls,
.delete-confirmation,
.delete-actions {
  display: flex;
  align-items: center;
}

.tag-manager-header {
  position: static;
  z-index: auto;
  top: auto;
  left: auto;
  width: auto;
  height: auto;
  padding: 0;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1.25rem;
  border: 0;
  background: transparent;
  box-shadow: none;
  transition: none;
}

.tag-manager-header:hover {
  box-shadow: none;
}

.tag-manager-header h3,
.tag-manager-header p,
.delete-confirmation p {
  margin: 0;
}

.tag-manager-header h3 {
  font-size: 1.25rem;
  letter-spacing: -0.02em;
}

.tag-manager-header p {
  margin-top: 0.25rem;
  color: var(--textSecondary, #64748b);
  font-size: 0.8125rem;
}

.tag-create {
  margin-bottom: 1.25rem;
  padding: 1rem;
  background: var(--surfaceSecondary, #f8fafc);
  border: 1px solid var(--divider, #e2e8f0);
  border-radius: 0.75rem;
}

.tag-create-controls,
.tag-edit-controls {
  gap: 0.625rem;
}

.tag-input {
  box-sizing: border-box;
  min-width: 0;
  min-height: 2.75rem;
  flex: 1;
  padding: 0.625rem 0.75rem;
  color: var(--textPrimary, #1e293b);
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--divider, #cbd5e1);
  border-radius: 0.5rem;
  outline: none;
  font: inherit;
}

.tag-input:focus {
  border-color: #2e90fa;
  box-shadow: 0 0 0 3px rgba(46, 144, 250, 0.14);
}

.primary-button,
.secondary-button,
.delete-button,
.icon-button {
  border: 0;
  font: inherit;
  cursor: pointer;
}

.primary-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.25rem;
  min-height: 2.75rem;
  padding: 0.625rem 1rem;
  color: #fff;
  background: #1677ff;
  border-radius: 0.5rem;
  font-size: 0.9375rem;
  font-weight: 600;
  white-space: nowrap;
}

.primary-button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.primary-button .app-icon {
  width: 1.125rem;
  height: 1.125rem;
}

.color-grid {
  display: grid;
  grid-template-columns: repeat(9, minmax(2.25rem, 1fr));
  gap: 0.75rem 0.625rem;
  width: 100%;
  margin-top: 1rem;
}

.color-swatch {
  --swatch-color: transparent;
  position: relative;
  display: inline-grid;
  place-items: center;
  justify-self: center;
  box-sizing: border-box;
  width: 2.25rem;
  height: 2.25rem;
  padding: 0;
  color: #fff;
  background: var(--swatch-color);
  border: 2px solid transparent;
  border-radius: 50%;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.08);
  cursor: pointer;
  transition:
    transform 120ms ease,
    box-shadow 120ms ease,
    opacity 120ms ease;
}

.color-swatch:hover:not(:disabled) {
  transform: scale(1.08);
  box-shadow: 0 0 0 3px rgba(46, 144, 250, 0.18);
}

.color-swatch.selected {
  border-color: #fff;
  box-shadow: 0 0 0 2px #2e90fa;
}

.color-swatch.unavailable {
  cursor: not-allowed;
  opacity: 0.24;
}

.color-swatch .app-icon {
  width: 1rem;
  height: 1rem;
  text-shadow: 0 1px 2px rgba(15, 23, 42, 0.35);
}

.custom-swatch {
  position: relative;
  overflow: visible;
  color: var(--textSecondary, #64748b);
  background: conic-gradient(
    #e5484d,
    #f28c28,
    #d6be21,
    #35a867,
    #28afc0,
    #3f72d8,
    #7656c9,
    #c34f90,
    #e5484d
  );
  border: 0;
  box-shadow:
    inset 0 0 0 1px rgba(15, 23, 42, 0.08),
    0 0 0 1px rgba(15, 23, 42, 0.06);
}

.custom-swatch-center {
  position: absolute;
  inset: 4px;
  z-index: 1;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--surfacePrimary, #fff);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.16);
  pointer-events: none;
}

.custom-swatch-center .app-icon {
  color: var(--textSecondary, #64748b);
  width: 0.875rem;
  height: 0.875rem;
  text-shadow: none;
}

.custom-swatch.small .custom-swatch-center {
  inset: 3px;
}

.custom-swatch.small .custom-swatch-center .app-icon {
  width: 0.75rem;
  height: 0.75rem;
}

.custom-swatch > input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  padding: 0;
  opacity: 0;
  cursor: pointer;
}

.form-error {
  margin: 0.625rem 0 0;
  color: #d92d20;
  font-size: 0.8125rem;
}

.tag-list {
  display: grid;
  gap: 0.5rem;
}

.tag-empty {
  margin: 0;
  padding: 2rem 1rem;
  color: var(--textSecondary, #64748b);
  text-align: center;
}

.tag-entry {
  overflow: hidden;
  border: 1px solid var(--divider, #e2e8f0);
  border-radius: 0.75rem;
}

.tag-row {
  min-height: 3.5rem;
  gap: 0.625rem;
  padding: 0.375rem 0.5rem 0.375rem 0.875rem;
}

.tag-row:hover {
  background: var(--surfaceSecondary, #f8fafc);
}

.tag-dot {
  width: 0.75rem;
  height: 0.75rem;
  flex: 0 0 auto;
  border-radius: 50%;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.08);
}

.tag-name {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-count {
  min-width: 2rem;
  padding: 0.25rem 0.5rem;
  color: var(--textSecondary, #64748b);
  background: var(--surfaceSecondary, #f1f5f9);
  border-radius: 999px;
  font-size: 0.75rem;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.icon-button {
  display: inline-grid;
  place-items: center;
  width: 2.5rem;
  height: 2.5rem;
  flex: 0 0 auto;
  padding: 0;
  color: var(--textSecondary, #64748b);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 0.5rem;
}

.icon-button:hover {
  color: #1677ff;
  background: rgba(22, 119, 255, 0.08);
}

.close-button {
  border-color: var(--divider, #e2e8f0);
}

.danger-button:hover {
  color: #d92d20;
  background: #fef3f2;
}

.save-button {
  color: #079455;
  background: #ecfdf3;
}

.icon-button .app-icon {
  width: 1.25rem;
  height: 1.25rem;
}

.tag-edit-panel {
  padding: 0.75rem;
  background: var(--surfaceSecondary, #f8fafc);
}

.edit-color-grid {
  grid-template-columns: repeat(9, minmax(1.75rem, 1fr));
  gap: 0.5rem 0.375rem;
  margin-top: 0.75rem;
}

.color-swatch.small {
  width: 1.75rem;
  height: 1.75rem;
}

.color-swatch.small .app-icon {
  width: 0.8125rem;
  height: 0.8125rem;
}

.delete-confirmation {
  justify-content: space-between;
  gap: 1rem;
  padding: 0.75rem 0.875rem;
  background: #fff8f7;
  border-top: 1px solid #fecdca;
}

.delete-confirmation strong {
  font-size: 0.875rem;
}

.delete-confirmation p {
  margin-top: 0.125rem;
  color: var(--textSecondary, #64748b);
  font-size: 0.75rem;
}

.delete-actions {
  gap: 0.5rem;
  flex: 0 0 auto;
}

.secondary-button,
.delete-button {
  min-height: 2.25rem;
  padding: 0.375rem 0.75rem;
  border-radius: 0.5rem;
  font-size: 0.8125rem;
}

.secondary-button {
  color: var(--textPrimary, #1e293b);
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--divider, #d0d5dd);
}

.delete-button {
  color: #fff;
  background: #d92d20;
}

@media (max-width: 736px) {
  .tag-manager {
    width: 100%;
    max-height: 84dvh;
    padding: 1rem;
    border: 0;
    border-radius: 1rem 1rem 0 0;
  }

  .tag-create-controls {
    align-items: stretch;
    flex-direction: column;
  }

  .color-grid {
    grid-template-columns: repeat(6, 1fr);
  }

  .edit-color-grid {
    grid-template-columns: repeat(6, 1fr);
  }

  .delete-confirmation {
    align-items: stretch;
    flex-direction: column;
  }

  .delete-actions {
    justify-content: flex-end;
  }
}
</style>
