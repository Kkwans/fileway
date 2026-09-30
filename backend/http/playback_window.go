package fbhttp

import (
	"fmt"
	"github.com/Kkwans/nas-file-browser/backend/hls"
	"math"
)

func applyPlaybackWindow(input *hls.Input, start, length float64, session string) error {
	if math.IsNaN(start) || math.IsInf(start, 0) || math.IsNaN(length) || math.IsInf(length, 0) || start < 0 || length < 0 {
		return fmt.Errorf("播放位置或片段长度无效")
	}
	if length == 0 {
		if start != 0 {
			return fmt.Errorf("跳转播放需要指定片段长度")
		}
		return nil
	}
	if input.DurationSeconds <= 0 || start >= input.DurationSeconds || length < 8 || length > 120 || len(session) > 128 {
		return fmt.Errorf("播放片段超出视频范围或无法确认视频总时长")
	}
	input.StartSeconds, input.WindowSeconds, input.SessionID = start, length, session
	return nil
}
