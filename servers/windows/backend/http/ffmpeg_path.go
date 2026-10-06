package fbhttp

import (
	"os"
	"os/exec"
	"path/filepath"
)

// LookupFFmpegTool finds ffmpeg/ffprobe for previews/HLS.
// Order: <TOOL>_PATH → <exeDir>/bin/<tool> → PATH.
func LookupFFmpegTool(tool string) (string, error) {
	if tool == "" {
		tool = "ffmpeg"
	}
	if env := os.Getenv(tool + "_PATH"); env != "" {
		if info, err := os.Stat(env); err == nil && !info.IsDir() {
			return env, nil
		}
	}
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		for _, name := range []string{
			filepath.Join(dir, "bin", tool+".exe"),
			filepath.Join(dir, "bin", tool),
			filepath.Join(dir, tool+".exe"),
			filepath.Join(dir, tool),
		} {
			if info, err := os.Stat(name); err == nil && !info.IsDir() {
				return name, nil
			}
		}
	}
	return exec.LookPath(tool)
}

// LookupFFmpeg locates ffmpeg.
func LookupFFmpeg() (string, error) { return LookupFFmpegTool("ffmpeg") }

// LookupFFprobe locates ffprobe.
func LookupFFprobe() (string, error) { return LookupFFmpegTool("ffprobe") }

func lookupFFmpeg() (string, error) { return LookupFFmpeg() }