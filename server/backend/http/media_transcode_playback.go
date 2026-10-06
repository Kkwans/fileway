package fbhttp

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/hls"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/gorilla/mux"
)

var transcodeAssetName = regexp.MustCompile(`^(index\.m3u8|segment-[0-9]{6}\.ts)$`)

func transcodePlaybackTask(d *data, id string) (*tasks.Task, int, error) {
	if !d.user.Perm.Download {
		return nil, http.StatusForbidden, fmt.Errorf("没有读取视频的权限")
	}
	task, err := d.store.Tasks.Get(d.user.ID, id, false)
	if err != nil {
		return nil, taskErrorStatus(err), err
	}
	if task.Type != tasks.TypeMediaTranscode || !d.Check(task.SourcePath) {
		return nil, http.StatusForbidden, fmt.Errorf("转码播放任务不可用")
	}
	return task, 0, nil
}

func mediaTranscodePlaybackHandler(service *hls.Service) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		task, status, err := transcodePlaybackTask(d, mux.Vars(r)["id"])
		if err != nil {
			return status, err
		}
		response := mediaHLSResponse{ID: task.ID, TaskID: task.ID, Path: task.SourcePath, State: hls.StateQueued, Format: "hls"}
		var args mediaTranscodeArgs
		if err := json.Unmarshal(task.Args, &args); err == nil {
			response.Quality = args.Quality
		}
		if task.Status == tasks.StatusRunning {
			response.State = hls.StatePreparing
		}
		if task.Media != nil {
			media := *task.Media
			media.PlayableSeconds = hls.PlayableSeconds(service.ExportDirectory(task.ID))
			response.Progress = &media
			response.DurationSeconds = task.Media.DurationSeconds
			response.SourceDurationSeconds = task.Media.DurationSeconds
			response.ProcessedSeconds = task.Media.ProcessedSeconds
		}
		if hls.PlayableSeconds(service.ExportDirectory(task.ID)) > 0 {
			response.State = hls.StateStreamable
			response.PlaylistURL = strings.TrimSuffix(d.server.BaseURL, "/") + "/api/media/transcodes/" + task.ID + "/assets/index.m3u8"
		}
		if task.Status == tasks.StatusCompleted {
			response.State = hls.StateCompleted
			response.SourceURL = (&url.URL{Path: strings.TrimSuffix(d.server.BaseURL, "/") + "/api/raw" + task.OutputPath, RawQuery: "inline=true"}).String()
		}
		if task.Status == tasks.StatusFailed || task.Status == tasks.StatusInterrupted {
			response.State = hls.StateFailed
			response.Error = task.Error
		}
		if task.Status == tasks.StatusCanceled {
			response.State = hls.StateCanceled
		}
		return renderJSON(w, r, response)
	})
}

func mediaTranscodeAssetHandler(service *hls.Service) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		task, status, err := transcodePlaybackTask(d, mux.Vars(r)["id"])
		if err != nil {
			return status, err
		}
		name := mux.Vars(r)["asset"]
		if !transcodeAssetName.MatchString(name) {
			return http.StatusNotFound, hls.ErrNotFound
		}
		file, err := os.Open(filepath.Join(service.ExportDirectory(task.ID), name))
		if err != nil {
			return errToStatus(err), err
		}
		defer func() { _ = file.Close() }()
		info, err := file.Stat()
		if err != nil {
			return errToStatus(err), err
		}
		if strings.HasSuffix(name, "m3u8") {
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			w.Header().Set("Cache-Control", "private, no-cache")
		} else {
			w.Header().Set("Content-Type", "video/mp2t")
			w.Header().Set("Cache-Control", "private, max-age=86400")
		}
		http.ServeContent(w, r, name, info.ModTime(), file)
		service.TouchExport(task.ID)
		return 0, nil
	})
}
