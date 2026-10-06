/**
 * 集中管理所有翻译常量（替代 vue-i18n）
 * 使用 flat keys（点分隔），如 buttons.save, share.password
 * 组件中使用: '保存' 或 '保存'
 */
export const T = {
  // === buttons ===
  "buttons.save": "保存",
  "buttons.delete": "删除",
  "buttons.create": "创建",
  "buttons.share": "分享",
  "buttons.copy": "复制",
  "buttons.close": "关闭",
  "buttons.openFile": "打开文件",
  "buttons.openFolder": "打开文件夹",
  "buttons.copyFile": "复制文件",
  "buttons.preview": "预览",
  "buttons.stopSearch": "停止搜索",
  "buttons.download": "下载",
  "buttons.rename": "重命名",
  "buttons.editAsText": "编辑文本",
  "buttons.openDirect": "直接打开",
  "buttons.cancel": "取消",
  "buttons.confirm": "确认",
  "buttons.continue": "继续",
  "buttons.replace": "替换",
  "buttons.skip": "跳过",
  "buttons.createFolder": "新建文件夹",
  "buttons.createFile": "新建文件",
  "buttons.settings": "设置",
  "buttons.logout": "退出登录",
  "buttons.login": "登录",
  "buttons.register": "注册",
  "buttons.newShare": "新建",
  "buttons.removeShare": "删除",
  "buttons.addTag": "添加标签",
  "buttons.manageTags": "管理标签",
  "buttons.clearFilter": "清除筛选",
  "buttons.discardChanges": "放弃更改",
  "buttons.saveChanges": "保存更改",
  "buttons.discardEditorChanges": "放弃更改",
  "buttons.copyToClipboard": "复制到剪贴板",
  "buttons.copyDownloadLink": "复制下载链接到剪贴板",
  "buttons.move": "移动",
  "buttons.submit": "提交",

  // === search ===
  "search.typeToSearch": "输入关键词搜索",
  "search.pressToSearch": "按回车搜索",
  "search.stopSearching": "停止搜索",
  "search.noResults": "无搜索结果",
  "search.searchTypes": "类型",
  "search.images": "图像",
  "search.music": "音乐",
  "search.video": "视频",
  "search.searchPlaceholder": "搜索",

  // === viewer ===
  "viewer.openInEditor": "在编辑器中打开",
  "viewer.fileTooLarge": "文件过大",
  "viewer.cannotPreview": "此文件无法预览",
  "viewer.clickToDownload": "点击以显示",
  "viewer.browserNotSupport": "您的浏览器不支持嵌入式视频播放，请下载后观看。",
  "viewer.close": "关闭",
  "viewer.previous": "上一个",
  "viewer.next": "下一个",
  "viewer.showingRows": "正在显示 {count} 行",
  "viewer.noFilesHere": "这里没有任何文件...",

  // === users ===
  "users.userManagement": "用户管理",
  "users.newUser": "新建用户",
  "users.editingUser": "用户 {name}",
  "users.username": "用户名",
  "users.password": "密码",
  "users.currentPassword": "当前密码",
  "users.confirmPassword": "确认密码",
  "users.createUser": "创建用户",
  "users.updateUser": "更新用户",

  // === permissions ===
  "permissions.title": "权限",
  "permissions.adminDescription":
    "你可以将该用户设置为管理员或单独选择各项权限。如果你选择了「管理员」，则其他的选项会被自动选中，同时该用户可以管理其他用户。",
  "permissions.admin": "管理员",
  "permissions.createFiles": "创建文件和文件夹",
  "permissions.deleteFiles": "删除文件和文件夹",
  "permissions.download": "下载",
  "permissions.edit": "编辑",
  "permissions.executeCommands": "执行命令",
  "permissions.renameMove": "重命名或移动文件和文件夹",
  "permissions.shareFiles": "分享文件（需要下载权限）",

  // === commands ===
  "commands.title": "用户命令（Shell 命令）",
  "commands.description":
    "指定该用户可以执行的命令（Shell 命令），用空格分隔。例如：",
  "commands.placeholder": "输入正则表达式",
  "commands.pathPlaceholder": "输入路径",

  // === share ===
  "share.password": "密码",
  "share.name": "名称",
  "share.lastModified": "最后修改",
  "share.size": "大小",
  "share.shareExpiry": "分享期限",
  "share.permanent": "永久",
  "share.copyShareLink": "复制到剪贴板",
  "share.timeUnit": "时间单位",
  "share.seconds": "秒",
  "share.minutes": "分钟",
  "share.hours": "小时",
  "share.days": "天",
  "share.passwordOptional": "密码（选填，不填即无密码）",
  "share.title": "分享",

  // === confirm ===
  "confirm.deleteConfirm": "你确定要删除这个文件/文件夹吗？",
  "confirm.deleteCountConfirm": "你确定要删除这 {count} 个文件吗？",
  "confirm.confirmExecution": "确认执行",
  "confirm.riskOperationConfirm": "风险操作确认",
  "confirm.warningProtectedDir":
    "您正在对一个受保护的目录执行操作，请确认您了解可能的后果。",
  "confirm.warningDelete":
    "删除此目录可能导致系统组件无法正常运行，数据可能无法恢复。",
  "confirm.warningRename": "重命名此目录可能导致依赖它的系统组件无法正常工作。",
  "confirm.warningMove": "移动此目录可能导致依赖它的系统组件无法正常工作。",
  "confirm.warningGeneral": "对此目录的操作可能影响系统稳定性。",
  "confirm.determineDeleteShare": "你确定要删除这个分享吗？",
  "confirm.determineAbortUpload": "确定中止上传？",

  // === risk ===
  "risk.high": "高危",
  "risk.medium": "中危",
  "risk.low": "低危",

  // === upload ===
  "upload.title": "上传文件",
  "upload.remaining": "剩余时间",
  "upload.completed": "已完成",
  "upload.abortUpload": "确定中止上传？",
  "upload.remainingTime": "剩余时间",
  "upload.completedPercent": "已完成",
  "upload.confirmAbort": "确定中止上传？",

  // === settings ===
  "settings.rules": "规则",
  "settings.brandCustomization": "品牌定制",
  "settings.brandDescription": "如需自定义品牌，请参考",
  "settings.officialDoc": "官方文档",
  "settings.commandExecutor": "命令执行器",
  "settings.commandDescription":
    "命令执行器允许在文件操作前后运行自定义命令。详情请查看",
  "settings.globalSettings": "全局设置",
  "settings.profileSettings": "账户设置",
  "settings.shareManagement": "分享管理",
  "settings.usersManagement": "用户管理",

  // === info ===
  "info.title": "文件信息",
  "info.selectedCount": "已选择 {count} 个文件",
  "info.resolution": "分辨率",
  "info.fileCount": "文件数",
  "info.folderCount": "文件夹数",
  "info.currentDir": "当前目录：",

  // === favorites ===
  "favorites.favorites": "收藏夹",
  "favorites.newGroup": "新建分组",
  "favorites.clearAll": "清空收藏夹",
  "favorites.noFavorites": "暂无收藏目录",
  "favorites.removeFavorite": "取消收藏",
  "favorites.deleteGroup": "删除分组",
  "favorites.noGroupFavorites": "该分组暂无收藏",
  "favorites.addToGroup": "收藏到分组",
  "favorites.ungrouped": "未分组",
  "favorites.groupNamePlaceholder": "分组名称...",

  // === tags ===
  "tags.tags": "标签",
  "tags.manageTags": "管理标签",
  "tags.assignTags": "分配标签",
  "tags.noTags": "暂无标签，创建一个吧",
  "tags.createTag": "创建标签",
  "tags.tagNamePlaceholder": "标签名称...",
  "tags.confirmDeleteTag": "确定删除标签「{name}」？",
  "tags.noTagFiles": "暂无标签，创建一个吧",
  "tags.tagTitle": "标签",
  "tags.tagName": "标签名称...",
  "tags.createTagAction": "创建标签",
  "tags.noTagsAction": "暂无标签，创建一个吧",
  "tags.editTag": "编辑标签",
  "tags.deleteTag": "删除标签",
  "tags.confirmDelete": "确定删除标签「{name}」？",
  "tags.assignTagAction": "分配标签",
  "tags.manageTagsAction": "管理标签",
  "tags.tagsTitle": "管理标签",

  // === volumes ===
  "volumes.storageVolumes": "存储卷",
  "volumes.diskUsage": "磁盘使用：",

  // === categories ===
  "categories.categories": "目录分类",
  "categories.myFiles": "我的文件",
  "categories.personalFolder": "个人文件夹",
  "categories.sharedFolder": "共享文件夹",
  "categories.systemFolder": "系统文件夹",
  "categories.other": "其他",
  "categories.viewContent": "查看内容",

  // === listing ===
  "listing.name": "名称",
  "listing.size": "大小",
  "listing.filteringByTag": "正在按标签筛选:",
  "listing.copyFile": "复制文件",
  "listing.download": "下载",
  "listing.delete": "删除",

  // === editor ===
  "editor.languageLabel": "代码语言",
  "editor.lineNumbers": "行号",
  "editor.markdownMode": "Markdown 模式切换",
  "editor.vditorContainer": "Vditor 容器（Markdown 文件）",
  "editor.aceEditor": "Ace 编辑器（非 Markdown 文件）",

  // === help ===
  "help.title": "帮助",
  "help.f1": "显示该帮助信息",
  "help.f2": "重命名文件/文件夹",
  "help.del": "删除所选的文件/文件夹",
  "help.esc": "清除已选项或关闭提示信息",
  "help.arrowKeys": "上下方向键导航文件列表",
  "help.shiftArrow": "Shift + 方向键多选文件",
  "help.enter": "打开选中的文件/文件夹",
  "help.homeEnd": "跳转到列表首/末尾",
  "help.pageUpDown": "按页上下翻滚",
  "help.ctrlS": "保存文件或下载当前文件夹",
  "help.ctrlC": "复制选中文件",
  "help.ctrlX": "剪切选中文件",
  "help.ctrlV": "粘贴剪贴板中的文件",
  "help.ctrlA": "全选所有文件和文件夹",
  "help.ctrlShiftF": "打开搜索框",
  "help.ctrlClick": "选择多个文件或文件夹",
  "help.click": "选择文件或文件夹",
  "help.doubleClick": "打开文件/文件夹",
  "help.space": "快速预览文件（选中文件后按空格键）",
  "help.ok": "确定",

  // === other ===
  "other.passwordRequired": "请输入密码以确认此操作。",
  "other.reportIssue": "报告问题",
  "other.search": "搜索",
  "other.tags": "标签",
  "other.favorites": "收藏夹",
  "other.resolveConflict": "解决冲突",
  "other.replaceOrSkip": "替换或跳过",
  "other.keepBothDescription":
    "如果选择保留两个版本，副本文件名将添加数字后缀。",
  "other.sourceFile": "源位置文件",
  "other.uploadFile": "上传文件",
  "other.targetFile": "目标位置文件",
  "other.renameAll": "重命名",
  "other.permissionError": "权限错误",
  "other.replaceAll": "覆盖",
  "other.skipAll": "跳过",
  "other.conflictCount": "目标文件夹中有 {count} 个同名文件",
  "other.replaceAllFiles": "替换目标文件夹中的所有文件",
  "other.renameAllFiles": "重命名所有文件（创建副本）",
  "other.skipAllConflicts": "跳过所有冲突文件",
  "other.resumeTransfer": "恢复传输",
  "other.skipSmallerFiles":
    "跳过所有冲突文件，除了服务器上较小的文件（可能传输中断）。",
  "other.handleIndividually": "逐个处理冲突文件",
  "other.columnSeparator": "列分隔符",
  "other.comma": "逗号",
  "other.semicolon": "分号",
  "other.both": "逗号和分号",
  "other.fileEncoding": "文件编码",
  "other.searchPlaceholder": "搜索...",
  "other.csvColumn": "列 {index}",
  "other.selectMultiple": "选择多个",

  // === copy ===
  "copy.targetDirectory": "请选择目标目录：",

  // === move ===
  "move.title": "移动",

  // === login ===
  "login.password": "密码",

  // === csv ===
  "csv.noFilesHere": "这里没有任何文件...",
  "csv.showingRows": "正在显示 {count} 行",
  "csv.column": "列 {index}",
  "csv.columnSeparator": "列分隔符",
  "csv.comma": "逗号",
  "csv.semicolon": "分号",
  "csv.both": "逗号和分号",
  "csv.fileEncoding": "文件编码",
  "csv.searchPlaceholder": "搜索...",

  // === sidebar ===
  "sidebar.myFiles": "我的文件",
  "sidebar.search": "搜索",
  "sidebar.favorites": "收藏夹",
  "sidebar.tags": "标签",
  "sidebar.storageVolumes": "存储卷",
  "sidebar.categories": "目录分类",
  "sidebar.createFolder": "新建文件夹",
  "sidebar.createFile": "新建文件",
  "sidebar.settings": "设置",
  "sidebar.logout": "退出登录",
  "sidebar.login": "登录",
  "sidebar.register": "注册",
  "sidebar.noFavorites": "暂无收藏目录",
  "sidebar.removeFavorite": "取消收藏",
  "sidebar.deleteGroup": "删除分组",
  "sidebar.noGroupFavorites": "该分组暂无收藏",
  "sidebar.noTags": "暂无标签，创建一个吧",
  "sidebar.clearFilter": "清除筛选",
  "sidebar.manageTags": "管理标签",
  "sidebar.newGroup": "新建分组",
  "sidebar.clearAll": "清空收藏夹",
  "sidebar.dragToResize": "拖拽调节侧边栏宽度",
  "sidebar.viewContent": "查看内容",
  "sidebar.diskUsage": "磁盘使用：",
  "sidebar.help": "帮助",

  // === replace ===
  "replace.title": "替换",
  "replace.conflict":
    "你尝试上传的文件中有一个与现有文件的名称存在冲突。是否替换现有的同名文件？",
  "replace.newFolder": "新建文件夹",
  "replace.confirmCancel": "取消",
  "replace.confirmContinue": "继续",
  "replace.confirmReplace": "替换",

  // === shareDelete ===
  "shareDelete.confirm": "你确定要删除这个分享吗？",

  // === discardEditorChanges ===
  "discardEditorChanges.confirm": "你确定要放弃所做的更改吗？",
  "discardEditorChanges.discard": "放弃更改",
  "discardEditorChanges.save": "保存更改",

  // === resolveConflict ===
  "resolveConflict.title": "解决冲突",
  "resolveConflict.replaceOrSkip": "替换或跳过",
  "resolveConflict.keepBoth":
    "如果选择保留两个版本，副本文件名将添加数字后缀。",
  "resolveConflict.sourceFile": "源位置文件",
  "resolveConflict.uploadFile": "上传文件",
  "resolveConflict.targetFile": "目标位置文件",
  "resolveConflict.rename": "重命名",
  "resolveConflict.permissionError": "权限错误",
  "resolveConflict.replace": "覆盖",
  "resolveConflict.skip": "跳过",
  "resolveConflict.conflictCount": "目标文件夹中有 {count} 个同名文件",
  "resolveConflict.replaceAll": "替换目标文件夹中的所有文件",
  "resolveConflict.renameAll": "重命名所有文件（创建副本）",
  "resolveConflict.skipAll": "跳过所有冲突文件",
  "resolveConflict.resume": "恢复传输",
  "resolveConflict.skipSmaller":
    "跳过所有冲突文件，除了服务器上较小的文件（可能传输中断）。",
  "resolveConflict.handleIndividually": "逐个处理冲突文件",
  "resolveConflict.newFolder": "新建文件夹",

  // === newFile ===
  "newFile.cancel": "取消",
  "newFile.create": "创建",

  // === newDir ===
  "newDir.cancel": "取消",
  "newDir.create": "创建",

  // === deleteUser ===
  "deleteUser.cancel": "取消",
  "deleteUser.delete": "删除",

  // === currentPassword ===
  "currentPassword.title": "当前密码",
  "currentPassword.passwordRequired": "请输入密码以确认此操作。",

  // === tagManager ===
  "tagManager.tagTitle": "管理标签",
  "tagManager.close": "关闭",
  "tagManager.tagName": "标签名称...",
  "tagManager.createTag": "创建标签",
  "tagManager.noTags": "暂无标签，创建一个吧",
  "tagManager.editTag": "编辑标签",
  "tagManager.deleteTag": "删除标签",
  "tagManager.confirmDelete": "确定删除标签「{name}」？",
  "tagManager.cancel": "取消",
  "tagManager.delete": "删除",

  // === tagPicker ===
  "tagPicker.assignTag": "分配标签",
  "tagPicker.manageTags": "管理标签",
  "tagPicker.noTags": "暂无标签，创建一个吧",

  // === extendedImage ===
  "extendedImage.close": "关闭",

  // === videoPlayer ===
  "videoPlayer.download": "下载",
  "videoPlayer.browserNotSupport":
    "您的浏览器不支持嵌入式视频播放，请下载后观看。",

  // === breadcrumbs ===
  "breadcrumbs.home": "首页",

  // === fileList ===
  "fileList.currentDir": "当前目录：",

  // === user ===
  "user.newUserTitle": "新建用户",
  "user.editingUser": "用户 {name}",
  "user.delete": "删除",
  "user.cancel": "取消",
  "user.save": "保存",

  // === global ===
  "global.rules": "规则",
  "global.brandCustomization": "品牌定制",
  "global.brandDescription": "如需自定义品牌，请参考",
  "global.officialDoc": "官方文档",
  "global.commandExecutor": "命令执行器",
  "global.commandDescription":
    "命令执行器允许在文件操作前后运行自定义命令。详情请查看",

  // === searchPage ===
  "searchPage.typeToSearch": "输入关键词搜索",
  "searchPage.searchTypes": "类型快捷入口",
  "searchPage.searching": "搜索中...",
  "searchPage.noResults": "无搜索结果",
  "searchPage.results": "搜索结果列表",
} as const;
