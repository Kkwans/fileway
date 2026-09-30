package fbhttp

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/hls"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

type mediaTranscodeRequest struct {
	Paths       []string `json:"paths"`
	Quality     string   `json:"quality"`
	Destination string   `json:"destination"`
}
type mediaTranscodeArgs struct {
	Path       string `json:"path"`
	Quality    string `json:"quality"`
	OutputPath string `json:"outputPath"`
}

func collectTranscodeVideos(ctx context.Context, d *data, paths []string) ([]string, error) {
	seen := make(map[string]bool)
	videos := make([]string, 0)
	visited := 0
	var visit func(string) error
	visit = func(value string) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		value, err := normalizeTransferPath(value)
		if err != nil {
			return err
		}
		if seen[value] {
			return nil
		}
		seen[value] = true
		visited++
		if visited > 50000 {
			return fmt.Errorf("所选目录超过 50000 项，请缩小选择范围")
		}
		file, err := files.NewFileInfo(&files.FileOptions{Fs: d.user.Fs, Path: value, Expand: true, SkipSubtitles: true, Modify: d.user.Perm.Modify, ReadHeader: d.server.TypeDetectionByHeader, Checker: d})
		if err != nil {
			return err
		}
		if file.IsDir {
			if file.IsSymlink {
				return nil
			}
			for _, item := range file.Items {
				if err := visit(item.Path); err != nil {
					return err
				}
			}
		} else if file.Type == "video" {
			videos = append(videos, value)
			if len(videos) > 1000 {
				return fmt.Errorf("一次最多转码 1000 个视频")
			}
		}
		return nil
	}
	for _, value := range paths {
		if err := visit(value); err != nil {
			return nil, err
		}
	}
	return videos, nil
}

func mediaTranscodesHandler(service *hls.Service, runtime *tasks.Runtime) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Download || !d.user.Perm.Create {
			return http.StatusForbidden, fmt.Errorf("转码需要读取视频和创建文件的权限")
		}
		var request mediaTranscodeRequest
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8*1024*1024))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&request); err != nil {
			return http.StatusBadRequest, err
		}
		if len(request.Paths) == 0 || len(request.Paths) > 1000 {
			return http.StatusBadRequest, fmt.Errorf("请选择 1–1000 个文件或目录")
		}
		if request.Quality == "" {
			request.Quality = "source"
		}
		if !mediaHLSQualityAllowed(request.Quality) || request.Quality == "native" {
			return http.StatusBadRequest, fmt.Errorf("转码画质无效")
		}
		destination, err := normalizeTransferPath(request.Destination)
		if err != nil {
			return http.StatusBadRequest, err
		}
		dir, err := files.NewFileInfo(&files.FileOptions{Fs: d.user.Fs, Path: destination, Expand: true, SkipSubtitles: true, Modify: d.user.Perm.Modify, Checker: d})
		if err != nil {
			return errToStatus(err), err
		}
		if !dir.IsDir {
			return http.StatusBadRequest, fmt.Errorf("输出目录无效")
		}
		videos, err := collectTranscodeVideos(r.Context(), d, request.Paths)
		if err != nil {
			return errToStatus(err), err
		}
		if len(videos) == 0 {
			return http.StatusBadRequest, fmt.Errorf("所选内容中没有可转码的视频")
		}
		items := make([]*tasks.Task, 0, len(videos))
		failures := make([]map[string]string, 0)
		reserved := make(map[string]bool)
		for _, source := range videos {
			name := path.Base(source)
			name = name[:len(name)-len(path.Ext(name))] + "." + request.Quality + ".mp4"
			output := path.Join(destination, name)
			for suffix := 2; ; suffix++ {
				_, statErr := d.user.Fs.Stat(output)
				if os.IsNotExist(statErr) && !reserved[output] {
					break
				}
				if statErr != nil && !os.IsNotExist(statErr) {
					return errToStatus(statErr), statErr
				}
				output = path.Join(destination, fmt.Sprintf("%s (%d).mp4", name[:len(name)-4], suffix))
			}
			reserved[output] = true
			if !d.Check(output) {
				return http.StatusForbidden, fmt.Errorf("无法在所选目录创建转码文件")
			}
			task, err := enqueueMediaTranscode(runtime, d, d.user, service, mediaTranscodeArgs{Path: source, Quality: request.Quality, OutputPath: output}, "")
			if err != nil {
				failures = append(failures, map[string]string{"path": source, "error": err.Error()})
			} else {
				items = append(items, task)
			}
		}
		return renderJSONStatus(w, struct {
			Items    []*tasks.Task       `json:"items"`
			Failures []map[string]string `json:"failures"`
		}{items, failures}, http.StatusAccepted)
	})
}

