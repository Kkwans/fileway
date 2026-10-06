<template>
  <div
    v-show="active"
    class="overlay sidebar-overlay"
    aria-hidden="true"
    @click="closeHovers"
  ></div>
  <div
    ref="sidebarFrame"
    class="sidebar-frame"
    :class="{ active, 'is-rail': railMode, 'is-resizing': isResizing }"
  >
    <nav
      class="sidebar"
      :class="{
        active,
        'sidebar--rail': railMode,
        'is-resizing': isResizing,
        'is-scrolling': sidebarScrolling,
      }"
      @pointerdown.stop
      @scroll.passive="onSidebarScroll"
      @keydown.esc="closeRailPanel(true)"
    >
      <template v-if="isLoggedIn">
        <template v-if="railMode">
          <div ref="railRootRef" class="sidebar-icon-rail">
            <button
              type="button"
              class="sidebar-rail-action sidebar-rail-profile"
              :class="{ active: route.path.startsWith('/settings') }"
              data-tooltip="账户设置"
              title="账户设置"
              aria-label="账户设置"
              @click="toAccountSettings"
            >
              <AppIcon name="user" :size="22" />
              <span class="sidebar-rail-avatar-dot" aria-hidden="true"></span>
            </button>
            <button
              v-for="option in orderedSystemOptions"
              :key="option.id"
              type="button"
              class="sidebar-rail-action"
              :class="{ active: isSystemOptionActive(option.id) }"
              :data-tooltip="option.label"
              :title="option.label"
              :aria-label="option.label"
              @click="runSystemOption(option.id)"
            >
              <AppIcon :name="option.icon" :size="21" />
            </button>

            <div class="sidebar-rail-divider" aria-hidden="true"></div>

            <button
              type="button"
              class="sidebar-rail-action"
              :class="{ active: railPanel === 'favorites' }"
              data-tooltip="收藏夹"
              title="收藏夹"
              aria-label="打开收藏夹"
              :aria-expanded="railPanel === 'favorites'"
              @click.stop="toggleRailPanel('favorites', $event)"
            >
              <AppIcon name="star" :size="21" />
              <span
                v-if="favoritesStore.sortedFavorites.length"
                class="sidebar-rail-count"
                >{{ compactCount(favoritesStore.sortedFavorites.length) }}</span
              >
            </button>
            <button
              type="button"
              class="sidebar-rail-action"
              :class="{
                active: railPanel === 'tags' || Boolean(tagsStore.activeFilter),
              }"
              data-tooltip="标签"
              title="标签"
              aria-label="打开标签"
              :aria-expanded="railPanel === 'tags'"
              @click.stop="toggleRailPanel('tags', $event)"
            >
              <AppIcon name="tags" :size="21" />
              <span
                v-if="tagsStore.sortedTags.length"
                class="sidebar-rail-count"
                >{{ compactCount(tagsStore.sortedTags.length) }}</span
              >
            </button>
            <button
              v-if="user?.perm?.admin && categoryGroups.length > 0"
              type="button"
              class="sidebar-rail-action"
              :class="{ active: railPanel === 'categories' }"
              data-tooltip="目录分类"
              title="目录分类"
              aria-label="打开目录分类"
              :aria-expanded="railPanel === 'categories'"
              @click.stop="toggleRailPanel('categories', $event)"
            >
              <AppIcon name="categories" :size="21" />
            </button>
            <button
              v-if="user?.perm?.admin && volumesStore.displayVolumes.length > 0"
              type="button"
              class="sidebar-rail-action"
              :class="{ active: railPanel === 'volumes' }"
              data-tooltip="存储卷"
              title="存储卷"
              aria-label="打开存储卷"
              :aria-expanded="railPanel === 'volumes'"
              @click.stop="toggleRailPanel('volumes', $event)"
            >
              <AppIcon name="storage" :size="21" />
            </button>

            <div class="sidebar-rail-spacer"></div>
            <div class="sidebar-rail-footer">
              <button
                type="button"
                class="sidebar-rail-action sidebar-rail-help"
                data-tooltip="帮助"
                title="帮助"
                aria-label="帮助"
                @click="help"
              >
                <AppIcon name="info" :size="21" />
                <span class="sidebar-rail-action-label">帮助</span>
              </button>
              <button
                type="button"
                class="sidebar-rail-action sidebar-rail-expand"
                data-tooltip="展开侧边栏"
                title="展开侧边栏"
                aria-label="展开侧边栏"
                @click="toggleDesktopRail(false)"
              >
                <AppIcon name="panel-open" :size="21" />
                <span class="sidebar-rail-action-label">展开侧栏</span>
              </button>
              <button
                v-if="canLogout"
                type="button"
                class="sidebar-rail-action sidebar-rail-logout"
                data-tooltip="退出登录"
                title="退出登录"
                aria-label="退出登录"
                @click="logout"
              >
                <AppIcon name="logout" :size="21" />
                <span class="sidebar-rail-action-label">退出登录</span>
              </button>
            </div>
          </div>

          <Teleport to="body">
            <section
              v-if="railMode && railPanel"
              ref="railPopoverRef"
              class="sidebar-rail-popover"
              :style="railPopoverStyle"
              role="dialog"
              tabindex="-1"
              :aria-label="railPanelTitle"
              @click.stop
              @keydown.esc.stop="closeRailPanel(true)"
            >
              <header class="sidebar-rail-popover-header">
                <div>
                  <span>{{ railPanelTitle }}</span>
                  <small>{{ railPanelDescription }}</small>
                </div>
                <button
                  type="button"
                  aria-label="关闭浮层"
                  @click="closeRailPanel(true)"
                >
                  <AppIcon name="x" :size="18" />
                </button>
              </header>

              <div class="sidebar-rail-popover-list">
                <template v-if="railPanel === 'favorites'">
                  <button
                    v-for="fav in favoritesStore.sortedFavorites"
                    :key="fav.id"
                    type="button"
                    class="sidebar-rail-popover-item"
                    :title="fav.path"
                    @click="navigateVolume(fav.path, fav.groupId)"
                  >
                    <AppIcon
                      :name="isFileByExtension(fav.name) ? 'file' : 'folder'"
                      :size="19"
                    />
                    <span
                      ><strong>{{ fav.name }}</strong
                      ><small>{{ fav.path }}</small></span
                    >
                    <AppIcon name="chevron-right" :size="17" />
                  </button>
                  <p
                    v-if="favoritesStore.sortedFavorites.length === 0"
                    class="sidebar-rail-empty"
                  >
                    暂无收藏项目
                  </p>
                </template>

                <template v-else-if="railPanel === 'tags'">
                  <button
                    v-for="tag in orderedTags"
                    :key="tag.id"
                    type="button"
                    class="sidebar-rail-popover-item"
                    :class="{ active: tagsStore.activeFilter === tag.id }"
                    @click="filterByTag(tag.id)"
                  >
                    <span
                      class="sidebar-rail-tag-dot"
                      :style="{ background: tag.color }"
                    ></span>
                    <span
                      ><strong>{{ tag.name }}</strong
                      ><small>{{ tag.paths.length }} 个项目</small></span
                    >
                    <span class="sidebar-rail-item-count">{{
                      tag.paths.length
                    }}</span>
                  </button>
                  <p v-if="orderedTags.length === 0" class="sidebar-rail-empty">
                    暂无标签
                  </p>
                </template>

                <template v-else-if="railPanel === 'categories'">
                  <div
                    v-for="group in orderedCategoryGroups"
                    :key="group.id"
                    class="sidebar-rail-popover-group"
                  >
                    <button
                      v-if="group.id === 'nas-root'"
                      type="button"
                      class="sidebar-rail-popover-item sidebar-root-link"
                      @click="toRoot"
                    >
                      <AppIcon name="home" :size="18" /><span>根目录</span>
                    </button>
                    <div v-else class="sidebar-rail-popover-group-title">
                      <span>{{ group.name }}</span>
                    </div>
                    <button
                      v-for="path in orderedCategoryPaths(group)"
                      :key="path.path"
                      type="button"
                      class="sidebar-rail-popover-item"
                      :title="path.path"
                      @click="navigateVolume(path.path)"
                    >
                      <AppIcon name="folder" :size="19" />
                      <span
                        ><strong>{{ path.name }}</strong
                        ><small>{{ path.path }}</small></span
                      >
                      <AppIcon name="chevron-right" :size="17" />
                    </button>
                  </div>
                </template>

                <template v-else-if="railPanel === 'volumes'">
                  <button
                    v-for="volume in orderedVolumes"
                    :key="volume.path"
                    type="button"
                    class="sidebar-rail-popover-item sidebar-rail-volume-item"
                    @click="navigateVolume(volume.path)"
                  >
                    <AppIcon
                      :name="volume.icon"
                      :size="19"
                      :stroke-width="1.9"
                    />
                    <span>
                      <strong>{{ volume.displayName }}</strong>
                      <small
                        >{{ volume.usedFormatted }} /
                        {{ volume.totalFormatted }}</small
                      >
                      <i aria-hidden="true"
                        ><b
                          :style="{
                            width: volume.usedPercentage + '%',
                            background: volumeBarColor(volume.usedPercentage),
                          }"
                        ></b
                      ></i>
                    </span>
                    <span class="sidebar-rail-item-count"
                      >{{ Math.round(volume.usedPercentage) }}%</span
                    >
                  </button>
                </template>
              </div>

              <footer class="sidebar-rail-popover-footer">
                <button type="button" @click="toggleDesktopRail(false)">
                  <AppIcon name="panel-open" :size="18" />
                  展开侧边栏以管理
                </button>
              </footer>
            </section>
          </Teleport>
        </template>

        <div v-else class="sidebar-personalized-stack">
          <div
            class="sidebar-primary-nav sidebar-sortable-module"
            :class="sidebarDropClass('module', 'moduleOrder', 'user')"
            :style="moduleStyle('user')"
            @dragover.prevent="
              onSidebarDragOver($event, 'module', 'moduleOrder', 'user')
            "
            @drop="onModuleDrop('user')"
          >
            <div class="sidebar-user-row">
              <button
                type="button"
                @click="toAccountSettings"
                class="action sidebar-user-card"
                draggable="true"
                @dragstart="onModuleDragStart($event, 'user')"
                @dragend="clearSidebarDrag"
              >
                <span class="sidebar-user-icon"
                  ><AppIcon name="user" :size="20"
                /></span>
                <span>{{ user?.username }}</span>
              </button>
            </div>
          </div>

          <div
            class="system-options-section sidebar-module sidebar-sortable-module"
            :class="sidebarDropClass('module', 'moduleOrder', 'system-options')"
            :style="moduleStyle('system-options')"
            @dragover.prevent="
              onSidebarDragOver(
                $event,
                'module',
                'moduleOrder',
                'system-options'
              )
            "
            @drop="onModuleDrop('system-options')"
          >
            <SidebarSectionHeader
              icon="system-options"
              label="系统选项"
              :expanded="!collapsedSections.systemOptions"
              draggable="true"
              @dragstart="onModuleDragStart($event, 'system-options')"
              @dragend="clearSidebarDrag"
              @toggle="toggleSection('systemOptions')"
            />
            <template v-if="!collapsedSections.systemOptions">
              <button
                v-for="option in orderedSystemOptions"
                :key="option.id"
                type="button"
                class="action sidebar-command sidebar-sortable-item"
                :class="
                  sidebarDropClass('preference', 'systemOptionOrder', option.id)
                "
                draggable="true"
                @click="runSystemOption(option.id)"
                @dragstart.stop="
                  onPreferenceDragStart($event, 'systemOptionOrder', option.id)
                "
                @dragover.prevent="
                  onSidebarDragOver(
                    $event,
                    'preference',
                    'systemOptionOrder',
                    option.id
                  )
                "
                @drop.stop="onPreferenceDrop('systemOptionOrder', option.id)"
                @dragend="clearSidebarDrag"
              >
                <AppIcon :name="option.icon" :size="20" />
                <span>{{ option.label }}</span>
              </button>
            </template>
          </div>

          <!-- Favorites Section -->
          <div
            class="favorites-section sidebar-module sidebar-sortable-module"
            :class="sidebarDropClass('module', 'moduleOrder', 'favorites')"
            :style="moduleStyle('favorites')"
            @dragover.prevent="
              onSidebarDragOver($event, 'module', 'moduleOrder', 'favorites')
            "
            @drop="onModuleDrop('favorites')"
          >
            <SidebarSectionHeader
              icon="star"
              label="收藏夹"
              tone="favorite"
              :expanded="!collapsedSections.favorites"
              draggable="true"
              @dragstart="onModuleDragStart($event, 'favorites')"
              @dragend="clearSidebarDrag"
              @toggle="toggleSection('favorites')"
            >
              <template #tools>
                <button
                  class="section-action-btn"
                  type="button"
                  title="新建分组"
                  aria-label="新建收藏分组"
                  @click.stop.prevent="showCreateGroup = !showCreateGroup"
                >
                  <AppIcon name="folder-new" :size="18" />
                </button>
              </template>
            </SidebarSectionHeader>
            <template v-if="!collapsedSections.favorites">
              <!-- Create group input -->
              <div v-if="showCreateGroup" class="create-group-input">
                <input
                  v-model="newGroupName"
                  placeholder="分组名称"
                  @keyup.enter="createGroup"
                  @keyup.escape="showCreateGroup = false"
                  ref="groupInputRef"
                />
                <button
                  type="button"
                  aria-label="确认新建分组"
                  @click="createGroup"
                  :disabled="!newGroupName.trim()"
                >
                  <AppIcon name="circle-check" :size="18" />
                </button>
                <button
                  type="button"
                  aria-label="取消新建分组"
                  @click="showCreateGroup = false"
                >
                  <AppIcon name="x" :size="18" />
                </button>
              </div>
              <!-- Empty state -->
              <div
                v-if="
                  favoritesStore.sortedFavorites.length === 0 &&
                  favoritesStore.sortedGroups.length === 0
                "
                class="section-empty"
              >
                <AppIcon name="star" :size="20" :stroke-width="1.9" />
                <span>暂无收藏目录</span>
              </div>
              <!-- Ungrouped favorites -->
              <div
                class="favorites-ungrouped-drop-zone"
                :class="{
                  'favorites-ungrouped-drop-zone--empty':
                    isDraggingGroupedFavorite &&
                    favoritesStore.favoritesByGroup[''].length === 0,
                  'sidebar-drop-before': ungroupedDropActive,
                }"
                @dragover.stop.prevent="onUngroupedDragOver"
                @dragleave="ungroupedDropActive = false"
                @drop.stop="onUngroupedDrop"
              >
                <template
                  v-if="
                    favoritesStore.favoritesByGroup[''] &&
                    favoritesStore.favoritesByGroup[''].length > 0
                  "
                >
                  <button
                    v-for="fav in favoritesStore.favoritesByGroup['']"
                    :key="fav.id"
                    class="action favorite-item"
                    :class="favoriteDropClass(fav.id)"
                    draggable="true"
                    @click="navigateVolume(fav.path, fav.groupId)"
                    :title="fav.path"
                    @dragstart="onFavDragStart($event, fav.id)"
                    @dragover.stop.prevent="onFavDragOverItem($event, fav.id)"
                    @dragleave="onFavDragLeaveItem"
                    @drop.stop="onFavDropOnItem($event, fav.id)"
                    @dragend="onFavDragEnd"
                  >
                    <AppIcon
                      class="favorite-icon favorite-drag-handle"
                      name="drag"
                      :size="18"
                      :stroke-width="2"
                    />
                    <AppIcon
                      class="favorite-icon"
                      :name="favoriteIcon(fav.name)"
                      :size="20"
                      :stroke-width="1.9"
                    />
                    <div class="favorite-info">
                      <span class="favorite-name">{{ fav.name }}</span>
                      <span
                        class="favorite-path"
                        v-if="fav.path !== fav.name"
                        >{{ fav.path }}</span
                      >
                    </div>
                    <span
                      class="favorite-remove"
                      role="button"
                      tabindex="0"
                      aria-label="取消收藏"
                      title="取消收藏"
                      @click.stop.prevent="removeFavorite(fav.id)"
                      @keydown.enter.stop.prevent="removeFavorite(fav.id)"
                      @keydown.space.stop.prevent="removeFavorite(fav.id)"
                    >
                      <AppIcon name="minus" :size="14" :stroke-width="2.2" />
                    </span>
                  </button>
                </template>
              </div>
              <!-- Groups -->
              <div
                v-for="group in favoritesStore.sortedGroups"
                :key="group.id"
                class="favorite-group"
              >
                <SidebarGroupHeader
                  class="favorite-group-header"
                  icon="inventory_2"
                  app-icon="collection"
                  :label="group.name"
                  :expanded="!collapsedGroups[group.id]"
                  :color="group.color || 'var(--blue)'"
                  draggable="true"
                  @dragstart.stop="onFavoriteGroupDragStart($event, group.id)"
                  @dragend="clearSidebarDrag"
                  @toggle="toggleGroupCollapse(group.id)"
                  @dragover.stop.prevent="onFavDragOverGroup($event, group.id)"
                  @drop.stop="onFavDropOnGroup($event, group.id)"
                  @dragleave="onFavDragLeaveGroup"
                  :class="{
                    'drag-over-group':
                      dragOverGroupId === group.id && !draggedFavoriteGroupId,
                    'sidebar-drop-before':
                      draggedFavoriteGroupId &&
                      favoriteGroupDropId === group.id &&
                      favoriteGroupDropPosition === 'before',
                    'sidebar-drop-after':
                      draggedFavoriteGroupId &&
                      favoriteGroupDropId === group.id &&
                      favoriteGroupDropPosition === 'after',
                  }"
                >
                  <template #actions>
                    <button
                      class="section-action-btn"
                      type="button"
                      title="删除分组"
                      :aria-label="`删除分组 ${group.name}`"
                      @click.stop.prevent="deleteGroup(group.id)"
                    >
                      <AppIcon name="trash" :size="16" :stroke-width="1.9" />
                    </button>
                  </template>
                </SidebarGroupHeader>
                <template v-if="!collapsedGroups[group.id]">
                  <button
                    v-for="fav in favoritesStore.favoritesByGroup[group.id] ||
                    []"
                    :key="fav.id"
                    class="action favorite-item category-path-item"
                    :class="favoriteDropClass(fav.id)"
                    draggable="true"
                    @click="navigateVolume(fav.path, fav.groupId)"
                    :title="fav.path"
                    @dragstart="onFavDragStart($event, fav.id)"
                    @dragover.stop.prevent="onFavDragOverItem($event, fav.id)"
                    @dragleave="onFavDragLeaveItem"
                    @drop.stop="onFavDropOnItem($event, fav.id)"
                    @dragend="onFavDragEnd"
                  >
                    <AppIcon
                      class="favorite-icon favorite-drag-handle"
                      name="drag"
                      :size="18"
                      :stroke-width="2"
                    />
                    <AppIcon
                      class="favorite-icon"
                      :name="favoriteIcon(fav.name)"
                      :size="20"
                      :stroke-width="1.9"
                    />
                    <div class="favorite-info">
                      <span class="favorite-name">{{ fav.name }}</span>
                      <span
                        class="favorite-path"
                        v-if="fav.path !== fav.name"
                        >{{ fav.path }}</span
                      >
                    </div>
                    <span
                      class="favorite-remove"
                      role="button"
                      tabindex="0"
                      aria-label="取消收藏"
                      title="取消收藏"
                      @click.stop.prevent="removeFavorite(fav.id)"
                      @keydown.enter.stop.prevent="removeFavorite(fav.id)"
                      @keydown.space.stop.prevent="removeFavorite(fav.id)"
                    >
                      <AppIcon name="minus" :size="14" :stroke-width="2.2" />
                    </span>
                  </button>
                  <div
                    v-if="
                      (favoritesStore.favoritesByGroup[group.id] || [])
                        .length === 0
                    "
                    class="section-empty"
                  >
                    <span>该分组暂无收藏</span>
                  </div>
                </template>
              </div>
            </template>
          </div>

          <!-- Tags Filter Section -->
          <div
            class="tags-section sidebar-module sidebar-sortable-module"
            :class="sidebarDropClass('module', 'moduleOrder', 'tags')"
            :style="moduleStyle('tags')"
            @dragover.prevent="
              onSidebarDragOver($event, 'module', 'moduleOrder', 'tags')
            "
            @drop="onModuleDrop('tags')"
          >
            <SidebarSectionHeader
              icon="tags"
              label="标签"
              :expanded="!collapsedSections.tags"
              draggable="true"
              @dragstart="onModuleDragStart($event, 'tags')"
              @dragend="clearSidebarDrag"
              @toggle="toggleSection('tags')"
            >
              <template #tools>
                <button
                  class="section-action-btn"
                  type="button"
                  title="管理标签"
                  aria-label="管理标签"
                  @click.stop.prevent="openTagManager"
                >
                  <AppIcon name="settings" :size="18" />
                </button>
              </template>
            </SidebarSectionHeader>
            <template v-if="!collapsedSections.tags">
              <div
                v-if="tagsStore.sortedTags.length === 0"
                class="section-empty"
              >
                <AppIcon name="tag" :size="20" :stroke-width="1.9" />
                <span>暂无标签，创建一个吧</span>
              </div>
              <button
                v-for="tag in orderedTags"
                :key="tag.id"
                class="action tag-filter-item sidebar-sortable-item"
                :class="[
                  { active: tagsStore.activeFilter === tag.id },
                  sidebarDropClass('preference', 'tagOrder', tag.id),
                ]"
                draggable="true"
                @click="filterByTag(tag.id)"
                @dragstart.stop="
                  onPreferenceDragStart($event, 'tagOrder', tag.id)
                "
                @dragover.prevent="
                  onSidebarDragOver($event, 'preference', 'tagOrder', tag.id)
                "
                @drop.stop="onPreferenceDrop('tagOrder', tag.id)"
                @dragend="clearSidebarDrag"
              >
                <AppIcon
                  class="tag-filter-dot"
                  name="tag"
                  :size="18"
                  :stroke-width="1.9"
                  :style="{ color: tag.color }"
                />
                <span class="tag-filter-name">{{ tag.name }}</span>
                <span class="tag-filter-count">{{ tag.paths.length }}</span>
              </button>
              <button
                v-if="tagsStore.activeFilter"
                class="action tag-filter-clear"
                @click="clearTagFilter"
              >
                <AppIcon name="filter-clear" :size="18" :stroke-width="1.9" />
                <span>清除筛选</span>
              </button>
            </template>
          </div>

          <!-- Storage Volumes Section (admin only) -->
          <div
            v-if="user?.perm?.admin && volumesStore.displayVolumes.length > 0"
            class="volumes-section sidebar-module sidebar-sortable-module"
            :class="sidebarDropClass('module', 'moduleOrder', 'volumes')"
            :style="moduleStyle('volumes')"
            @dragover.prevent="
              onSidebarDragOver($event, 'module', 'moduleOrder', 'volumes')
            "
            @drop="onModuleDrop('volumes')"
          >
            <SidebarSectionHeader
              icon="storage"
              label="存储卷"
              :expanded="!collapsedSections.volumes"
              draggable="true"
              @dragstart="onModuleDragStart($event, 'volumes')"
              @dragend="clearSidebarDrag"
              @toggle="toggleSection('volumes')"
            />
            <template v-if="!collapsedSections.volumes">
              <button
                v-for="vol in orderedVolumes"
                :key="vol.path"
                class="action volume-item sidebar-sortable-item"
                :class="sidebarDropClass('preference', 'volumeOrder', vol.path)"
                draggable="true"
                @click="navigateVolume(vol.path)"
                @dragstart.stop="
                  onPreferenceDragStart($event, 'volumeOrder', vol.path)
                "
                @dragover.prevent="
                  onSidebarDragOver(
                    $event,
                    'preference',
                    'volumeOrder',
                    vol.path
                  )
                "
                @drop.stop="onPreferenceDrop('volumeOrder', vol.path)"
                @dragend="clearSidebarDrag"
              >
                <AppIcon
                  :name="vol.icon"
                  :size="20"
                  :stroke-width="1.9"
                  :style="{ color: vol.color }"
                />
                <div class="volume-info">
                  <span class="volume-name">{{ vol.displayName }}</span>
                  <div class="volume-bar-wrap">
                    <div class="volume-bar">
                      <div
                        class="volume-bar-fill"
                        :style="{
                          width: vol.usedPercentage + '%',
                          background: volumeBarColor(vol.usedPercentage),
                        }"
                      ></div>
                    </div>
                    <span class="volume-usage"
                      >{{ vol.usedFormatted }} / {{ vol.totalFormatted }}</span
                    >
                  </div>
                </div>
              </button>
            </template>
          </div>

          <!-- Category Quick Navigation (admin only) -->
          <div
            v-if="user?.perm?.admin && categoryGroups.length > 0"
            class="categories-section sidebar-module sidebar-sortable-module"
            :class="sidebarDropClass('module', 'moduleOrder', 'categories')"
            :style="moduleStyle('categories')"
            @dragover.prevent="
              onSidebarDragOver($event, 'module', 'moduleOrder', 'categories')
            "
            @drop="onModuleDrop('categories')"
          >
            <SidebarSectionHeader
              icon="categories"
              label="目录分类"
              :expanded="!collapsedSections.categories"
              draggable="true"
              @dragstart="onModuleDragStart($event, 'categories')"
              @dragend="clearSidebarDrag"
              @toggle="toggleSection('categories')"
            />
            <template v-if="!collapsedSections.categories">
              <div
                v-for="group in orderedCategoryGroups"
                :key="group.id"
                class="category-group"
              >
                <button
                  v-if="group.id === 'nas-root'"
                  type="button"
                  class="action sidebar-command sidebar-root-link"
                  :class="
                    sidebarDropClass('preference', 'categoryOrder', group.id)
                  "
                  draggable="true"
                  @dragstart.stop="
                    onPreferenceDragStart($event, 'categoryOrder', group.id)
                  "
                  @dragover.prevent="
                    onSidebarDragOver(
                      $event,
                      'preference',
                      'categoryOrder',
                      group.id
                    )
                  "
                  @drop.stop="onPreferenceDrop('categoryOrder', group.id)"
                  @dragend="clearSidebarDrag"
                  @click="toRoot"
                >
                  <AppIcon name="home" :size="18" /><span>根目录</span>
                </button>
                <SidebarGroupHeader
                  v-else
                  class="category-group-header"
                  :icon="group.icon"
                  :label="group.name"
                  :expanded="Boolean(expandedCategories[group.id])"
                  :color="group.color"
                  draggable="true"
                  :class="
                    sidebarDropClass('preference', 'categoryOrder', group.id)
                  "
                  @dragstart.stop="
                    onPreferenceDragStart($event, 'categoryOrder', group.id)
                  "
                  @dragover.prevent="
                    onSidebarDragOver(
                      $event,
                      'preference',
                      'categoryOrder',
                      group.id
                    )
                  "
                  @drop.stop="onPreferenceDrop('categoryOrder', group.id)"
                  @dragend="clearSidebarDrag"
                  @toggle="toggleCategory(group.id)"
                />
                <div v-if="expandedCategories[group.id]" class="category-paths">
                  <button
                    v-for="p in orderedCategoryPaths(group)"
                    :key="p.path"
                    class="action category-path-item sidebar-sortable-item"
                    :class="sidebarDropClass('category-path', group.id, p.path)"
                    draggable="true"
                    @click="navigateVolume(p.path)"
                    :title="p.path"
                    @dragstart.stop="
                      onCategoryPathDragStart($event, group.id, p.path)
                    "
                    @dragover.prevent="
                      onSidebarDragOver(
                        $event,
                        'category-path',
                        group.id,
                        p.path
                      )
                    "
                    @drop.stop="onCategoryPathDrop(group, p.path)"
                    @dragend="clearSidebarDrag"
                  >
                    <AppIcon
                      class="category-path-risk-icon"
                      :class="'risk-' + p.risk"
                      :name="riskIcon(p.risk)"
                      :size="18"
                      :stroke-width="1.9"
                    />
                    <div class="category-path-info">
                      <span class="category-path-name">{{ p.name }}</span>
                      <span
                        v-if="isDuplicateName(p.name, group.id)"
                        class="category-path-volume"
                        >{{ getVolumeLabel(p.path) }}</span
                      >
                      <span
                        v-else-if="p.volumeType && p.volumeType !== 'system'"
                        class="category-path-type"
                        >{{ p.volumeType }}</span
                      >
                    </div>
                  </button>
                </div>
              </div>
            </template>
          </div>

          <div
            v-if="canLogout"
            class="sidebar-footer-actions sidebar-sortable-module"
            :class="sidebarDropClass('module', 'moduleOrder', 'logout')"
            :style="moduleStyle('logout')"
            draggable="true"
            @dragstart="onModuleDragStart($event, 'logout')"
            @dragover.prevent="
              onSidebarDragOver($event, 'module', 'moduleOrder', 'logout')
            "
            @drop="onModuleDrop('logout')"
            @dragend="clearSidebarDrag"
          >
            <div v-if="isDesktopViewport" class="sidebar-collapse-row">
              <IconButton
                class="sidebar-collapse-control"
                icon="panel-close"
                label="折叠为图标侧栏"
                :icon-size="20"
                @click="toggleDesktopRail(true)"
              >
                <span class="sidebar-collapse-label">折叠侧栏</span>
              </IconButton>
            </div>
            <button
              @click="logout"
              class="action sidebar-command"
              id="logout"
              aria-label="退出登录"
              title="退出登录"
            >
              <AppIcon name="logout" :size="20" />
              <span>退出登录</span>
            </button>
          </div>
        </div>
      </template>
      <template v-else>
        <router-link
          v-if="!hideLoginButton"
          class="action"
          to="/login"
          aria-label="登录"
          title="登录"
        >
          <AppIcon name="login" :size="20" />
          <span>登录</span>
        </router-link>

        <router-link
          v-if="signup"
          class="action"
          to="/login"
          aria-label="注册"
          title="注册"
        >
          <AppIcon name="user-add" :size="20" />
          <span>注册</span>
        </router-link>
      </template>

      <p v-if="!railMode" class="credits">
        <span>
          <a
            rel="noopener noreferrer"
            target="_blank"
            href="https://github.com/Kkwans/nas-file-browser"
            >NAS File Browser</a
          >
        </span>
        <span>
          <a @click="help">帮助</a>
        </span>
      </p>
    </nav>
    <div
      v-if="isDesktopViewport && !railMode"
      class="sidebar-resize-handle"
      role="separator"
      tabindex="0"
      aria-label="调整侧栏宽度"
      aria-orientation="vertical"
      :aria-valuemin="SIDEBAR_MIN_WIDTH"
      :aria-valuemax="SIDEBAR_MAX_WIDTH"
      :aria-valuenow="sidebarWidth"
      title="拖拽调整宽度；方向键微调，双击复位"
      @pointerdown="startResize"
      @pointermove="onResize"
      @pointerup="stopResize"
      @pointercancel="cancelResize"
      @lostpointercapture="stopResize"
      @keydown="resizeByKeyboard"
      @dblclick="resetSidebarWidth"
    />
  </div>
