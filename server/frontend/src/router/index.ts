import type { RouteLocation } from "vue-router";
import { createRouter, createWebHistory } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { useNavigationStore } from "@/stores/navigation";
import { baseURL, name } from "@/utils/constants";
import { recaptcha, loginPage } from "@/utils/constants";
import { login, validateLogin } from "@/utils/auth";

const Login = () => import("@/views/Login.vue");
const Layout = () => import("@/views/Layout.vue");
const Files = () => import("@/views/Files.vue");
const Share = () => import("@/views/Share.vue");
const SearchPage = () => import("@/views/SearchPage.vue");
const Recent = () => import("@/views/Recent.vue");
const Trash = () => import("@/views/Trash.vue");
const TaskCenter = () => import("@/views/TaskCenter.vue");
const Analysis = () => import("@/views/Analysis.vue");
const Archive = () => import("@/views/Archive.vue");
const Settings = () => import("@/views/Settings.vue");
const ProfileSettings = () => import("@/views/settings/Profile.vue");
const Shares = () => import("@/views/settings/Shares.vue");
const GlobalSettings = () => import("@/views/settings/Global.vue");
const Users = () => import("@/views/settings/Users.vue");
const User = () => import("@/views/settings/User.vue");
const Errors = () => import("@/views/Errors.vue");

const titles: Record<string, string> = {
  Login: "登录",
  Share: "分享",
  Files: "文件管理",
  Search: "搜索",
  Recent: "最近访问",
  Trash: "回收站",
  Tasks: "任务中心",
  History: "操作历史",
  Analysis: "存储工具",
  Archive: "压缩包浏览",
  Settings: "设置",
  ProfileSettings: "账户设置",
  Shares: "分享管理",
  GlobalSettings: "全局设置",
  Users: "用户管理",
  User: "用户",
  Forbidden: "无权限",
  NotFound: "页面未找到",
  InternalServerError: "服务器错误",
};

const routes = [
  {
    path: "/login",
    name: "Login",
    component: Login,
  },
  {
    path: "/share",
    component: Layout,
    children: [
      {
        path: ":path*",
        name: "Share",
        component: Share,
      },
    ],
  },
  {
    path: "/files",
    component: Layout,
    meta: {
      requiresAuth: true,
    },
    children: [
      {
        path: ":path*",
        name: "Files",
        component: Files,
      },
    ],
  },
  {
    path: "/search",
    component: Layout,
    meta: {
      requiresAuth: true,
    },
    children: [
      {
        path: "",
        name: "Search",
        component: SearchPage,
      },
    ],
  },
  {
    path: "/recent",
    component: Layout,
    meta: {
      requiresAuth: true,
    },
    children: [
      {
        path: "",
        name: "Recent",
        component: Recent,
      },
    ],
  },
  {
    path: "/trash",
    component: Layout,
    meta: {
      requiresAuth: true,
    },
    children: [
      {
        path: "",
        name: "Trash",
        component: Trash,
      },
    ],
  },
  {
    path: "/tasks",
    component: Layout,
    meta: {
      requiresAuth: true,
    },
    children: [
      {
        path: "",
        name: "Tasks",
        component: TaskCenter,
      },
    ],
  },
  {
    path: "/history",
    redirect: { path: "/tasks", query: { tab: "history" } },
  },
  {
    path: "/analysis",
    component: Layout,
    meta: {
      requiresAuth: true,
    },
    children: [
      {
        path: "",
        name: "Analysis",
        component: Analysis,
      },
    ],
  },
  {
    path: "/archive",
    component: Layout,
    meta: {
      requiresAuth: true,
    },
    children: [
      {
        path: "",
        name: "Archive",
        component: Archive,
      },
    ],
  },
  {
    path: "/settings",
    component: Layout,
    meta: {
      requiresAuth: true,
    },
    children: [
      {
        path: "",
        name: "Settings",
        component: Settings,
        redirect: {
          path: "/settings/profile",
        },
        children: [
          {
            path: "profile",
            name: "ProfileSettings",
            component: ProfileSettings,
          },
          {
            path: "shares",
            name: "Shares",
            component: Shares,
          },
          {
            path: "global",
            name: "GlobalSettings",
            component: GlobalSettings,
            meta: {
              requiresAdmin: true,
            },
          },
          {
            path: "users",
            name: "Users",
            component: Users,
            meta: {
              requiresAdmin: true,
            },
          },
          {
            path: "users/:id",
            name: "User",
            component: User,
            meta: {
              requiresAdmin: true,
            },
          },
        ],
      },
    ],
  },
  {
    path: "/403",
    name: "Forbidden",
    component: Errors,
    props: {
      errorCode: 403,
      showHeader: true,
    },
  },
  {
    path: "/404",
    name: "NotFound",
    component: Errors,
    props: {
      errorCode: 404,
      showHeader: true,
    },
  },
  {
    path: "/500",
    name: "InternalServerError",
    component: Errors,
    props: {
      errorCode: 500,
      showHeader: true,
    },
  },
  {
    path: "/:catchAll(.*)*",
    redirect: (to: RouteLocation) => {
      const catchAll = to.params.catchAll;
      if (catchAll && Array.isArray(catchAll) && catchAll.length > 0) {
        return `/files/${catchAll.join("/")}`;
      }
      return "/files/";
    },
  },
];

async function initAuth() {
  if (loginPage) {
    await validateLogin();
  } else {
    await login("", "", "");
  }

  if (recaptcha) {
    await new Promise<void>((resolve) => {
      const check = () => {
        if (typeof window.grecaptcha === "undefined") {
          setTimeout(check, 100);
        } else {
          resolve();
        }
      };

      check();
    });
  }
}

const router = createRouter({
  history: createWebHistory(baseURL),
  routes,
});

router.beforeResolve(async (to, from) => {
  const title = titles[to.name as keyof typeof titles];
  document.title = title + " - " + name;

  const authStore = useAuthStore();

  // this will only be null on first route
  if (from.name == null) {
    try {
      await initAuth();
    } catch (error) {
      console.error(error);
    }
  }

  if (to.path.endsWith("/login") && authStore.isLoggedIn) {
    return { path: "/files/" };
  }

  if (to.matched.some((record) => record.meta.requiresAuth)) {
    if (!authStore.isLoggedIn) {
      return {
        path: "/login",
        query: { redirect: to.fullPath },
      };
    }

    if (to.matched.some((record) => record.meta.requiresAdmin)) {
      if (authStore.user === null || !authStore.user.perm.admin) {
        return { path: "/403" };
      }
    }
  }

  return true;
});

router.afterEach((to, from, failure) => {
  const navigation = useNavigationStore();
  if (failure) {
    navigation.restorePath = null;
    return;
  }
  if (
    !useAuthStore().isLoggedIn ||
    !to.matched.some((record) => record.meta.requiresAuth)
  )
    return;
  const position = window.history.state?.position;
  navigation.record(
    {
      path: to.fullPath,
      position: Number.isSafeInteger(position) ? position : null,
    },
    !from.name
  );
});

export { router, router as default };
