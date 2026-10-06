<template>
  <div>
    <header-bar showMenu showLogo>
      <title />

      <action
        v-if="fileStore.selectedCount"
        app-icon="download"
        label="下载"
        @action="download"
        :counter="fileStore.selectedCount"
      />
      <button
        v-if="isSingleFile()"
        class="action copy-clipboard"
        aria-label="复制下载链接"
        :data-title="'复制下载链接'"
        @click="copyToClipboard(linkSelected())"
      >
        <AppIcon name="copy" :size="20" :stroke-width="1.9" />
      </button>
      <action
        app-icon="select"
        label="多选"
        @action="toggleMultipleSelection"
      />
    </header-bar>

    <breadcrumbs :base="'/share/' + hash" />

    <div v-if="layoutStore.loading">
      <h2 class="message delayed" style="padding-top: 3em !important">
        <div class="spinner">
          <div class="bounce1"></div>
          <div class="bounce2"></div>
          <div class="bounce3"></div>
        </div>
        <span>加载中...</span>
      </h2>
    </div>
    <div v-else-if="error">
      <div v-if="error.status === 401">
        <div class="card floating" id="password" style="z-index: 9999999">
          <div v-if="attemptedPasswordLogin" class="share__wrong__password">
            用户名或密码错误
          </div>
          <div class="card-title">
            <h2>密码</h2>
          </div>

          <div class="card-content">
            <input
              v-focus
              class="input input--block"
              type="password"
              placeholder="密码"
              v-model="password"
              @keyup.enter="fetchData"
            />
          </div>
          <div class="card-action">
            <button
              class="button button--flat"
              @click="fetchData"
              aria-label="提交"
              :data-title="'提交'"
            >
              提交
            </button>
          </div>
        </div>
        <div class="overlay" />
      </div>
      <errors v-else :errorCode="error.status" />
    </div>
    <div v-else-if="req !== null">
      <div class="share">
        <div
          class="share__box share__box__info"
          style="
            position: -webkit-sticky;
            position: sticky;
            top: -20.6em;
            z-index: 999;
          "
        >
          <div class="share__box__header" style="height: 3em">
            {{ req.isDir ? "下载文件夹" : "下载文件" }}
          </div>
          <div
            v-if="!req.isDir"
            class="share__box__element share__box__center share__box__icon"
          >
            <AppIcon :name="icon" :size="72" :stroke-width="1.45" />
          </div>
          <div class="share__box__element" style="height: 3em">
            <strong>'名称'：</strong> {{ req.name }}
          </div>
          <div v-if="!req.isDir" class="share__box__element" :title="modTime">
            <strong>'最后修改':</strong> {{ humanTime }}
          </div>
          <div class="share__box__element" style="height: 3em">
            <strong>'大小':</strong> {{ humanSize }}
          </div>
          <div class="share__box__element share__box__center">
            <a
              target="_blank"
              :href="link"
              class="button button--flat"
              style="height: 4em"
            >
              <div><AppIcon name="download" :size="18" />下载</div>
            </a>
            <a
              target="_blank"
              :href="inlineLink"
              class="button button--flat"
              v-if="!req.isDir"
            >
              <div><AppIcon name="external-link" :size="18" />打开文件</div>
            </a>
            <qrcode-vue
              v-if="req.isDir"
              :value="link"
              :size="100"
              level="M"
            ></qrcode-vue>
          </div>
          <div v-if="!req.isDir" class="share__box__element share__box__center">
            <qrcode-vue :value="link" :size="200" level="M"></qrcode-vue>
          </div>
          <div
            v-if="req.isDir"
            class="share__box__element share__box__header"
            style="height: 3em"
          >
            预览
          </div>
          <div
            v-if="req.isDir"
            class="share__box__element share__box__center share__box__icon"
            style="padding: 0em !important; height: 12em !important"
          >
            <a
              target="_blank"
              :href="raw"
              class="button button--flat"
              v-if="
                !fileStore.multiple &&
                fileStore.selectedCount === 1 &&
                selectedItem?.type === 'image'
              "
              style="height: 12em; padding: 0; margin: 0"
            >
              <img
                style="height: 12em"
                :src="raw"
                :alt="fileStore.selectedCount === 1 ? selectedItem?.name : ''"
              />
            </a>
            <div
              v-else-if="
                fileStore.multiple &&
                fileStore.selectedCount === 1 &&
                selectedItem?.type === 'audio'
              "
              style="height: 12em; padding-top: 1em; margin: 0"
            >
              <button
                @click="play"
                v-if="!tag"
                style="
                  font-size: 6em !important;
                  border: 0px;
                  outline: none;
                  background: white;
                "
                class="share-audio-toggle"
                aria-label="播放"
              >
                <AppIcon name="play" :size="72" :stroke-width="1.55" />
              </button>
              <button
                @click="play"
                v-if="tag"
                style="
                  font-size: 6em !important;
                  border: 0px;
                  outline: none;
                  background: white;
                "
                class="share-audio-toggle"
                aria-label="暂停"
              >
                <AppIcon name="pause" :size="72" :stroke-width="1.55" />
              </button>
              <audio
                id="myaudio"
                ref="audio"
                :src="raw"
                controls
                :autoplay="tag"
              ></audio>
            </div>
            <video
              v-else-if="
                !fileStore.multiple &&
                fileStore.selectedCount === 1 &&
                selectedItem?.type === 'video'
              "
              style="height: 12em; padding: 0; margin: 0"
              :src="raw"
              controls
            >
              您的浏览器不支持内嵌视频播放，请<a :href="raw">下载文件</a
              >后使用本地播放器观看。
            </video>
            <AppIcon
              v-else-if="
                !fileStore.multiple &&
                fileStore.selectedCount === 1 &&
                selectedItem?.isDir
              "
              name="folder"
              :size="72"
              :stroke-width="1.45"
            />
            <AppIcon v-else name="file" :size="72" :stroke-width="1.45" />
          </div>
        </div>
        <div
          id="shareList"
          v-if="req.isDir && req.items.length > 0"
          class="share__box share__box__items"
        >
          <div class="share__box__header" v-if="req.isDir">文件</div>
          <div id="listing" class="list file-icons">
            <item
              v-for="item in req.items.slice(0, showLimit)"
              :key="base64(item.name)"
              v-bind:index="item.index"
              v-bind:name="item.name"
              v-bind:isDir="item.isDir"
              v-bind:url="item.url"
              v-bind:modified="item.modified"
              v-bind:type="item.type"
              v-bind:size="item.size"
              v-bind:path="item.path"
              readOnly
            >
            </item>
            <div
              v-if="req.items.length > showLimit"
              class="item"
              @click="showLimit += 100"
            >
              <div>
                <p class="name">+ {{ req.items.length - showLimit }}</p>
              </div>
            </div>

            <div
              :class="{ active: fileStore.multiple }"
              id="multiple-selection"
            >
              <p>多选模式已开启</p>
              <div
                @click="() => (fileStore.multiple = false)"
                tabindex="0"
                role="button"
                :data-title="'清空'"
                aria-label="清空"
                class="action"
              >
                <AppIcon name="x" :size="18" :stroke-width="2" />
              </div>
            </div>
          </div>
        </div>
        <div
          v-else-if="req.isDir && req.items.length === 0"
          class="share__box share__box__items"
        >
          <h2 class="message">
            <AppIcon name="frown" :size="30" :stroke-width="1.7" />
            <span>这里没有任何文件...</span>
          </h2>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { pub as api } from "@/api";