</template>

<script setup lang="ts">
import {
  computed,
  inject,
  nextTick,
  onMounted,
  onUnmounted,
  reactive,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { useAuthStore } from "@/stores/auth";
import { useLayoutStore } from "@/stores/layout";
import { useVolumesStore } from "@/stores/volumes";
import { useCategoriesStore } from "@/stores/categories";
import type { CategoryGroup } from "@/api/categories";
import { useFavoritesStore } from "@/stores/favorites";
import { useTagsStore } from "@/stores/tags";
import { useTrashStore } from "@/stores/trash";
import { useRecentStore } from "@/stores/recent";
import { useSidebarPreferencesStore } from "@/stores/sidebarPreferences";
import SidebarSectionHeader from "@/components/sidebar/SidebarSectionHeader.vue";
import SidebarGroupHeader from "@/components/sidebar/SidebarGroupHeader.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import IconButton from "@/components/ui/IconButton.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";

import * as auth from "@/utils/auth";
import { getResourceIconName, isFileByExtension } from "@/utils/fileIcons";
import { resolveRiskIcon } from "@/utils/sidebarIconSemantics";
import {
  getFavoriteDropPosition,
  type FavoriteDropPosition,
} from "@/utils/sidebarFavorites";
import type {
  SidebarModuleId,
  SidebarPreferences,
  SystemOptionId,
} from "@/utils/sidebarPreferences";
import {
  signup,
  hideLoginButton,
  noAuth,
  logoutPage,
  loginPage,
} from "@/utils/constants";
import {
  buildFilesRouteFromSearchBase,
  normalizeFilesRouteBase,
} from "@/utils/searchPath";

const $showError = inject<IToastError>("$showError")!;
const route = useRoute();
const router = useRouter();

const authStore = useAuthStore();
const layoutStore = useLayoutStore();
const volumesStore = useVolumesStore();
const categoriesStore = useCategoriesStore();
const favoritesStore = useFavoritesStore();
const tagsStore = useTagsStore();
const trashStore = useTrashStore();
const recentStore = useRecentStore();
const sidebarPreferencesStore = useSidebarPreferencesStore();

const { closeHovers, showHover } = layoutStore;
const { user, isLoggedIn } = storeToRefs(authStore);
const { currentPromptName } = storeToRefs(layoutStore);

// State

const expandedCategories = reactive<Record<string, boolean>>({});
const collapsedSections = reactive({
  systemOptions: true,
  favorites: false,
  tags: true,
  volumes: false,
  categories: true,
});
// Load collapsed state from localStorage
try {
  const saved = localStorage.getItem("nas-file-browser-collapsed-sections-v2");
  if (saved) {
    const parsed = JSON.parse(saved);
    Object.assign(collapsedSections, parsed);
  }
} catch {}

const showCreateGroup = ref(false);
const newGroupName = ref("");
const collapsedGroups = reactive<Record<string, boolean>>({});
try {
  const savedGroups = localStorage.getItem(
    "nas-file-browser-collapsed-favorite-groups"
  );
  if (savedGroups) Object.assign(collapsedGroups, JSON.parse(savedGroups));
} catch {}
const dragOverGroupId = ref("");
const draggedFavId = ref("");
const dragOverFavoriteId = ref("");
const dragOverFavoritePosition = ref<FavoriteDropPosition>("before");
const draggedFavoriteGroupId = ref("");
const favoriteGroupDropId = ref("");
const favoriteGroupDropPosition = ref<FavoriteDropPosition>("before");
const ungroupedDropActive = ref(false);
const sidebarDropTarget = ref<{
  kind: "module" | "preference" | "category-path";
  key: string;
  id: string;
  position: FavoriteDropPosition;
} | null>(null);
const draggedModuleId = ref<SidebarModuleId | "">("");
const draggedPreference = ref<{
  key: Exclude<keyof SidebarPreferences, "categoryPathOrder">;
  id: string;
} | null>(null);
const draggedCategoryPath = ref<{ groupId: string; path: string } | null>(null);
const SIDEBAR_MIN_WIDTH = 240;
const SIDEBAR_DEFAULT_WIDTH = 288;
const SIDEBAR_MAX_WIDTH = 500;
const clampSidebarWidth = (value: number) =>
  Math.min(
    SIDEBAR_MAX_WIDTH,
    Math.max(
      SIDEBAR_MIN_WIDTH,
      Number.isFinite(value) ? value : SIDEBAR_DEFAULT_WIDTH
    )
  );
let savedSidebarWidth = SIDEBAR_DEFAULT_WIDTH;
try {
  savedSidebarWidth = Number(
    localStorage.getItem("nas-file-browser-sidebar-width") ||
      String(SIDEBAR_DEFAULT_WIDTH)
  );
} catch {
  /* Use the default when storage is blocked. */
}
const sidebarWidth = ref(clampSidebarWidth(savedSidebarWidth));
const sidebarFrame = ref<HTMLElement | null>(null);
const isResizing = ref(false);
const sidebarScrolling = ref(false);
let sidebarScrollTimer: ReturnType<typeof setTimeout> | undefined;
const startX = ref(0);
const startWidth = ref(0);
const groupInputRef = ref<HTMLInputElement | null>(null);
type RailPanel = "favorites" | "tags" | "categories" | "volumes";
type SidebarOrderKey = Exclude<
  keyof SidebarPreferences,
  "categoryPathOrder" | "desktopCollapsed"
>;

const isDesktopViewport = ref(window.matchMedia("(min-width: 900px)").matches);
const railPanel = ref<RailPanel | "">("");
const railPanelTop = ref(64);
const railRootRef = ref<HTMLElement | null>(null);
const railPopoverRef = ref<HTMLElement | null>(null);
const railTriggerElement = ref<HTMLButtonElement | null>(null);
const desktopMediaQuery = window.matchMedia("(min-width: 900px)");

// Computed
const active = computed(() => currentPromptName.value === "sidebar");
const railMode = computed(
  () => isDesktopViewport.value && sidebarPreferencesStore.desktopCollapsed
);
const canLogout = computed(
  () => !noAuth && (loginPage || logoutPage !== "/login")
);

const systemOptions = computed<
  Array<{ id: SystemOptionId; icon: AppIconName; label: string }>
>(() => [
  {
    id: "files",
    icon: "folder",
    label: "我的文件",
  },
  { id: "search", icon: "search", label: "搜索" },
  { id: "recent", icon: "clock", label: "最近访问" },
  { id: "trash", icon: "trash", label: "回收站" },
  { id: "analysis", icon: "chart-storage", label: "存储工具" },
  { id: "tasks", icon: "tasks", label: "任务中心" },
]);

const orderedSystemOptions = computed(() =>
  sidebarPreferencesStore.ordered(
    systemOptions.value.filter(
      (option) => option.id !== "files" || !user.value?.perm?.admin
    ),
    "systemOptionOrder",
    (option) => option.id
  )
);

const orderedTags = computed(() =>
  sidebarPreferencesStore.ordered(
    tagsStore.sortedTags,
    "tagOrder",
    (tag) => tag.id
  )
);

const orderedVolumes = computed(() =>
  sidebarPreferencesStore.ordered(
    volumesStore.displayVolumes,
    "volumeOrder",
    (volume) => volume.path
  )
);

const categoryGroups = computed(() => {
  const subDirs = volumesStore.allSubDirs;
  if (!user.value?.perm?.admin) return [];

  const groups: Record<string, CategoryGroup> = {};
  const catOrder = ["personal", "shared", "system", "other"];

  for (const cat of categoriesStore.categories) {
    if (!groups[cat.id]) {
      groups[cat.id] = {
        id: cat.id,
        name: cat.name,
        icon: cat.icon,
        color: cat.color,
        paths: [],
      };
    }
  }

  for (const dir of subDirs) {
    const cat = categoriesStore.classifyPath(dir.path);
    if (groups[cat.id]) {
      groups[cat.id].paths.push({
        path: dir.path,
        name: dir.name,
        risk: dir.risk,
        volumeType: "",
      });
    }
  }

  const root: CategoryGroup = {
    id: "nas-root",
    name: "根目录",
    icon: "home",
    color: "var(--blue)",
    paths: [],
  };
  return [
    root,
    ...catOrder
      .filter((id) => groups[id] && groups[id].paths.length > 0)
      .map((id) => groups[id]),
  ];
});

const orderedCategoryGroups = computed(() =>
  sidebarPreferencesStore.ordered(
    categoryGroups.value,
    "categoryOrder",
    (group) => group.id
  )
);

const visibleModuleIds = computed<SidebarModuleId[]>(() => {
  const ids: SidebarModuleId[] = [
    "user",
    "system-options",
    "favorites",
    "tags",
  ];
  if (user.value?.perm?.admin && categoryGroups.value.length > 0) {
    ids.push("categories");
  }
  if (user.value?.perm?.admin && volumesStore.displayVolumes.length > 0) {
    ids.push("volumes");
  }
  if (canLogout.value) ids.push("logout");
  return ids;
});

const railPanelTitle = computed(() => {
  if (railPanel.value === "favorites") return "收藏夹";
  if (railPanel.value === "tags") return "标签";
  if (railPanel.value === "categories") return "目录分类";
  if (railPanel.value === "volumes") return "存储卷";
  return "";
});

const railPanelDescription = computed(() => {
  if (railPanel.value === "favorites") return "快速打开收藏的文件与目录";
  if (railPanel.value === "tags") return "按标签筛选当前文件范围";
  if (railPanel.value === "categories") return "按 NAS 目录语义快速定位";
  if (railPanel.value === "volumes") return "查看容量并进入存储卷";
  return "";
});

const railPopoverStyle = computed(() => ({
  top: `${railPanelTop.value}px`,
}));

const compactCount = (count: number) => (count > 99 ? "99+" : String(count));

const isSystemOptionActive = (id: SystemOptionId) => {
  const path = route.path;
  if (id === "files") return path.startsWith("/files");
  if (id === "search") return path === "/search";
  if (id === "recent") return path === "/recent";
  if (id === "trash") return path === "/trash";
  if (id === "analysis") return path === "/analysis";
  if (id === "new-directory") return currentPromptName.value === "newDir";
  if (id === "new-file") return currentPromptName.value === "newFile";
  return false;
};

const closeRailPanel = (restoreFocus = false) => {
  railPanel.value = "";
  if (restoreFocus) {
    void nextTick(() => railTriggerElement.value?.focus());
  }
};

const toggleRailPanel = async (panel: RailPanel, event: MouseEvent) => {
  if (railPanel.value === panel) {
    closeRailPanel(true);
    return;
  }
  railTriggerElement.value = event.currentTarget as HTMLButtonElement;
  const rect = railTriggerElement.value.getBoundingClientRect();
  railPanelTop.value = Math.max(
    60,
    Math.min(rect.top - 8, window.innerHeight - 520)
  );
  railPanel.value = panel;
  await nextTick();
  railPopoverRef.value?.focus();
};

const toggleDesktopRail = async (collapsed: boolean) => {
  closeRailPanel();
  try {
    await sidebarPreferencesStore.setDesktopCollapsed(collapsed);
  } catch (error) {
    $showError(error as Error);
  }
};

const applySidebarWidth = () => {
  document.documentElement.style.setProperty(
    "--sidebar-width",
    `${railMode.value ? 72 : sidebarWidth.value}px`
  );
};

const updateDesktopViewport = (event?: MediaQueryListEvent) => {
  isDesktopViewport.value = event?.matches ?? desktopMediaQuery.matches;
  if (!isDesktopViewport.value) closeRailPanel();
  applySidebarWidth();
};

const onDocumentPointerDown = (event: PointerEvent) => {
  const target = event.target as Node | null;
  if (!target || !railPanel.value) return;
  if (
    railRootRef.value?.contains(target) ||
    railPopoverRef.value?.contains(target)
  ) {
    return;
  }
  closeRailPanel();
};

// Methods
const moduleStyle = (id: SidebarModuleId) => ({
  order: sidebarPreferencesStore.moduleOrder.indexOf(id),
});

const orderedCategoryPaths = (group: CategoryGroup) =>
  sidebarPreferencesStore.orderedCategoryPaths(
    group.id,
    group.paths,
    (path) => path.path
  );

const onModuleDragStart = (event: DragEvent, id: SidebarModuleId) => {
  clearSidebarDrag();
  draggedModuleId.value = id;
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", `sidebar-module:${id}`);
  }
};

