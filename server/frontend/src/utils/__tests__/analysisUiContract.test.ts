import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

describe("analysis page UI contract", () => {
  it("uses local semantic icons throughout reports and task states", () => {
    const source = readFileSync(
      resolve(process.cwd(), "src/views/Analysis.vue"),
      "utf8"
    );

    expect(source).not.toContain("material-icons");
    expect(source).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(source).toContain("getResourceIconName");
  });

  it("首屏只保留工具切换与一次扫描入口，不重复堆叠巨大标题卡", () => {
    const source = readFileSync(
      resolve(process.cwd(), "src/views/Analysis.vue"),
      "utf8"
    );

    expect(source).toContain("analysis-workspace__topline");
    expect(source).not.toContain("开始一次分析");
    expect(source).not.toMatch(/analysis-workspace__context/);
  });

  it("工具切换器在桌面保持紧凑，在移动端占满工作区", () => {
    const switcherSource = readFileSync(
      resolve(
        process.cwd(),
        "src/components/analysis/AnalysisToolSwitcher.vue"
      ),
      "utf8"
    );

    expect(switcherSource).toMatch(
      /\.analysis-tool-switcher\s*\{[\s\S]*?display:\s*grid;[\s\S]*?max-width:\s*100%;/
    );
    expect(switcherSource).toMatch(
      /@media\s*\(max-width:\s*520px\)[\s\S]*?\.analysis-tool-switcher\s*\{[\s\S]*?width:\s*100%;/
    );
    expect(switcherSource).toMatch(
      /grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/
    );
  });

  it("分析工作区把扫描动作和最近记录保持在清晰的对齐列中", () => {
    const scopeSource = readFileSync(
      resolve(process.cwd(), "src/components/analysis/AnalysisScopePanel.vue"),
      "utf8"
    );
    const recentSource = readFileSync(
      resolve(process.cwd(), "src/components/analysis/AnalysisRecentScans.vue"),
      "utf8"
    );

    expect(scopeSource).toContain('class="analysis-run-panel__heading"');
    expect(scopeSource).toContain('class="analysis-run-panel__selection"');
    expect(scopeSource).toContain('class="analysis-run-panel__summary"');
    expect(scopeSource).toContain('class="analysis-run-panel__empty-action"');
    expect(recentSource).toContain('class="analysis-recent__status"');
    expect(recentSource).toContain('class="analysis-recent__time-block"');
    expect(recentSource).toContain('class="analysis-recent__time"');
    expect(recentSource).toContain(
      '<span class="analysis-recent__column-scope">扫描范围</span>'
    );
    expect(recentSource).toContain(
      '<span class="analysis-recent__column-status">状态</span>'
    );
    expect(recentSource).toContain(
      '<span class="analysis-recent__column-time">扫描时间</span>'
    );
    expect(recentSource).toContain(
      '<span class="analysis-recent__column-actions">操作</span>'
    );
    expect(recentSource).not.toContain("完成与操作");
    expect(recentSource).toMatch(
      /grid-template-columns:\s*24px\s+minmax\(0, 1\.4fr\)\s+minmax\(220px, 0\.8fr\)/
    );
    expect(recentSource).toMatch(
      /\.analysis-recent__time\s*\{[\s\S]*?justify-items:\s*center;/
    );
    expect(recentSource).toContain('class="analysis-recent__load-more"');
    expect(recentSource).toContain('class="analysis-recent__clear"');
    expect(
      readFileSync(resolve(process.cwd(), "src/views/Analysis.vue"), "utf8")
    ).toContain('@load-more="loadMoreRecent"');
    expect(
      readFileSync(resolve(process.cwd(), "src/views/Analysis.vue"), "utf8")
    ).toContain("max-width: 1440px;");
  });

  it("最近扫描使用紧凑的表格节奏，动作入口不抢夺报告内容", () => {
    const recentSource = readFileSync(
      resolve(process.cwd(), "src/components/analysis/AnalysisRecentScans.vue"),
      "utf8"
    );

    expect(recentSource).toMatch(
      /\.analysis-recent__list > li\s*\{[\s\S]*?min-height:\s*64px;/
    );
    expect(recentSource).toMatch(
      /\.analysis-recent__action\s*\{[\s\S]*?min-height:\s*44px;/
    );
    expect(recentSource).toMatch(
      /\.analysis-recent\s*\{[\s\S]*?border-radius:\s*10px;/
    );
  });

  it("最近扫描的时间列只保留一次列标题，操作列使用轻量对齐动作", () => {
    const recentSource = readFileSync(
      resolve(process.cwd(), "src/components/analysis/AnalysisRecentScans.vue"),
      "utf8"
    );

    expect(recentSource).not.toContain('class="analysis-recent__time-label"');
    expect(recentSource).not.toMatch(
      /\.analysis-recent__time-block\s*\{[\s\S]*?border-left:/
    );
    expect(recentSource).not.toContain("analysis-recent__header p");
  });

  it("存储工具使用紧凑的蓝色分段导航", () => {
    const switcherSource = readFileSync(
      resolve(
        process.cwd(),
        "src/components/analysis/AnalysisToolSwitcher.vue"
      ),
      "utf8"
    );

    expect(switcherSource).toMatch(
      /\.analysis-tool-switcher\s*\{[\s\S]*?border:\s*1px solid var\(--borderPrimary\);[\s\S]*?background:\s*var\(--surfaceSecondary\);/
    );
    expect(switcherSource).toMatch(
      /\.analysis-tool-switcher button\.is-active\s*\{[\s\S]*?color:\s*var\(--blue\);[\s\S]*?background:/
    );
  });

  it("最近扫描以单行范围摘要呈现，详情通过结果入口打开", () => {
    const recentSource = readFileSync(
      resolve(process.cwd(), "src/components/analysis/AnalysisRecentScans.vue"),
      "utf8"
    );
    expect(recentSource).toContain('class="analysis-recent__scope"');
    expect(recentSource).toContain('class="analysis-recent__metrics"');
    expect(recentSource).toContain("item.scopes.join('、')");
    expect(recentSource).not.toContain('class="analysis-recent__paths"');
    expect(recentSource).toContain("@container (max-width: 720px)");
    expect(recentSource).not.toContain("translateY");
  });

  it("结果详情使用扫描语义图标并提供返回分析入口", () => {
    const analysisSource = readFileSync(
      resolve(process.cwd(), "src/views/Analysis.vue"),
      "utf8"
    );
    expect(analysisSource).toContain("is-result-detail");
    expect(analysisSource).toContain(
      'class="analysis-detail-action analysis-detail-action--back"'
    );
    expect(analysisSource).toContain('name="scan" :size="20"');
    expect(analysisSource).toContain('v-if="!isResultDetail"');
  });

  it("扫描入口保留风险告知，移除重复介绍和侧栏布局", () => {
    const scopeSource = readFileSync(
      resolve(process.cwd(), "src/components/analysis/AnalysisScopePanel.vue"),
      "utf8"
    );
    const pageSource = readFileSync(
      resolve(process.cwd(), "src/views/Analysis.vue"),
      "utf8"
    );
    expect(scopeSource).not.toContain("analysis-run-panel__intro");
    expect(scopeSource).not.toContain("analysis-run-panel__section--confirm");
    expect(scopeSource).not.toContain("minmax(208px");
    expect(pageSource).not.toContain("analysis-workspace__hint");
    expect(scopeSource).toContain("确认扫描整个可访问范围");
    expect(scopeSource).toContain(':disabled="!canStart"');
  });

  it("分析报告使用紧凑指标带和独立的十条加载更多入口", () => {
    const pageSource = readFileSync(
      resolve(process.cwd(), "src/views/Analysis.vue"),
      "utf8"
    );
    const cleanupSource = readFileSync(
      resolve(
        process.cwd(),
        "src/components/analysis/DuplicateCleanupPanel.vue"
      ),
      "utf8"
    );

    expect(pageSource).toContain('class="analysis-metric-strip"');
    expect(pageSource).not.toContain("analysis-summary-grid");
    expect(pageSource).toContain("storageDirectoriesVisibleCount = ref(10)");
    expect(pageSource).toContain("storageFilesVisibleCount = ref(10)");
    expect(pageSource).toContain("加载更多目录");
    expect(pageSource).toContain("加载更多文件");
    expect(pageSource).toMatch(
      /\.storage-rank-row--directory\s*\{[\s\S]*?grid-template-columns:\s*24px\s+19px\s+minmax\(0, 1fr\)\s+68px\s+84px/
    );
    expect(pageSource).toMatch(
      /\.storage-rank-row--file\s*\{[\s\S]*?grid-template-columns:\s*24px\s+19px\s+minmax\(0, 1fr\)\s+84px\s+112px/
    );
    expect(cleanupSource).toContain("visibleGroupCount = ref(10)");
    expect(cleanupSource).toContain("加载更多重复组");
    expect(cleanupSource).toContain('class="cleanup-group__details"');
  });
});
