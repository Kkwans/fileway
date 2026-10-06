# Coil

- Upstream: https://github.com/coil-kt/coil
- Version: `3.6.3`; `coil-compose` and `coil-network-okhttp` are pinned explicitly.
- License: Apache-2.0; upstream license at `3.6.3/LICENSE.txt` is preserved in `coil-APACHE-2.0.txt`.
- Usage: image fetching, decoding and Compose presentation. NAS File Browser generates the thumbnails through its existing `/api/preview/thumb/{path}` endpoint.
- Requests use revocable localhost capabilities backed by the authenticated Go session. Credentials are not passed to Coil URLs.
- Disk caching is disabled; decoded image memory cache is limited to 16 MiB and cleared on session closure. Capability URLs are unique per lease. This is not a persistent thumbnail cache.