const onModuleDrop = async (targetId: SidebarModuleId) => {
  if (!draggedModuleId.value || draggedModuleId.value === targetId) return;
  await sidebarPreferencesStore.reorder(
    "moduleOrder",
    visibleModuleIds.value,
    draggedModuleId.value,
    targetId,
    sidebarDropTarget.value?.position ?? "before"
  );
  clearSidebarDrag();
};

const onSidebarDragOver = (
  event: DragEvent,
  kind: "module" | "preference" | "category-path",
  key: string,
  id: string
) => {
  const valid =
    (kind === "module" && Boolean(draggedModuleId.value)) ||
    (kind === "preference" && draggedPreference.value?.key === key) ||
    (kind === "category-path" && draggedCategoryPath.value?.groupId === key);
  if (!valid) {
    sidebarDropTarget.value = null;
    if (event.dataTransfer) event.dataTransfer.dropEffect = "none";
    return;
  }
  event.stopPropagation();
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
  sidebarDropTarget.value = {
    kind,
    key,
    id,
    position: getFavoriteDropPosition(event.clientY, rect.top, rect.height),
  };
  if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
};

const sidebarDropClass = (
  kind: "module" | "preference" | "category-path",
  key: string,
  id: string
) => ({
  "sidebar-drop-before":
    sidebarDropTarget.value?.kind === kind &&
    sidebarDropTarget.value.key === key &&
    sidebarDropTarget.value.id === id &&
    sidebarDropTarget.value.position === "before",
  "sidebar-drop-after":
    sidebarDropTarget.value?.kind === kind &&
    sidebarDropTarget.value.key === key &&
    sidebarDropTarget.value.id === id &&
    sidebarDropTarget.value.position === "after",
});

