package io.github.kkwans.nasfilebrowser.download

import java.io.IOException

/** Missing source bytes are recoverable; they are not a corrupt local file. */
internal class DownloadPendingException(cause: Throwable? = null) : IOException("此片段尚未下载，正在等待数据", cause)
