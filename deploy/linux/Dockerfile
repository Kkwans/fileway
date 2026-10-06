# One Fileway source build and runtime entrypoint for every Linux profile.
ARG SERVER_SOURCE=source-artifact
ARG RUNTIME_BASE=alpine:3.23

FROM --platform=$BUILDPLATFORM node:24-alpine AS frontend-builder
ENV NODE_USE_ENV_PROXY=1
WORKDIR /src/frontend
COPY server/frontend/package.json server/frontend/pnpm-lock.yaml server/frontend/pnpm-workspace.yaml ./
COPY server/frontend/patches/ ./patches/
RUN corepack enable && corepack prepare pnpm@10.33.4 --activate && pnpm install --frozen-lockfile
COPY server/frontend/ ./
RUN pnpm build

FROM --platform=$BUILDPLATFORM golang:1.25-alpine AS source-artifact
ARG TARGETOS
ARG TARGETARCH
ARG IMAGE_VERSION
ARG IMAGE_REVISION
WORKDIR /src
COPY server/backend/go.mod server/backend/go.sum ./
RUN go mod download
COPY server/backend/ ./
COPY --from=frontend-builder /src/frontend/dist/ ./frontend/dist/
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath \
    -ldflags="-s -w -X github.com/Kkwans/nas-file-browser/backend/version.Version=${IMAGE_VERSION} -X github.com/Kkwans/nas-file-browser/backend/version.CommitSHA=${IMAGE_REVISION}" \
    -o /fileway-server .

# Optional named BuildKit context containing an already verified static binary.
# Unused in normal source builds; build.sh validates its source and platform.
FROM scratch AS prebuilt-artifact
COPY --from=verified-server /fileway-server /fileway-server

FROM ${SERVER_SOURCE} AS selected-artifact
FROM ${RUNTIME_BASE} AS runtime
ARG IMAGE_VERSION
ARG IMAGE_REVISION
ARG IMAGE_CREATED
ARG RUNTIME_BASE
LABEL org.opencontainers.image.title="Fileway" \
      org.opencontainers.image.source="https://github.com/Kkwans/fileway" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${IMAGE_REVISION}" \
      org.opencontainers.image.created="${IMAGE_CREATED}" \
      io.fileway.runtime.base="${RUNTIME_BASE}"
ENV PATH="/usr/lib/jellyfin-ffmpeg:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
RUN if command -v ffmpeg && command -v ffprobe && command -v tini && command -v wget && test -f /etc/mime.types; then \
      :; \
    elif command -v apk; then \
      apk add --no-cache ca-certificates mailcap tini ffmpeg; \
    elif command -v apt-get; then \
      apt-get update && apt-get install -y --no-install-recommends ca-certificates tini busybox-static media-types \
      && ln -sf /bin/busybox /usr/local/bin/wget \
      && rm -rf /var/lib/apt/lists/*; \
      command -v ffmpeg && command -v ffprobe; \
    else exit 1; fi
COPY --from=selected-artifact /fileway-server /usr/local/bin/filebrowser
COPY server/docker/common/ /
COPY server/docker/JSON.sh /JSON.sh
RUN chmod +x /init.sh /healthcheck.sh /JSON.sh && mkdir -p /config /database /srv
WORKDIR /srv
VOLUME /config /database
EXPOSE 80
HEALTHCHECK --start-period=2m --start-interval=5s --interval=30m --timeout=3s --retries=2 CMD /healthcheck.sh
ENTRYPOINT ["tini", "--", "/init.sh"]
CMD []