const onPreferenceDragStart = (
  event: DragEvent,
  key: SidebarOrderKey,
  id: string
) => {
  clearSidebarDrag();
  draggedPreference.value = { key, id };
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", `sidebar-item:${key}:${id}`);
  }
};

const preferenceIds = (key: SidebarOrderKey) => {
  if (key === "systemOptionOrder") {
    return orderedSystemOptions.value.map((option) => option.id);
  }
  if (key === "tagOrder") return orderedTags.value.map((tag) => tag.id);
  if (key === "categoryOrder") {
    return orderedCategoryGroups.value.map((group) => group.id);
  }
  if (key === "volumeOrder") {
    return orderedVolumes.value.map((volume) => volume.path);
  }
  return visibleModuleIds.value;
};

const onPreferenceDrop = async (key: SidebarOrderKey, targetId: string) => {
  const dragged = draggedPreference.value;
  if (!dragged || dragged.key !== key || dragged.id === targetId) return;
  await sidebarPreferencesStore.reorder(
    key,
    preferenceIds(key),
    dragged.id,
    targetId,
    sidebarDropTarget.value?.position ?? "before"
  );
  clearSidebarDrag();
};

const onCategoryPathDragStart = (
  event: DragEvent,
  groupId: string,
  path: string
) => {
  clearSidebarDrag();
  draggedCategoryPath.value = { groupId, path };
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData(
      "text/plain",
      `sidebar-category-path:${groupId}:${path}`
    );
  }
};

