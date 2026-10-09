package fbhttp

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/events"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/transfers"
)

const transferProgressFlushBytes int64 = 8 << 20
const transferProgressFlushInterval = time.Second

// transferTracker keeps native downloads streamable while recording server
// side progress. It intentionally does not buffer response bytes.
type transferTracker struct {
	http.ResponseWriter
	request    *http.Request
	data       *data
	item       *transfers.Item
	bytes      int64
	flushed    int64
	lastFlush  time.Time
	statusCode int
	finished   bool
	writeErr   error
}

func newTransferTracker(w http.ResponseWriter, r *http.Request, d *data, file *files.FileInfo) *transferTracker {
	id := strings.TrimSpace(r.URL.Query().Get("transfer"))
	if id == "" || d.store == nil || d.store.Transfers == nil {
		return nil
	}
	total := int64(0)
	if file != nil && !file.IsDir && file.Size > 0 {
		total = file.Size
	}
	item, err := d.store.Transfers.Ensure(d.user.ID, id, transfers.KindDownload, file.Name, r.URL.Path, total)
	if err != nil {
		// A malformed/foreign tracking id must never turn a valid download into
		// an error. The raw route remains the source of truth for authorization.
		return nil
	}
	if item.Target != r.URL.Path || item.BytesTotal != total {
		return nil
	}
	return &transferTracker{ResponseWriter: w, request: r, data: d, item: item}
}

func (tracker *transferTracker) WriteHeader(code int) {
	if tracker.statusCode == 0 {
		tracker.statusCode = code
	}
	tracker.ResponseWriter.WriteHeader(code)
}

func (tracker *transferTracker) Write(payload []byte) (int, error) {
	if err := tracker.request.Context().Err(); err != nil {
		tracker.writeErr = err
		return 0, err
	}
	if tracker.statusCode == 0 {
		tracker.WriteHeader(http.StatusOK)
	}
	if tracker.bytes == 0 && (tracker.statusCode == http.StatusOK || tracker.statusCode == http.StatusPartialContent) {
		if item, err := tracker.data.store.Transfers.Get(tracker.data.user.ID, tracker.item.ID, false); err == nil && item.Status != transfers.StatusCompleted {
			_, _ = tracker.data.store.Transfers.SetStatus(item.ID, item.UserID, transfers.StatusRunning, "")
		}
	}
	count, err := tracker.ResponseWriter.Write(payload)
	if err != nil {
		tracker.writeErr = err
	}
	if count > 0 {
		tracker.bytes += int64(count)
		if tracker.bytes-tracker.flushed >= transferProgressFlushBytes || time.Since(tracker.lastFlush) >= transferProgressFlushInterval {
			tracker.flushProgress(false)
		}
	}
	return count, err
}

func (tracker *transferTracker) Flush() {
	tracker.flushProgress(true)
	if flusher, ok := tracker.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}

func (tracker *transferTracker) flushProgress(force bool) {
	if tracker.item == nil || tracker.bytes <= tracker.flushed {
		return
	}
	if !force && tracker.bytes-tracker.flushed < transferProgressFlushBytes && time.Since(tracker.lastFlush) < transferProgressFlushInterval {
		return
	}
	start, _, valid := tracker.responseRange()
	if !valid {
		return
	}
	if latest, err := tracker.data.store.Transfers.DownloadRange(tracker.item.ID, tracker.data.user.ID, start, tracker.bytes); err == nil {
		tracker.flushed = tracker.bytes
		tracker.lastFlush = time.Now()
		publishTransfer(latest)
	}
}

func (tracker *transferTracker) responseRange() (start, length int64, valid bool) {
	if tracker.request.Method != http.MethodGet {
		return 0, 0, false
	}
	if tracker.statusCode == http.StatusPartialContent {
		var end, total int64
		if count, err := fmt.Sscanf(tracker.Header().Get("Content-Range"), "bytes %d-%d/%d", &start, &end, &total); err != nil || count != 3 ||
			start < 0 || end < start || total != tracker.item.BytesTotal || end >= total {
			return 0, 0, false
		}
		return start, end - start + 1, true
	}
	if tracker.statusCode == 0 || tracker.statusCode == http.StatusOK {
		return 0, tracker.item.BytesTotal, true
	}
	return 0, 0, false
}

func (tracker *transferTracker) finish(statusCode int, responseErr error) {
	if tracker.finished {
		return
	}
	tracker.finished = true
	tracker.flushProgress(true)
	if tracker.item == nil {
		return
	}
	effectiveStatus := statusCode
	if effectiveStatus == 0 {
		effectiveStatus = tracker.statusCode
	}
	if effectiveStatus == 0 {
		effectiveStatus = http.StatusOK
	}
	status := transfers.StatusCompleted
	message := ""
	if errors.Is(context.Cause(tracker.request.Context()), errTransferCanceled) {
		// The owning wrapper releases the active slot before publishing the
		// canceled state, so an immediate explicit resume cannot hit a stale slot.
		return
	} else if tracker.request.Context().Err() != nil {
		status = transfers.StatusInterrupted
		message = tracker.request.Context().Err().Error()
	} else if effectiveStatus >= http.StatusBadRequest || responseErr != nil || tracker.writeErr != nil {
		status = transfers.StatusFailed
		if responseErr != nil {
			message = responseErr.Error()
		} else if tracker.writeErr != nil {
			message = tracker.writeErr.Error()
		} else {
			message = http.StatusText(effectiveStatus)
		}
	} else {
		start, length, valid := tracker.responseRange()
		latest, lookupErr := tracker.data.store.Transfers.Get(tracker.data.user.ID, tracker.item.ID, false)
		if !valid || lookupErr != nil || tracker.item.BytesTotal > 0 && (tracker.bytes != length || start+length != tracker.item.BytesTotal || latest.BytesTransferred < tracker.item.BytesTotal) {
			status = transfers.StatusInterrupted
			message = "本次响应未确认整文件传输完成"
		}
	}
	updated, err := tracker.data.store.Transfers.Finish(tracker.item.ID, tracker.data.user.ID, status, message)
	if err != nil {
		// The response has already been sent; persistence is best effort.
		return
	}
	events.Default.PublishForUser(updated.UserID, "transfer.changed", updated)
}

// Ensure the tracker remains compatible with wrappers that inspect optional
// interfaces. ServeContent only needs Header/Write/WriteHeader, but exposing
// Unwrap helps middleware and tests recover the original writer.
func (tracker *transferTracker) Unwrap() http.ResponseWriter {
	return tracker.ResponseWriter
}