import { filesize } from "@/utils";
import dayjs from "@/utils/date";
import { Base64 } from "js-base64";
import { createURL } from "@/api/utils";
import HeaderBar from "@/components/header/HeaderBar.vue";
import Action from "@/components/header/Action.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import Breadcrumbs from "@/components/Breadcrumbs.vue";
import Errors from "@/views/Errors.vue";
import QrcodeVue from "qrcode.vue";
import Item from "@/components/files/ListingItem.vue";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import { computed, inject, onMounted, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { StatusError } from "@/api/utils";
import { copy } from "@/utils/clipboard";
import type { DownloadFormat } from "@/types/file";
import { getResourceIconName } from "@/utils/fileIcons";

const error = ref<StatusError | null>(null);
const showLimit = ref<number>(100);
const password = ref<string>("");
const attemptedPasswordLogin = ref<boolean>(false);
const hash = ref<string>("");
const token = ref<string>("");
const audio = ref<HTMLAudioElement>();
const tag = ref<boolean>(false);

const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<IToastSuccess>("$showSuccess")!;

const route = useRoute();
const fileStore = useFileStore();
const layoutStore = useLayoutStore();

watch(route, () => {
  showLimit.value = 100;
  fetchData();
});

const req = computed(() => fileStore.req);
const selectedItem = computed(() => fileStore.selectedItems[0]);

// Define computes

const icon = computed(() => {
  if (req.value === null) return "file" as const;
  return getResourceIconName(
    req.value.name,
    req.value.type ?? "",
    req.value.isDir
  );
});

const link = computed(() => (req.value ? api.getDownloadURL(req.value) : ""));
const raw = computed(() => {
  if (!req.value || !selectedItem.value) return "";
  return createURL(`api/public/dl/${hash.value}${selectedItem.value.path}`, {
    token: token.value,
  });
});
const inlineLink = computed(() =>
  req.value ? api.getDownloadURL(req.value, true) : ""
);
const humanSize = computed(() => {
  if (req.value) {
    return req.value.isDir
      ? req.value.items.length
      : filesize(req.value.size ?? 0);
  } else {
    return "";
  }
});
const humanTime = computed(() => dayjs(req.value?.modified).fromNow());

const modTime = computed(() =>
  req.value
    ? new Date(Date.parse(req.value.modified)).toLocaleString()
    : new Date().toLocaleString()
);

// Functions
const base64 = (name: string) => Base64.encodeURI(name);
const play = () => {
  if (tag.value) {
    audio.value?.pause();
    tag.value = false;
  } else {
    audio.value?.play();
    tag.value = true;
  }
};
const fetchData = async () => {
  fileStore.reload = false;
  fileStore.clearSelection();
  layoutStore.closeHovers();

  // Set loading to true and reset the error.
  layoutStore.loading = true;
  error.value = null;
  if (password.value !== "") {
    attemptedPasswordLogin.value = true;
  }

  let url = route.path;
  if (url === "") url = "/";
  if (url[0] !== "/") url = "/" + url;

  try {
    const file = await api.fetch(url, password.value);
    file.hash = hash.value;

    token.value = file.token || "";

    fileStore.updateRequest(file);
    document.title = `${file.name} - ${document.title}`;
  } catch (err) {
    if (err instanceof Error) {
      error.value = err;
    }
  } finally {
    layoutStore.loading = false;
  }
};

const keyEvent = (event: KeyboardEvent) => {
  if (event.key === "Escape") {
    // If we're on a listing, unselect all
    // files and folders.
    if (fileStore.selectedCount > 0) {
      fileStore.clearSelection();
    }
  }
};

const toggleMultipleSelection = () => {
  fileStore.toggleMultiple();
};

const isSingleFile = () =>
  fileStore.selectedCount === 1 && !selectedItem.value?.isDir;

const download = () => {
  if (!req.value) return false;

  if (isSingleFile()) {
    api.download(null, hash.value, token.value, selectedItem.value!.path);
    return true;
  }

  layoutStore.showHover({
    prompt: "download",
    confirm: (format: DownloadFormat) => {
      if (req.value === null) return false;
      layoutStore.closeHovers();

      const files: string[] = [];

      for (const item of fileStore.selectedItems) {
        files.push(item.path);
      }

      api.download(format, hash.value, token.value, ...files);
      return true;
    },
  });

  return true;
};

const linkSelected = () => {
  return isSingleFile() && req.value
    ? api.getDownloadURL({
        ...req.value,
        hash: hash.value,
        path: selectedItem.value!.path,
      })
    : "";
};

const copyToClipboard = (text: string) => {
  copy({ text }).then(
    () => {
      // clipboard successfully set
      $showSuccess("链接已复制到剪贴板");
    },
    () => {
      // clipboard write failed
      copy({ text }, { permission: true }).then(
        () => {
          // clipboard successfully set
          $showSuccess("链接已复制到剪贴板");
        },
        (e) => {
          // clipboard write failed
          $showError(e);
        }
      );
    }
  );
};

onMounted(async () => {
  // Created
  hash.value = route.params.path[0];
  window.addEventListener("keydown", keyEvent);
  await fetchData();
});

onBeforeUnmount(() => {
  // Destroyed
  window.removeEventListener("keydown", keyEvent);
});
</script>

<style scoped>
#listing.list {
  height: auto;
}

#shareList {
  overflow-y: scroll;
}

@media (min-width: 930px) {
  #shareList {
    height: calc(100vh - 9.8em);
    overflow-y: auto;
  }
}
</style>