const onCategoryPathDrop = async (group: CategoryGroup, targetPath: string) => {
  const dragged = draggedCategoryPath.value;
  if (!dragged || dragged.groupId !== group.id || dragged.path === targetPath) {
    return;
  }
  await sidebarPreferencesStore.reorderCategoryPath(
    group.id,
    orderedCategoryPaths(group).map((path) => path.path),
    dragged.path,
    targetPath,
    sidebarDropTarget.value?.position ?? "before"
  );
  clearSidebarDrag();
};

const clearSidebarDrag = () => {
  draggedModuleId.value = "";
  draggedPreference.value = null;
  draggedCategoryPath.value = null;
  draggedFavoriteGroupId.value = "";
  favoriteGroupDropId.value = "";
  ungroupedDropActive.value = false;
  sidebarDropTarget.value = null;
  onFavDragEnd();
};

const runSystemOption = (id: SystemOptionId) => {
  closeRailPanel();
  if (id === "files") toRoot();
  else if (id === "search") openSearch();
  else if (id === "recent") {
    router.push({ path: "/recent" });
    closeHovers();
  } else if (id === "trash") {
    router.push({ path: "/trash" });
    closeHovers();
  } else if (id === "analysis") {
    router.push({ path: "/analysis" });
    closeHovers();
  } else if (id === "tasks") {
    router.push({ path: "/tasks" });
    closeHovers();
  } else if (id === "new-directory") showHover("newDir");
  else showHover("newFile");
};

