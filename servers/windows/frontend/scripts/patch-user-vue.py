import subprocess
from pathlib import Path

raw = subprocess.check_output(
    ["git", "show", "HEAD:frontend/src/views/settings/User.vue"],
    cwd=r"D:\Kkwans\Desktop\Project\MyProject\WinFileBrowser",
)
text = raw.decode("utf-8")

old_title = """        <div class="card-title">
          <h2 v-if="user?.id === 0">{{ "新建用户" }}</h2>
          <h2 v-else>{{ "编辑用户" }}</h2>
        </div>"""
new_title = """        <div class="card-title">
          <h2 v-if="user?.id === 0">{{ "新建用户" }}</h2>
          <h2 v-else>{{ "编辑用户" }}</h2>
          <button
            class="button button--flat"
            type="button"
            style="min-width: 72px; margin-left: auto"
            @click="requestSave"
          >
            保存
          </button>
        </div>"""

if old_title not in text:
    i = text.find("card-title")
    print("title not found", repr(text[i - 20 : i + 220]))
    raise SystemExit(1)
text = text.replace(old_title, new_title, 1)

text = text.replace(
    'import { computed, inject, onMounted, ref, watch } from "vue";',
    'import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from "vue";',
    1,
)

old_mount = "onMounted(() => {\n  fetchData();\n});"
new_mount = """onMounted(() => {
  fetchData();
  window.addEventListener("winfb-settings-save", onNavSave);
});

onBeforeUnmount(() => {
  window.removeEventListener("winfb-settings-save", onNavSave);
});

function onNavSave() {
  requestSave();
}

function requestSave() {
  const form = document.querySelector("form.card") as HTMLFormElement | null;
  form?.requestSubmit?.();
}"""
if old_mount not in text:
    i = text.find("onMounted")
    print("mount not found", repr(text[i : i + 80]))
    raise SystemExit(1)
text = text.replace(old_mount, new_mount, 1)

out = Path(r"D:\Kkwans\Desktop\Project\MyProject\WinFileBrowser\frontend\src\views\settings\User.vue")
out.write_text(text, encoding="utf-8")
print("ok", len(text), out)