func enqueueMediaTranscode(runtime *tasks.Runtime, d *data, owner *users.User, service *hls.Service, args mediaTranscodeArgs, retryOf string) (*tasks.Task, error) {
	encoded, err := json.Marshal(args)
	if err != nil {
		return nil, err
	}
	task, err := d.store.Tasks.New(owner.ID, owner.Username, tasks.TypeMediaTranscode, path.Base(args.Path), encoded, retryOf)
	if err != nil {
		return nil, err
	}
	task.SourcePath = args.Path
	task.OutputPath = args.OutputPath
	if err := d.store.Tasks.Update(task); err != nil {
		return nil, err
	}
	runner := func(ctx context.Context, report tasks.Reporter) (json.RawMessage, error) {
		ownerData := *d
		ownerData.user = owner
		if !owner.Perm.Create || !owner.Perm.Download || !ownerData.Check(args.OutputPath) {
			return nil, fmt.Errorf("转码文件权限已不可用")
		}
		dir, err := files.NewFileInfo(&files.FileOptions{Fs: owner.Fs, Path: path.Dir(args.OutputPath), Expand: true, SkipSubtitles: true, Modify: owner.Perm.Modify, Checker: &ownerData})
		if err != nil {
			return nil, err
		}
		if !dir.IsDir {
			return nil, fmt.Errorf("输出目录已不可用")
		}
		output := filepath.Join(dir.RealPath(), path.Base(args.OutputPath))
		temporary := filepath.Join(dir.RealPath(), ".nfb-transcode-"+task.ID+".mp4")
		created := false
		defer func() {
			if created {
				_ = os.Remove(temporary)
			}
		}()
		err = service.Export(ctx, func() (hls.Job, error) {
			file, err := os.OpenFile(temporary, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
			if err != nil {
				return hls.Job{}, err
			}
			created = true
			if err := file.Close(); err != nil {
				return hls.Job{}, err
			}
			input, _, err := mediaHLSInputWithContext(ctx, &ownerData, owner, args.Path, true)
			if err != nil {
				return hls.Job{}, err
			}
			if input.DurationSeconds <= 0 || input.VideoWidth <= 0 || input.VideoHeight <= 0 {
				return hls.Job{}, fmt.Errorf("无法确认视频尺寸与时长，请检查文件后重试")
			}
			return hls.Job{UserID: owner.ID, Path: input.Path, SourcePath: input.SourcePath, Profile: hls.MP4ExportProfile(input, args.Quality), DurationSeconds: input.DurationSeconds, HDR: input.HDR}, nil
		}, temporary, task.ID, func(progress hls.Progress) error { return report(tasks.Progress{TotalItems: 1, Media: &progress}) })
		if err != nil {
			return nil, err
		}
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if err := os.Chmod(temporary, d.settings.FileMode); err != nil {
			return nil, err
		}
		// Atomic publication without replacing an existing user file. The
		// temporary file lives in the destination directory on the same FS.
		if err := os.Link(temporary, output); err != nil {
			return nil, fmt.Errorf("无法发布转码文件（目标已存在或文件系统不支持原子发布）: %w", err)
		}
		return json.Marshal(map[string]string{"outputPath": args.OutputPath})
	}
	if err := runtime.StartExclusive(task, runner, "media.output:"+fmt.Sprint(owner.ID)+":"+args.OutputPath); err != nil {
		task.Status = tasks.StatusFailed
		task.FinishedAt = time.Now().UnixMilli()
		task.Error = err.Error()
		_ = d.store.Tasks.Update(task)
		return nil, err
	}
	return task, nil
}