let resizingElement: HTMLElement | null = null;
let resizingPointer: number | null = null;
let previousCursor = "";
let previousUserSelect = "";
const persistSidebarWidth = () => {
  try {
    localStorage.setItem(
      "nas-file-browser-sidebar-width",
      String(sidebarWidth.value)
    );
  } catch {
    /* Keep the current width in memory. */
  }
};
const setSidebarWidth = (width: number) => {
  sidebarWidth.value = clampSidebarWidth(width);
  applySidebarWidth();
};
const startResize = (event: PointerEvent) => {
  if (!isDesktopViewport.value || railMode.value || event.button !== 0) return;
  event.preventDefault();
  resizingElement = event.currentTarget as HTMLElement;
  resizingElement.focus();
  resizingPointer = event.pointerId;
  resizingElement.setPointerCapture(event.pointerId);
  isResizing.value = true;
  startX.value = event.clientX;
  startWidth.value =
    sidebarFrame.value?.getBoundingClientRect().width ?? sidebarWidth.value;
  previousCursor = document.body.style.cursor;
  previousUserSelect = document.body.style.userSelect;
  document.body.style.cursor = "col-resize";
  document.body.style.userSelect = "none";
};
const onResize = (event: PointerEvent) => {
  if (!isResizing.value || event.pointerId !== resizingPointer) return;
  const direction = document.documentElement.dir === "rtl" ? -1 : 1;
  setSidebarWidth(
    startWidth.value + (event.clientX - startX.value) * direction
  );
};
const stopResize = () => {
  if (!isResizing.value) return;
  isResizing.value = false;
  if (
    resizingPointer !== null &&
    resizingElement?.hasPointerCapture(resizingPointer)
  )
    resizingElement.releasePointerCapture(resizingPointer);
  resizingElement = null;
  resizingPointer = null;
  document.body.style.cursor = previousCursor;
  document.body.style.userSelect = previousUserSelect;
  persistSidebarWidth();
};
const cancelResize = () => {
  if (!isResizing.value) return;
  setSidebarWidth(startWidth.value);
  stopResize();
};
const resetSidebarWidth = () => {
  setSidebarWidth(SIDEBAR_DEFAULT_WIDTH);
  persistSidebarWidth();
};
const resizeByKeyboard = (event: KeyboardEvent) => {
  if (event.key === "Escape") {
    if (isResizing.value) {
      event.preventDefault();
      event.stopPropagation();
    }
    cancelResize();
    return;
  }
  if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
  event.preventDefault();
  const direction = document.documentElement.dir === "rtl" ? -1 : 1;
  const delta =
    (event.key === "ArrowRight" ? 1 : -1) *
    direction *
    (event.shiftKey ? 1 : 10);
  setSidebarWidth(
    event.key === "Home"
      ? SIDEBAR_MIN_WIDTH
      : event.key === "End"
        ? SIDEBAR_MAX_WIDTH
        : sidebarWidth.value + delta
  );
  persistSidebarWidth();
};

