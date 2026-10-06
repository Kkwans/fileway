import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const analysisSource = readFileSync(
  fileURLToPath(new URL("../../views/Analysis.vue", import.meta.url)),
  "utf8"
);
const scopePanelSource = readFileSync(
  fileURLToPath(
    new URL("../../components/analysis/AnalysisScopePanel.vue", import.meta.url)
  ),
  "utf8"
);
const recentScansSource = readFileSync(
  fileURLToPath(
    new URL(
      "../../components/analysis/AnalysisRecentScans.vue",
      import.meta.url
    )
  ),
  "utf8"
);
const cleanupPanelSource = readFileSync(
  fileURLToPath(
    new URL(
      "../../components/analysis/DuplicateCleanupPanel.vue",
      import.meta.url
    )
  ),
  "utf8"
);

describe("存储工具无障碍契约", () => {
  it("扫描范围移除按钮保留 44px 触摸目标", () => {
    expect(scopePanelSource).toMatch(
      /\.analysis-run-panel__scopes button\s*\{[\s\S]*?width:\s*44px;[\s\S]*?height:\s*44px;/
    );
  });

  it("路径选择器作为主入口，扫描范围不预选根目录", () => {
    expect(analysisSource).toContain("<AnalysisToolSwitcher");
    expect(analysisSource).toContain("<AnalysisScopePanel");
    expect(analysisSource).toContain('mode="both"');
    expect(analysisSource).toContain("multiple");
    expect(analysisSource).toContain('interaction-mode="analysis"');
    expect(analysisSource).toContain(':model-value="[]"');
    expect(scopePanelSource).toContain("analysis-run-panel__summary");
    expect(scopePanelSource).not.toContain("analysis-run-panel__advanced");
    expect(scopePanelSource).not.toContain("粘贴路径");
    expect(scopePanelSource).not.toContain("步骤 1");
    expect(scopePanelSource).not.toContain("步骤 2");
    expect(analysisSource).toContain('class="analysis-results-heading__icon"');
    expect(analysisSource).toContain('name="scan" :size="20"');
    expect(analysisSource).not.toContain('class="analysis-hero"');
  });

  it("报告页把再次扫描收纳为可展开入口", () => {
    expect(analysisSource).toContain('class="analysis-run-toggle"');
    expect(analysisSource).toContain(':aria-expanded="showRunPanel"');
    expect(analysisSource).toContain(
      'v-if="!isResultDetail && (!hasReport || showRunPanel)"'
    );
    expect(analysisSource).toContain('class="analysis-task-back"');
    expect(analysisSource).toContain('v-else-if="isResultDetail"');
  });

  it("分析报告卡片不复用会继承全局定位规则的原生 header", () => {
    expect(analysisSource).toContain('class="storage-ranking-header"');
    expect(cleanupPanelSource).toContain('class="cleanup-group__header"');
    expect(analysisSource).not.toMatch(/<header(?:\s|>)/);
    expect(cleanupPanelSource).not.toMatch(/<header(?:\s|>)/);
  });

  it("重复清理的保留选择与弹窗操作保留完整触摸目标", () => {
    expect(cleanupPanelSource).toContain('class="cleanup-file-choice"');
    expect(cleanupPanelSource).toMatch(
      /\.cleanup-file-choice\s*\{[\s\S]*?height:\s*44px;/
    );
    expect(cleanupPanelSource).toContain("<AppDialog");
    expect(cleanupPanelSource).toContain("移入回收站不会立即释放磁盘空间");
  });

  it("扫描面板使用单个表单，不嵌套表单或借用全局 header", () => {
    expect(scopePanelSource.match(/<form(?:\s|>)/g)).toHaveLength(1);
    expect(scopePanelSource).not.toMatch(/<header(?:\s|>)/);
    expect(scopePanelSource).toContain(`@submit.prevent="$emit('start')"`);
  });

  it("扫描动作紧随范围，不保留右侧启动栏与重复准备提示", () => {
    expect(scopePanelSource).toContain('class="analysis-run-panel__footer"');
    expect(scopePanelSource).not.toContain("border-left:");
    expect(scopePanelSource).not.toContain("准备就绪后开始一次扫描。");
    expect(scopePanelSource).toContain("扫描只读，不会删除文件。");
  });

  it("扫描范围双栏卡片共享同一行高，工具切换器保持紧凑居中", () => {
    const toolSwitcherSource = readFileSync(
      fileURLToPath(
        new URL(
          "../../components/analysis/AnalysisToolSwitcher.vue",
          import.meta.url
        )
      ),
      "utf8"
    );

    expect(scopePanelSource).toMatch(
      /\.analysis-run-panel__body\s*\{[\s\S]*?align-items:\s*stretch;/
    );
    expect(scopePanelSource).toMatch(
      /\.analysis-run-panel__selection,[\s\S]*?\.analysis-run-panel__summary\s*\{[\s\S]*?height:\s*100%;/
    );
    expect(analysisSource).toMatch(
      /\.analysis-workspace__topline\s*\{[\s\S]*?justify-content:\s*center;/
    );
    expect(toolSwitcherSource).toContain("margin-inline: auto;");
  });

  it("分析模式单击选择、双击进入，并提供键盘与当前目录入口", () => {
    const pickerSource = readFileSync(
      fileURLToPath(
        new URL("../../components/prompts/PathPicker.vue", import.meta.url)
      ),
      "utf8"
    );

    expect(pickerSource).toContain('interactionMode?: "default" | "analysis"');
    expect(pickerSource).toContain("handleEntryDoubleClick");
    expect(pickerSource).toContain("@keydown.space.prevent");
    expect(pickerSource).toContain("选择当前目录");
    expect(pickerSource).toContain("path-picker__entry-enter");
  });

  it("分析路径选择器为目录列表保留独立滚动区并保持移动端操作单行", () => {
    const pickerSource = readFileSync(
      fileURLToPath(
        new URL("../../components/prompts/PathPicker.vue", import.meta.url)
      ),
      "utf8"
    );

    expect(pickerSource).toContain("visibleShortcuts");
    expect(pickerSource).toContain("shortcuts: () => [],");
    expect(pickerSource).toContain(
      ":class=\"{ 'has-shortcuts': visibleShortcuts.length > 0 }\""
    );
    expect(pickerSource).toContain("path-picker__location-separator");
    expect(pickerSource).toMatch(
      /height:\s*min\(720px, calc\(100dvh - 36px\)\);/
    );
    expect(pickerSource).toMatch(
      /grid-template-rows:\s*auto auto minmax\(0, 1fr\) auto;/
    );
    expect(pickerSource).toMatch(
      /\.path-picker\.has-shortcuts\s*\{[\s\S]*?grid-template-rows:\s*auto auto auto minmax\(0, 1fr\) auto;/
    );
    expect(pickerSource).toMatch(
      /\.path-picker__list\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?overflow-y:\s*auto;/
    );
    expect(pickerSource).toContain("touch-action: pan-y;");
    expect(pickerSource).toContain(
      "grid-template-columns: repeat(3, minmax(0, 1fr));"
    );
    expect(pickerSource).toContain("white-space: nowrap;");
  });

  it("最近扫描同时呈现范围、指标、状态、时间和可达结果入口", () => {
    expect(analysisSource).toContain("<AnalysisRecentScans");
    expect(recentScansSource).toContain("item.scopes.join");
    expect(recentScansSource).toContain("metricsLabel(item)");
    expect(recentScansSource).toContain("statusLabel(item)");
    expect(recentScansSource).toContain("扫描范围");
    expect(recentScansSource).toContain(">状态</span>");
    expect(recentScansSource).toContain("扫描时间");
    expect(recentScansSource).toContain("操作");
    expect(recentScansSource).not.toContain("完成与操作");
    expect(recentScansSource).toContain(": 'scan'");
    expect(recentScansSource).toContain("formatTime(recordTime(item))");
    expect(recentScansSource).toContain("item.finishedAt || item.createdAt");
    expect(recentScansSource).toContain('class="analysis-recent__scope"');
    expect(recentScansSource).not.toContain('class="analysis-recent__paths"');
    expect(recentScansSource).toMatch(
      /\.analysis-recent__action\s*\{[\s\S]*?min-height:\s*44px;/
    );
    expect(recentScansSource).toMatch(
      /grid-template-columns:\s*24px minmax\(0, 1\.4fr\) minmax\(220px, 0\.8fr\)/
    );
    expect(recentScansSource).toMatch(
      /\.analysis-recent__column-scope\s*\{[\s\S]*?grid-column:\s*1 \/ span 2;[\s\S]*?text-align:\s*start;/
    );
    for (const column of ["status", "time", "actions"]) {
      expect(recentScansSource).toMatch(
        new RegExp(
          `\\.analysis-recent__column-${column}\\s*\\{[\\s\\S]*?text-align:\\s*center;`
        )
      );
    }
    expect(recentScansSource).not.toContain(
      '<header class="analysis-recent__header">'
    );
  });
});
