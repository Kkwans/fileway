package fbhttp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/gorilla/mux"
)

type trashSizeArgs struct {
	ID string `json:"id"`
}

func trashSizeHandler(runtime *tasks.Runtime) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		item, owner, status, err := trashItemAndOwner(d, mux.Vars(r)["id"])
		if err != nil {
			return status, err
		}
		if !d.user.Perm.Admin && (!d.user.Perm.Delete || !d.Check(item.OriginalPath)) {
			return http.StatusForbidden, fmt.Errorf("没有统计此项目的权限")
		}
		ownerData := *d
		ownerData.user = owner
		task, err := enqueueTrashSizeTask(runtime, &ownerData, item.ID, "")
		if err != nil {
			return trashErrorStatus(err), err
		}
		return renderJSONStatus(w, task, http.StatusAccepted)
	})
}

func enqueueTrashSizeTask(runtime *tasks.Runtime, d *data, id, retryOf string) (*tasks.Task, error) {
	item, err := d.store.Trash.Get(d.user.ID, id, false)
	if err != nil {
		return nil, err
	}
	// Callers authorize the deletion or explicit size request before enqueueing.
	if !d.Check(item.OriginalPath) {
		return nil, fmt.Errorf("没有统计此项目的权限")
	}
	args, _ := json.Marshal(trashSizeArgs{ID: id})
	task, err := d.store.Tasks.New(d.user.ID, d.user.Username, tasks.TypeTrashSize, "统计回收站目录大小", args, retryOf)
	if err != nil {
		return nil, err
	}
	reserved, release, err := d.store.Trash.ReserveSize(d.user.ID, id, false, task.ID)
	if err == nil {
		service := newTrashService(d, d.user)
		err = runtime.Start(task, func(ctx context.Context, report tasks.Reporter) (json.RawMessage, error) {
			defer release()
			combined, cancel := context.WithCancel(reserved)
			defer cancel()
			stop := context.AfterFunc(ctx, cancel)
			defer stop()
			measured, err := service.MeasureSize(combined, d.user.ID, id, task.ID, func(count int, size int64) error {
				return report(tasks.Progress{ProcessedItems: count, ProcessedBytes: size})
			})
			if measured == nil {
				return nil, err
			}
			result, marshalErr := json.Marshal(measured.Public())
			if marshalErr != nil {
				return nil, marshalErr
			}
			if err != nil && !errors.Is(err, context.Canceled) {
				err = fmt.Errorf("部分目录无法读取，大小统计未完成")
			}
			return result, err
		})
		if err != nil {
			release()
			_ = d.store.Trash.FailSize(id, task.ID)
		}
	}
	if err != nil {
		task.Status, task.Error, task.FinishedAt = tasks.StatusFailed, err.Error(), time.Now().UnixMilli()
		_ = d.store.Tasks.Update(task)
		return nil, err
	}
	return task, nil
}
