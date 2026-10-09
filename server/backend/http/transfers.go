package fbhttp

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/mux"

	"github.com/Kkwans/nas-file-browser/backend/events"
	"github.com/Kkwans/nas-file-browser/backend/storage"
	"github.com/Kkwans/nas-file-browser/backend/transfers"
)

var errTransferCanceled = errors.New("传输已停止，未完成的上传片段保留")

type transferStreamKey struct {
	store *storage.Storage
	owner uint
	id    string
}
type transferStreamGroup struct {
	canceled bool
	streams  map[*http.Request]func()
}

var transferStreams = struct {
	sync.Mutex
	groups map[transferStreamKey]*transferStreamGroup
}{groups: make(map[transferStreamKey]*transferStreamGroup)}

// Only live, authenticated requests are cancellable here. TUS session deletion
// remains the separate DELETE protocol; stopping a PATCH retains its part.
func withTransferCancellation(fn handleFunc, kind transfers.Kind) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		id := strings.TrimSpace(r.URL.Query().Get("transfer"))
		if kind == transfers.KindUpload {
			header := strings.TrimSpace(r.Header.Get("X-Transfer-ID"))
			if id != "" && header != "" && id != header {
				return http.StatusConflict, fmt.Errorf("传输标识不一致")
			}
			if header != "" {
				id = header
			}
		}
		if id == "" || d.store.Transfers == nil {
			return fn(w, r, d)
		}
		if item, lookupErr := d.store.Transfers.Get(d.user.ID, id, false); lookupErr == nil && (item.Kind != kind || item.Target != r.URL.Path) {
			return fn(w, r, d) // A reused tracking ID grants no control over another resource.
		}
		ctx, cancel := context.WithCancelCause(r.Context())
		defer cancel(nil)
		r = r.WithContext(ctx)
		if kind == transfers.KindUpload {
			r.Body = &transferRequestBody{ReadCloser: r.Body, ctx: ctx, start: func() {
				if item, err := d.store.Transfers.Get(d.user.ID, id, false); err == nil && item.Kind == kind && item.Status != transfers.StatusCompleted {
					_, _ = d.store.Transfers.SetStatus(id, d.user.ID, transfers.StatusRunning, "")
				}
			}}
		}
		key := transferStreamKey{d.store, d.user.ID, id}
		transferStreams.Lock()
		group := transferStreams.groups[key]
		if group == nil {
			group = &transferStreamGroup{streams: make(map[*http.Request]func())}
			transferStreams.groups[key] = group
		}
		if group.canceled {
			transferStreams.Unlock()
			return http.StatusConflict, errTransferCanceled
		}
		group.streams[r] = func() {
			cancel(errTransferCanceled)
			controller := http.NewResponseController(w)
			if kind == transfers.KindUpload {
				_ = controller.SetReadDeadline(time.Now())
				_ = r.Body.Close()
			} else {
				_ = controller.SetWriteDeadline(time.Now())
			}
		}
		transferStreams.Unlock()
		released := false
		release := func() {
			if released {
				return
			}
			released = true
			transferStreams.Lock()
			delete(group.streams, r)
			if len(group.streams) == 0 {
				if group.canceled {
					if item, finishErr := d.store.Transfers.Finish(id, d.user.ID, transfers.StatusCanceled, errTransferCanceled.Error()); finishErr == nil {
						publishTransfer(item)
					}
				}
				delete(transferStreams.groups, key)
			}
			transferStreams.Unlock()
		}
		defer release()
		status, err := fn(w, r, d)
		release()
		return status, err
	})
}

type transferRequestBody struct {
	io.ReadCloser
	ctx   context.Context
	start func()
	once  sync.Once
}

func (body *transferRequestBody) Read(data []byte) (int, error) {
	if err := body.ctx.Err(); err != nil {
		return 0, err
	}
	body.once.Do(body.start)
	count, err := body.ReadCloser.Read(data)
	if body.ctx.Err() != nil {
		return count, body.ctx.Err()
	}
	return count, err
}

const (
	defaultTransferPageSize = 10
	maxTransferPageSize     = 100
)

type transferListResponse struct {
	Items      []*transfers.Item `json:"items"`
	NextCursor string            `json:"nextCursor,omitempty"`
	Total      int               `json:"total"`
}