const onSidebarScroll = () => {
  sidebarScrolling.value = true;
  if (sidebarScrollTimer) clearTimeout(sidebarScrollTimer);
  sidebarScrollTimer = setTimeout(() => {
    sidebarScrolling.value = false;
  }, 700);
};

const volumeBarColor = (percent: number) => {
  if (percent >= 90) return "var(--icon-red, #DA4453)";
  if (percent >= 70) return "var(--icon-orange, #F5A623)";
  return "var(--blue, #2196F3)";
};

const toggleCategory = (id: string) => {
  expandedCategories[id] = !expandedCategories[id];
};

const toggleSection = (id: keyof typeof collapsedSections) => {
  collapsedSections[id] = !collapsedSections[id];
  try {
    localStorage.setItem(
      "nas-file-browser-collapsed-sections-v2",
      JSON.stringify(collapsedSections)
    );
  } catch {}
};

const riskIcon = (risk: string) => {
  return resolveRiskIcon(risk);
};

const navigateVolume = (path: string, favoriteGroupId = "") => {
  closeRailPanel();
  const isFile = isFileByExtension(path);
  const url = isFile ? "/files" + path : "/files" + path + "/";
  router.push({
    path: url,
    query: isFile && favoriteGroupId ? { mediaQueue: favoriteGroupId } : {},
  });
  closeHovers();
};

const removeFavorite = (id: string) => {
  favoritesStore.removeFavorite(id);
};

const favoriteIcon = (name: string) => {
  return isFileByExtension(name)
    ? getResourceIconName(name, "", false)
    : ("folder" as const);
};

const createGroup = async () => {
  const name = newGroupName.value.trim();
  if (!name) return;
  await favoritesStore.addGroup(name);
  newGroupName.value = "";
  showCreateGroup.value = false;
};

const deleteGroup = async (id: string) => {
  const result = await favoritesStore.deleteGroup(id);
  if (!result.ok) {
    $showError(new Error("删除收藏夹分组失败"));
  }
};

const toggleGroupCollapse = (id: string) => {
  collapsedGroups[id] = !collapsedGroups[id];
  try {
    localStorage.setItem(
      "nas-file-browser-collapsed-favorite-groups",
      JSON.stringify(collapsedGroups)
    );
  } catch {}
};

const onFavDragStart = (event: DragEvent, favId: string) => {
  clearSidebarDrag();
  draggedFavId.value = favId;
  event.dataTransfer!.effectAllowed = "move";
  event.dataTransfer!.setData("text/plain", favId);
};

const isDraggingGroupedFavorite = computed(() => {
  if (!draggedFavId.value) return false;
  const favorite = favoritesStore.favorites.find(
    (item) => item.id === draggedFavId.value
  );
  return Boolean(favorite?.groupId);
});

const onFavDragOverItem = (event: DragEvent, favoriteId: string) => {
  event.stopPropagation();
  if (!draggedFavId.value) {
    dragOverFavoriteId.value = "";
    if (event.dataTransfer) event.dataTransfer.dropEffect = "none";
    return;
  }
  if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
  const element = event.currentTarget as HTMLElement;
  const rect = element.getBoundingClientRect();
  dragOverFavoriteId.value = favoriteId;
  dragOverFavoritePosition.value = getFavoriteDropPosition(
    event.clientY,
    rect.top,
    rect.height
  );
};

const onFavDragLeaveItem = (event: DragEvent) => {
  const element = event.currentTarget as HTMLElement;
  if (!element.contains(event.relatedTarget as Node)) {
    dragOverFavoriteId.value = "";
  }
};

