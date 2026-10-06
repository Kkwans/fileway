# Windows deployment

Build the same `server/backend` with GOOS=windows and the same embedded Web
artifact. The Windows filesystem adapter maps virtual `/C/...` API paths to
native drive paths and keeps configured user scope boundaries.

Existing deployments can keep their database, CLI arguments and service runner.
Stage and hash the new executable, preserve the previous executable/database,
stop the identified runner, switch the executable and restore the original
runner. Reuse FFmpeg/ffprobe from an explicit override, the executable's bin
directory or PATH. Verify actual native-host behavior before retiring old source.

A Windows server is independent of the later Windows desktop client. They share
the product's protocol and reusable core; there is one Web UI for every server.
