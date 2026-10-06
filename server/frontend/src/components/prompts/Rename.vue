<template>
  <div
    class="card floating rename-card"
    :class="{ 'batch-rename-card': isBatchRename }"
  >
    <div class="card-title rename-title">
      <div>
        <h2>{{ isBatchRename ? "批量重命名" : "重命名" }}</h2>
        <p v-if="isBatchRename">
          已选择 {{ selectedCount }} 项，最多支持 500 项
        </p>
      </div>
      <span v-if="isBatchRename" class="rename-count" aria-hidden="true">
        {{ changedCount }} 项待修改
      </span>
    </div>

    <div v-if="!isBatchRename" class="card-content">
      <p>
        请输入新名称，旧名称为： <code>{{ oldName }}</code
        >:
      </p>
      <input
        id="focus-prompt"
        v-model.trim="name"
        class="input input--block"
        type="text"
        autocomplete="off"
        @keyup.enter="submitSingle"
      />
    </div>

    <div v-else class="card-content batch-rename-content">
      <div
        v-if="selectedCount > 500"
        class="rename-banner rename-banner--error"
      >
        一次最多重命名 500 项，请减少选择后重试。
      </div>

      <section class="rename-rule" aria-labelledby="batch-rename-rule-title">
        <div class="rename-section-heading">
          <div>
            <h3 id="batch-rename-rule-title">命名规则</h3>
            <p>应用规则后仍可逐项调整目标名称。</p>
          </div>
          <select v-model="ruleType" aria-label="批量重命名规则">
            <option value="replace">查找并替换</option>
            <option value="prefix">添加前缀</option>
            <option value="suffix">添加后缀</option>
            <option value="number">连续编号</option>
          </select>
        </div>

        <div class="rename-rule-fields">
          <template v-if="ruleType === 'replace'">
            <label>
              <span>查找</span>
              <input v-model="replaceSearch" type="text" autocomplete="off" />
            </label>
            <label>
              <span>替换为</span>
              <input
                v-model="replaceValue"
                type="text"
                autocomplete="off"
                placeholder="留空表示删除"
              />
            </label>
          </template>
          <label v-else-if="ruleType === 'prefix'">
            <span>前缀</span>
            <input v-model="prefixValue" type="text" autocomplete="off" />
          </label>
          <label v-else-if="ruleType === 'suffix'">
            <span>后缀</span>
            <input v-model="suffixValue" type="text" autocomplete="off" />
          </label>
          <template v-else>
            <label class="rename-rule-base">
              <span>基础名称</span>
              <input v-model="numberBase" type="text" autocomplete="off" />
            </label>
            <label class="rename-rule-number">
              <span>起始</span>
              <input v-model.number="numberStart" type="number" min="0" />
            </label>
            <label class="rename-rule-number">
              <span>位数</span>
              <input
                v-model.number="numberPadding"
                type="number"
                min="1"
                max="8"
              />
            </label>
            <label class="rename-checkbox">
              <input v-model="preserveExtension" type="checkbox" />
              <span>保留扩展名</span>
            </label>
          </template>
          <button
            type="button"
            class="button button--flat rename-apply-rule"
            :disabled="!canApplyRule"
            @click="applyRule"
          >
            应用规则
          </button>
        </div>
      </section>

      <section
        class="rename-preview"
        aria-labelledby="batch-rename-preview-title"
      >
        <div class="rename-section-heading">
          <div>
            <h3 id="batch-rename-preview-title">变更预览</h3>
            <p>未修改的项目会跳过；提交前会再次检查磁盘冲突。</p>
          </div>
          <span
            v-if="preflightPassed"
            class="rename-check-state rename-check-state--success"
          >
            <AppIcon name="circle-check" :size="16" aria-hidden="true" />
            检查通过
          </span>
        </div>

        <div class="rename-preview-list" role="list">
          <div
            v-for="(draft, index) in drafts"
            :key="draft.sourcePath"
            class="rename-preview-row"
            :class="{
              'rename-preview-row--error': rowError(index),
              'rename-preview-row--unchanged': draft.newName === draft.oldName,
            }"
            role="listitem"
          >
            <span class="rename-row-index">{{ index + 1 }}</span>
            <div class="rename-source" :title="draft.oldName">
              <AppIcon
                :name="draft.isDir ? 'folder' : 'file'"
                :size="18"
                aria-hidden="true"
              />
              <span>{{ draft.oldName }}</span>
            </div>
            <AppIcon
              class="rename-arrow"
              name="arrow-right"
              :size="17"
              aria-hidden="true"
            />
            <label class="rename-target">
              <span class="sr-only">第 {{ index + 1 }} 项的新名称</span>
              <input
                v-model="draft.newName"
                type="text"
                autocomplete="off"
                :aria-invalid="Boolean(rowError(index))"
                :aria-describedby="
                  rowError(index) ? `rename-error-${index}` : undefined
                "
              />
              <small
                v-if="rowError(index)"
                :id="`rename-error-${index}`"
                class="rename-row-error"
              >
                {{ rowError(index) }}
              </small>
              <small
                v-else-if="draft.newName === draft.oldName"
                class="rename-row-skip"
              >
                跳过
              </small>
            </label>
          </div>
        </div>
      </section>

      <div
        v-if="batchError"
        class="rename-banner rename-banner--error"
        role="alert"
      >
        {{ batchError }}
      </div>
    </div>

    <div class="card-action">
      <button
        class="button button--flat button--grey"
        type="button"
        aria-label="取消"
        title="取消"
        @click="closeHovers"
      >
        取消
      </button>
      <button
        v-if="!isBatchRename"
        class="button button--flat"
        type="submit"
        aria-label="重命名"
        title="重命名"
        :disabled="name === '' || name === oldName || submitting"
        @click="submitSingle"
      >
        {{ submitting ? "处理中…" : "重命名" }}
      </button>
      <button
        v-else
        id="focus-prompt"
        class="button button--flat"
        type="button"
        :disabled="!canSubmitBatch"
        @click="submitBatch"
      >
        {{ batchActionLabel }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import AppIcon from "@/components/ui/AppIcon.vue";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import url from "@/utils/url";
import { files as api } from "@/api";
import {
  applyBatchRenameRule,
  validateBatchRenameDrafts,
  type BatchRenameDraft,
  type BatchRenameChange,
  type BatchRenameRule,
} from "@/utils/batchRename";

const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<IToastSuccess>("$showSuccess")!;
const router = useRouter();
const fileStore = useFileStore();
const layoutStore = useLayoutStore();
const { closeHovers, showHover } = layoutStore;
const { req, selectedItems, selectedCount, isListing } = storeToRefs(fileStore);
const { reload, preselect } = storeToRefs(fileStore);

const oldName = computed(() => {
  if (!isListing.value) return req.value!.name;
  if (selectedCount.value !== 1) return "";
  return selectedItems.value[0].name;
});
const isBatchRename = computed(
  () => isListing.value && selectedCount.value > 1
);
const selectionFingerprint = computed(() =>
  isListing.value
    ? selectedItems.value.map((item) => `${item.path}\0${item.name}`).join("\0")
    : `${req.value?.path ?? ""}\0${req.value?.name ?? ""}`
);

const name = ref("");
const drafts = ref<BatchRenameDraft[]>([]);
const ruleType = ref<BatchRenameRule["type"]>("replace");
const replaceSearch = ref("");
const replaceValue = ref("");
const prefixValue = ref("");
const suffixValue = ref("");
const numberBase = ref("文件-");
const numberStart = ref(1);
const numberPadding = ref(2);
const preserveExtension = ref(true);
const submitting = ref(false);
const batchError = ref("");
const serverErrors = ref(new Map<string, string>());
const preflightSignature = ref("");

function resetState() {
  name.value = oldName.value;
  drafts.value = selectedItems.value.map((item) => ({
    sourcePath: item.path,
    oldName: item.name,
    newName: item.name,
    isDir: item.isDir,
  }));
  batchError.value = "";
  serverErrors.value = new Map();
  preflightSignature.value = "";
  submitting.value = false;
}

watch(selectionFingerprint, resetState, { immediate: true });

const validation = computed(() => validateBatchRenameDrafts(drafts.value));
const changedCount = computed(() => validation.value.changes.length);
const changeSignature = computed(() =>
  JSON.stringify(validation.value.changes)
);
const preflightPassed = computed(
  () =>
    preflightSignature.value !== "" &&
    preflightSignature.value === changeSignature.value &&
    validation.value.errors.size === 0
);
watch(changeSignature, () => {
  batchError.value = "";
  serverErrors.value = new Map();
  preflightSignature.value = "";
});
const canApplyRule = computed(() => {
  if (ruleType.value === "replace") return replaceSearch.value !== "";
  if (ruleType.value === "prefix") return prefixValue.value !== "";
  if (ruleType.value === "suffix") return suffixValue.value !== "";
  return numberBase.value !== "" && numberPadding.value >= 1;
});
const canSubmitBatch = computed(
  () =>
    selectedCount.value <= 500 &&
    changedCount.value > 0 &&
    validation.value.errors.size === 0 &&
    !submitting.value
);
const batchActionLabel = computed(() => {
  if (submitting.value) return preflightPassed.value ? "重命名中…" : "检查中…";
  return preflightPassed.value
    ? `确认重命名 ${changedCount.value} 项`
    : "检查变更";
});

function rowError(index: number) {
  const draft = drafts.value[index];
  return (
    validation.value.errors.get(index) ||
    serverErrors.value.get(`${draft?.sourcePath}\0${draft?.newName}`) ||
    ""
  );
}

function currentRule(): BatchRenameRule {
  if (ruleType.value === "replace") {
    return {
      type: "replace",
      search: replaceSearch.value,
      replacement: replaceValue.value,
    };
  }
  if (ruleType.value === "prefix") {
    return { type: "prefix", value: prefixValue.value };
  }
  if (ruleType.value === "suffix") {
    return { type: "suffix", value: suffixValue.value };
  }
  return {
    type: "number",
    base: numberBase.value,
    start: Number.isFinite(numberStart.value) ? numberStart.value : 1,
    padding: Math.min(8, Math.max(1, numberPadding.value || 1)),
    preserveExtension: preserveExtension.value,
  };
}

function applyRule() {
  if (!canApplyRule.value) return;
  drafts.value = applyBatchRenameRule(drafts.value, currentRule());
  batchError.value = "";
  serverErrors.value = new Map();
  preflightSignature.value = "";
}

async function submitSingle() {
  if (name.value === "" || name.value === oldName.value || submitting.value)
    return;
  const item = isListing.value ? selectedItems.value[0] : req.value!;
  if (item?.path) {
    const risk = item.riskLevel ?? "low";
    if (risk === "high" || risk === "medium") {
      showHover({
        prompt: "risk-confirm",
        props: {
          riskLevel: risk,
          targetPath: item.path,
          actionType: "rename",
          onconfirm: executeSingleRename,
        },
      });
      return;
    }
  }
  await executeSingleRename();
}

async function executeSingleRename() {
  if (name.value === "" || name.value === oldName.value || submitting.value)
    return;
  submitting.value = true;
  const oldLink = isListing.value ? selectedItems.value[0].url : req.value!.url;
  const newLink = url.appendResourceRouteSegment(
    url.removeLastDir(oldLink),
    name.value
  );
  try {
    await api.move([{ from: oldLink, to: newLink }]);
    if (!isListing.value) {
      await router.push({ path: newLink });
      closeHovers();
      return;
    }
    preselect.value = url.canonicalResourcePath(newLink);
    reload.value = true;
    closeHovers();
  } catch (error) {
    $showError(error instanceof Error ? error : String(error));
  } finally {
    submitting.value = false;
  }
}

async function submitBatch() {
  if (!canSubmitBatch.value) return;
  if (!preflightPassed.value) {
    await preflightBatch();
    return;
  }
  const changes = validation.value.changes.map((item) => ({ ...item }));
  const risky = changes
    .map((change) =>
      selectedItems.value.find((item) => item.path === change.from)
    )
    .filter((item) => Boolean(item?.path))
    .map((item) => ({
      item: item!,
      risk: item!.riskLevel ?? "low",
    }))
    .find(({ risk }) => risk === "high" || risk === "medium");
  if (risky) {
    showHover({
      prompt: "risk-confirm",
      props: {
        riskLevel: risky.risk,
        targetPath: `已选择 ${changes.length} 项（含 ${risky.item.path}）`,
        actionType: "rename",
        onconfirm: () => executeBatchRename(changes),
      },
    });
    return;
  }
  await executeBatchRename(changes);
}

async function preflightBatch() {
  submitting.value = true;
  batchError.value = "";
  serverErrors.value = new Map();
  try {
    const result = await api.batchRename(validation.value.changes, true);
    const errors = new Map<string, string>();
    result.items.forEach((item) => {
      const targetName = item.to.split("/").at(-1) || "";
      if (item.error) errors.set(`${item.from}\0${targetName}`, item.error);
    });
    serverErrors.value = errors;
    if (!result.valid) {
      batchError.value = result.error || "存在名称冲突，请调整后重新检查。";
      return;
    }
    preflightSignature.value = changeSignature.value;
  } catch (error) {
    batchError.value = "无法完成变更检查，请确认网络和权限后重试。";
    $showError(error instanceof Error ? error : String(error));
  } finally {
    submitting.value = false;
  }
}

async function executeBatchRename(changes: BatchRenameChange[]) {
  if (submitting.value) return;
  submitting.value = true;
  batchError.value = "";
  try {
    const result = await api.batchRename(changes, false);
    if (!result.executed) {
      batchError.value = result.error || "批量重命名未执行。";
      return;
    }
    preselect.value = result.items[0]?.to || "";
    fileStore.clearSelection();
    reload.value = true;
    $showSuccess(`已重命名 ${result.items.length} 项`);
    closeHovers();
  } catch (error) {
    batchError.value = "文件状态可能已变化，请重新检查名称后再试。";
    preflightSignature.value = "";
    $showError(error instanceof Error ? error : String(error));
  } finally {
    submitting.value = false;
  }
}
</script>
