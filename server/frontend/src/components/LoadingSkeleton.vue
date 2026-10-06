<template>
  <div class="skeleton-container" :class="viewMode">
    <!-- Section header skeleton -->
    <div class="skeleton-header">
      <div class="skeleton-bone skeleton-header-text"></div>
    </div>
    <!-- Item skeletons -->
    <div class="skeleton-listing">
      <div v-for="n in count" :key="n" class="skeleton-item">
        <div class="skeleton-bone skeleton-icon"></div>
        <div class="skeleton-info">
          <div
            class="skeleton-bone skeleton-name"
            :style="{ width: nameWidth(n) }"
          ></div>
          <div class="skeleton-meta">
            <div class="skeleton-bone skeleton-size"></div>
            <div class="skeleton-bone skeleton-modified"></div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    count?: number;
    viewMode?: string;
  }>(),
  {
    count: 12,
    viewMode: "mosaic",
  }
);

// Vary name widths for a natural look
const nameWidth = (index: number): string => {
  const widths = [
    "65%",
    "80%",
    "55%",
    "70%",
    "60%",
    "75%",
    "50%",
    "85%",
    "68%",
    "72%",
    "58%",
    "78%",
  ];
  return widths[(index - 1) % widths.length];
};
</script>

<style scoped>
.skeleton-container {
  padding: 0.5em 1em;
}

/* --- Skeleton bone pulse animation --- */
.skeleton-bone {
  background: var(--surfaceSecondary, #e8e8e8);
  border-radius: 4px;
  position: relative;
  overflow: hidden;
}

.dark-mode .skeleton-bone,
[data-theme="dark"] .skeleton-bone {
  background: var(--surfaceSecondary, #2a2a2a);
}

.skeleton-bone::after {
  content: "";
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(
    90deg,
    transparent 0%,
    rgba(255, 255, 255, 0.15) 50%,
    transparent 100%
  );
  animation: skeleton-shimmer 1.8s ease-in-out infinite;
}

.dark-mode .skeleton-bone::after,
[data-theme="dark"] .skeleton-bone::after {
  background: linear-gradient(
    90deg,
    transparent 0%,
    rgba(255, 255, 255, 0.06) 50%,
    transparent 100%
  );
}

@keyframes skeleton-shimmer {
  0% {
    transform: translateX(-100%);
  }
  100% {
    transform: translateX(100%);
  }
}

/* --- Header skeleton --- */
.skeleton-header {
  margin: 1.5em 0 0.5em 0.5em;
}

.skeleton-header-text {
  width: 6em;
  height: 0.9em;
}

/* --- Listing container --- */
.skeleton-listing {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5em;
}

/* --- Item skeleton --- */
.skeleton-item {
  display: flex;
  align-items: center;
  padding: 0.6em 0.8em;
  border-radius: 8px;
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--divider, rgba(0, 0, 0, 0.06));
}

.dark-mode .skeleton-item,
[data-theme="dark"] .skeleton-item {
  background: var(--surfacePrimary, #1e1e1e);
  border-color: var(--divider, rgba(255, 255, 255, 0.06));
}

/* --- Mosaic mode --- */
.skeleton-container.mosaic .skeleton-item {
  width: calc(25% - 0.6em);
  min-width: 180px;
  flex-direction: column;
  align-items: flex-start;
  padding: 1em;
}

.skeleton-container.mosaic .skeleton-icon {
  width: 100%;
  height: 3.5em;
  margin-bottom: 0.8em;
  border-radius: 6px;
}

.skeleton-container.mosaic .skeleton-info {
  width: 100%;
}

.skeleton-container.mosaic .skeleton-name {
  height: 1em;
  margin-bottom: 0.5em;
}

.skeleton-container.mosaic .skeleton-meta {
  display: flex;
  gap: 0.8em;
}

.skeleton-container.mosaic .skeleton-size {
  width: 3em;
  height: 0.75em;
}

.skeleton-container.mosaic .skeleton-modified {
  width: 5em;
  height: 0.75em;
}

/* --- List mode --- */
.skeleton-container.list .skeleton-item {
  width: 100%;
  margin-bottom: 2px;
}

.skeleton-container.list .skeleton-icon {
  width: 2.5em;
  height: 2.5em;
  margin-right: 1em;
  flex-shrink: 0;
  border-radius: 4px;
}

.skeleton-container.list .skeleton-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 1em;
}

.skeleton-container.list .skeleton-name {
  width: 40%;
  height: 0.9em;
  flex-shrink: 0;
}

.skeleton-container.list .skeleton-meta {
  display: flex;
  gap: 1.5em;
  margin-left: auto;
}

.skeleton-container.list .skeleton-size {
  width: 4em;
  height: 0.75em;
}

.skeleton-container.list .skeleton-modified {
  width: 6em;
  height: 0.75em;
}

/* --- Gallery (detail) mode --- */
.skeleton-container.gallery .skeleton-item {
  width: 100%;
  margin-bottom: 2px;
}

.skeleton-container.gallery .skeleton-icon {
  width: 3em;
  height: 3em;
  margin-right: 1em;
  flex-shrink: 0;
  border-radius: 4px;
}

.skeleton-container.gallery .skeleton-info {
  flex: 1;
}

.skeleton-container.gallery .skeleton-name {
  width: 50%;
  height: 0.9em;
  margin-bottom: 0.4em;
}

.skeleton-container.gallery .skeleton-meta {
  display: flex;
  gap: 1.5em;
}

.skeleton-container.gallery .skeleton-size {
  width: 4em;
  height: 0.7em;
}

.skeleton-container.gallery .skeleton-modified {
  width: 7em;
  height: 0.7em;
}

/* --- Compact mode --- */
.skeleton-container.compact .skeleton-item {
  width: 100%;
  padding: 0.35em 0.8em;
  margin-bottom: 1px;
}

.skeleton-container.compact .skeleton-icon {
  width: 1.8em;
  height: 1.8em;
  margin-right: 0.8em;
  flex-shrink: 0;
  border-radius: 3px;
}

.skeleton-container.compact .skeleton-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 1em;
}

.skeleton-container.compact .skeleton-name {
  width: 35%;
  height: 0.8em;
}

.skeleton-container.compact .skeleton-meta {
  display: flex;
  gap: 1em;
  margin-left: auto;
}

.skeleton-container.compact .skeleton-size {
  width: 3em;
  height: 0.65em;
}

.skeleton-container.compact .skeleton-modified {
  width: 5em;
  height: 0.65em;
}

/* --- Responsive adjustments --- */
@media (max-width: 736px) {
  .skeleton-container.mosaic .skeleton-item {
    width: calc(50% - 0.4em);
    min-width: 0;
  }
}

@media (max-width: 450px) {
  .skeleton-container.mosaic .skeleton-item {
    width: 100%;
  }
}
</style>