const onFavDropOnItem = async (event: DragEvent, targetId: string) => {
  event.preventDefault();
  event.stopPropagation();
  try {
    if (draggedFavId.value && draggedFavId.value !== targetId) {
      await favoritesStore.moveAndReorderFavorite(
        draggedFavId.value,
        targetId,
        dragOverFavoritePosition.value
      );
    }
  } finally {
    clearSidebarDrag();
  }
};

const favoriteDropClass = (favoriteId: string) => ({
  "sidebar-drop-before":
    dragOverFavoriteId.value === favoriteId &&
    dragOverFavoritePosition.value === "before",
  "sidebar-drop-after":
    dragOverFavoriteId.value === favoriteId &&
    dragOverFavoritePosition.value === "after",
});

const onFavDragOverGroup = (event: DragEvent, groupId: string) => {
  event.stopPropagation();
  if (!draggedFavoriteGroupId.value && !draggedFavId.value) {
    dragOverGroupId.value = "";
    favoriteGroupDropId.value = "";
    if (event.dataTransfer) event.dataTransfer.dropEffect = "none";
    return;
  }
  if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
  if (draggedFavoriteGroupId.value) {
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
    favoriteGroupDropId.value = groupId;
    favoriteGroupDropPosition.value = getFavoriteDropPosition(
      event.clientY,
      rect.top,
      rect.height
    );
    return;
  }
  dragOverGroupId.value = groupId;
};

const onFavDragLeaveGroup = (event: DragEvent) => {
  if (
    !(event.currentTarget as HTMLElement)?.contains(event.relatedTarget as Node)
  ) {
    dragOverGroupId.value = "";
  }
};

const onFavDropOnGroup = async (event: DragEvent, groupId: string) => {
  event.preventDefault();
  event.stopPropagation();
  try {
    if (draggedFavoriteGroupId.value) {
      const fromIndex = favoritesStore.sortedGroups.findIndex(
        (group) => group.id === draggedFavoriteGroupId.value
      );
      const toIndex = favoritesStore.sortedGroups.findIndex(
        (group) => group.id === groupId
      );
      if (fromIndex >= 0 && toIndex >= 0 && fromIndex !== toIndex) {
        let destination = toIndex;
        if (
          favoriteGroupDropPosition.value === "after" &&
          fromIndex > toIndex
        ) {
          destination++;
        } else if (
          favoriteGroupDropPosition.value === "before" &&
          fromIndex < toIndex
        ) {
          destination--;
        }
        await favoritesStore.reorderGroups(fromIndex, destination);
      }
      return;
    }
    if (draggedFavId.value) {
      await favoritesStore.moveFavoriteToGroup(draggedFavId.value, groupId);
    }
  } finally {
    clearSidebarDrag();
  }
};

const onUngroupedDragOver = (event: DragEvent) => {
  event.stopPropagation();
  if (!draggedFavId.value) {
    ungroupedDropActive.value = false;
    if (event.dataTransfer) event.dataTransfer.dropEffect = "none";
    return;
  }
  const favorite = favoritesStore.favorites.find(
    (item) => item.id === draggedFavId.value
  );
  if (!favorite?.groupId) {
    ungroupedDropActive.value = false;
    if (event.dataTransfer) event.dataTransfer.dropEffect = "none";
    return;
  }
  ungroupedDropActive.value = true;
  if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
};

const onUngroupedDrop = async (event: DragEvent) => {
  event.preventDefault();
  event.stopPropagation();
  try {
    if (draggedFavId.value) {
      await favoritesStore.moveFavoriteToGroup(draggedFavId.value, "");
    }
  } finally {
    clearSidebarDrag();
  }
};

const onFavoriteGroupDragStart = (event: DragEvent, groupId: string) => {
  clearSidebarDrag();
  draggedFavoriteGroupId.value = groupId;
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", `favorite-group:${groupId}`);
  }
};

const onFavDragEnd = () => {
  dragOverGroupId.value = "";
  dragOverFavoriteId.value = "";
  draggedFavId.value = "";
  ungroupedDropActive.value = false;
};

const openTagManager = () => {
  showHover({ prompt: "tag-manager" });
};

const filterByTag = (tagId: string) => {
  closeRailPanel();
  if (tagsStore.activeFilter === tagId) {
    clearTagFilter();
    return;
  }
  tagsStore.setFilterMode("current");
  tagsStore.setFilter(tagId);
  const base = normalizeFilesRouteBase(route.path);
  router.push({
    path: "/search",
    query: {
      tag: tagId,
      base: base.endsWith("/") ? base : base + "/",
      scope: "current",
    },
  });
  closeHovers();
};

const clearTagFilter = () => {
  closeRailPanel();
  tagsStore.setFilter(null);
  if (typeof route.query.tag === "string") {
    const base = typeof route.query.base === "string" ? route.query.base : "/";
    router.push({ path: buildFilesRouteFromSearchBase(base) });
  }
};

const openSearch = () => {
  const base = normalizeFilesRouteBase(route.path);
  router.push({
    path: "/search",
    query: { base: base.endsWith("/") ? base : `${base}/`, scope: "current" },
  });
  closeHovers();
};

const isDuplicateName = (name: string, groupId: string) => {
  const group = categoryGroups.value.find((g) => g.id === groupId);
  if (!group) return false;
  return group.paths.filter((p) => p.name === name).length > 1;
};

const getVolumeLabel = (path: string) => {
  const match = path.match(/^\/(volume\d+)/);
  if (match) return match[1];
  const parts = path.split("/").filter(Boolean);
  if (parts.length > 0) return parts[0];
  return "";
};

const toRoot = () => {
  closeRailPanel();
  router.push({ path: "/files" });
  closeHovers();
};

const toAccountSettings = () => {
  closeRailPanel();
  router.push({ path: "/settings/profile" });
  closeHovers();
};

const help = () => {
  showHover("help");
};

const logout = () => {
  closeRailPanel();
  auth.logout();
};

// Lifecycle
let loadedUserId: number | null = null;

watch(() => [railMode.value, sidebarWidth.value] as const, applySidebarWidth, {
  immediate: true,
});

watch([railMode, isDesktopViewport], ([rail, desktop]) => {
  if (rail || !desktop) stopResize();
});

watch(
  () => route.fullPath,
  () => closeRailPanel()
);

watch(
  () => user.value?.id,
  async (userId) => {
    if (!userId) {
      loadedUserId = null;
      favoritesStore.favorites = [];
      favoritesStore.groups = [];
      tagsStore.tags = [];
      tagsStore.activeFilter = null;
      trashStore.resetForUser();
      recentStore.resetForUser();
      return;
    }
    if (loadedUserId === userId) return;

    loadedUserId = userId;
    trashStore.resetForUser();
    recentStore.resetForUser();
    await Promise.all([
      favoritesStore.loadFavorites(),
      tagsStore.loadTags(),
      sidebarPreferencesStore.load(),
    ]);
    if (user.value?.id !== userId) return;
    if (user.value?.perm?.admin) {
      volumesStore.fetchVolumes();
      categoriesStore.fetchCategories();
    }
  },
  { immediate: true }
);

watch(
  () => favoritesStore.sortedGroups.map((group) => group.id),
  (groupIds) => {
    for (const groupId of groupIds) {
      if (!(groupId in collapsedGroups)) collapsedGroups[groupId] = true;
    }
  },
  { immediate: true }
);

onMounted(() => {
  desktopMediaQuery.addEventListener("change", updateDesktopViewport);
  document.addEventListener("pointerdown", onDocumentPointerDown);
  updateDesktopViewport();
});

onUnmounted(() => {
  if (sidebarScrollTimer) clearTimeout(sidebarScrollTimer);
  desktopMediaQuery.removeEventListener("change", updateDesktopViewport);
  document.removeEventListener("pointerdown", onDocumentPointerDown);
  stopResize();
});
</script>