type transferCursor struct {
	CreatedAt int64  `json:"createdAt"`
	ID        string `json:"id"`
}

type transferDeleteAllResponse struct {
	Deleted int `json:"deleted"`
}

type downloadTransferRequest struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Target     string `json:"target"`
	Path       string `json:"path"`
	URL        string `json:"url"`
	BytesTotal int64  `json:"bytesTotal"`
}

type downloadTransferResponse struct {
	Item *transfers.Item `json:"item"`
	URL  string          `json:"url,omitempty"`
}

var transferListHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.store.Transfers == nil {
		return http.StatusServiceUnavailable, fmt.Errorf("传输记录服务不可用")
	}
	kind, err := parseTransferKind(r.URL.Query().Get("kind"))
	if err != nil {
		return http.StatusBadRequest, err
	}
	limit := defaultTransferPageSize
	if raw := strings.TrimSpace(r.URL.Query().Get("limit")); raw != "" {
		parsed, scanErr := strconv.Atoi(raw)
		if scanErr != nil || parsed < 1 || parsed > maxTransferPageSize {
			return http.StatusBadRequest, fmt.Errorf("limit 必须在 1 到 %d 之间", maxTransferPageSize)
		}
		limit = parsed
	}
	statuses, err := parseTransferStatuses(r.URL.Query().Get("status"))
	if err != nil {
		return http.StatusBadRequest, err
	}
	var cursor *transfers.RecentCursor
	if raw := strings.TrimSpace(r.URL.Query().Get("cursor")); raw != "" {
		cursor, err = decodeTransferCursor(raw)
		if err != nil {
			return http.StatusBadRequest, fmt.Errorf("cursor 无效")
		}
	}
	items, err := d.store.Transfers.ListPageFiltered(d.user.ID, kind, statuses, limit+1, cursor)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	total, err := d.store.Transfers.CountFiltered(d.user.ID, kind, statuses)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	response := transferListResponse{Items: items, Total: total}
	if len(items) > limit {
		response.Items = items[:limit]
		response.NextCursor = encodeTransferCursor(response.Items[len(response.Items)-1])
	}
	if response.Items == nil {
		response.Items = []*transfers.Item{}
	}
	return renderJSON(w, r, response)
})

var transferGetHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.store.Transfers == nil {
		return http.StatusServiceUnavailable, fmt.Errorf("传输记录服务不可用")
	}
	item, err := d.store.Transfers.Get(d.user.ID, mux.Vars(r)["id"], d.user.Perm.Admin)
	if err != nil {
		return transferErrorStatus(err), err
	}
	return renderJSON(w, r, item)
})

var transferDownloadCreateHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.store.Transfers == nil {
		return http.StatusServiceUnavailable, fmt.Errorf("传输记录服务不可用")
	}
	if !d.user.Perm.Download {
		return http.StatusForbidden, fmt.Errorf("没有下载权限")
	}
	var request downloadTransferRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		return http.StatusBadRequest, fmt.Errorf("下载任务参数无效: %w", err)
	}
	if request.Target == "" {
		request.Target = request.Path
	}
	if request.Name == "" {
		request.Name = request.Target
	}
	if request.Target == "" {
		return http.StatusBadRequest, fmt.Errorf("下载目标不能为空")
	}
	item, err := d.store.Transfers.Ensure(d.user.ID, strings.TrimSpace(request.ID), transfers.KindDownload, request.Name, request.Target, request.BytesTotal)
	if err != nil {
		return transferErrorStatus(err), err
	}
	publishTransfer(item)
	return renderJSONStatus(w, downloadTransferResponse{Item: item, URL: request.URL}, http.StatusCreated)
})

var transferCancelHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.store.Transfers == nil {
		return http.StatusServiceUnavailable, fmt.Errorf("传输记录服务不可用")
	}
	item, err := d.store.Transfers.Get(d.user.ID, mux.Vars(r)["id"], d.user.Perm.Admin)
	if err != nil {
		return transferErrorStatus(err), err
	}
	if item.Status != transfers.StatusQueued && item.Status != transfers.StatusRunning {
		return http.StatusConflict, fmt.Errorf("传输已结束，无法取消")
	}
	key := transferStreamKey{d.store, item.UserID, item.ID}
	transferStreams.Lock()
	group := transferStreams.groups[key]
	if group == nil || len(group.streams) == 0 {
		transferStreams.Unlock()
		return http.StatusConflict, fmt.Errorf("当前没有可停止的运行流；未改变记录或删除上传片段，请使用原上传的取消清理操作")
	}
	group.canceled = true
	stops := make([]func(), 0, len(group.streams))
	for _, stop := range group.streams {
		stops = append(stops, stop)
	}
	transferStreams.Unlock()
	for _, stop := range stops {
		stop()
	}
	return renderJSONStatus(w, item, http.StatusAccepted)
})

var transferDeleteHandler = withUser(func(_ http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.store.Transfers == nil {
		return http.StatusServiceUnavailable, fmt.Errorf("传输记录服务不可用")
	}
	if err := d.store.Transfers.Delete(d.user.ID, mux.Vars(r)["id"], d.user.Perm.Admin); err != nil {
		return transferErrorStatus(err), err
	}
	events.Default.PublishForUser(d.user.ID, "transfer.changed", map[string]string{"id": mux.Vars(r)["id"], "status": "deleted"})
	return http.StatusNoContent, nil
})

var transferDeleteAllHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.store.Transfers == nil {
		return http.StatusServiceUnavailable, fmt.Errorf("传输记录服务不可用")
	}
	kind, err := parseTransferKind(r.URL.Query().Get("kind"))
	if err != nil {
		return http.StatusBadRequest, err
	}
	if kind == "" {
		return http.StatusBadRequest, fmt.Errorf("清空传输记录必须指定 kind")
	}
	deleted, err := d.store.Transfers.DeleteAll(d.user.ID, kind, d.user.Perm.Admin)
	if err != nil {
		return transferErrorStatus(err), err
	}
	return renderJSON(w, r, transferDeleteAllResponse{Deleted: deleted})
})

func publishTransfer(item *transfers.Item) {
	if item != nil {
		events.Default.PublishForUser(item.UserID, "transfer.changed", item)
	}
}

func parseTransferKind(raw string) (transfers.Kind, error) {
	switch transfers.Kind(strings.TrimSpace(raw)) {
	case "":
		return "", nil
	case transfers.KindUpload:
		return transfers.KindUpload, nil
	case transfers.KindDownload:
		return transfers.KindDownload, nil
	default:
		return "", fmt.Errorf("未知传输类型 %q", raw)
	}
}

func parseTransferStatuses(raw string) ([]transfers.Status, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, nil
	}
	valid := map[transfers.Status]struct{}{
		transfers.StatusQueued: {}, transfers.StatusRunning: {},
		transfers.StatusCompleted: {}, transfers.StatusFailed: {},
		transfers.StatusCanceled: {}, transfers.StatusInterrupted: {},
	}
	seen := make(map[transfers.Status]struct{})
	statuses := make([]transfers.Status, 0)
	for _, value := range strings.Split(raw, ",") {
		status := transfers.Status(strings.TrimSpace(value))
		if status == "" {
			continue
		}
		if _, ok := valid[status]; !ok {
			return nil, fmt.Errorf("未知传输状态 %q", status)
		}
		if _, ok := seen[status]; ok {
			continue
		}
		seen[status] = struct{}{}
		statuses = append(statuses, status)
	}
	return statuses, nil
}

func encodeTransferCursor(item *transfers.Item) string {
	encoded, _ := json.Marshal(transferCursor{CreatedAt: item.CreatedAt, ID: item.ID})
	return base64.RawURLEncoding.EncodeToString(encoded)
}

func decodeTransferCursor(raw string) (*transfers.RecentCursor, error) {
	decoded, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		return nil, err
	}
	var cursor transferCursor
	if err := json.Unmarshal(decoded, &cursor); err != nil || cursor.CreatedAt <= 0 || cursor.ID == "" {
		return nil, fmt.Errorf("invalid cursor")
	}
	return &transfers.RecentCursor{CreatedAt: cursor.CreatedAt, ID: cursor.ID}, nil
}

func transferErrorStatus(err error) int {
	if err == transfers.ErrNotExist {
		return http.StatusNotFound
	}
	if err == transfers.ErrInvalid {
		return http.StatusBadRequest
	}
	return http.StatusInternalServerError
}
