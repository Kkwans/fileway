package hls

import (
	"fmt"
	"math"
	"strings"
)

// Software remains the portable default. Only the explicit RK3588 deployment
// uses MPP decode, RGA scale/overlay and OpenCL HDR tone mapping. Copy paths
// never decode or encode, regardless of the configured accelerator.
func acceleratorArgs(args []string, job Job, accelerator string) ([]string, string) {
	codec := ""
	for i := 1; i+1 < len(args); i++ {
		if args[i] == "-c:v" {
			codec = args[i+1]
		}
	}
	if codec == "copy" || IsWebMCopyProfile(job.Profile) || IsMP4CopyProfile(job.Profile) || IsCopyProfile(job.Profile) {
		method := "remux"
		if job.Profile == DefaultAudioHLSProfile || job.Profile == DefaultMP4AudioProfile {
			method = "audio"
		}
		return args, method
	}
	if accelerator != "rkmpp" || job.VideoWidth < 2 || job.VideoHeight < 2 || job.VideoWidth > 4096 || job.VideoHeight > 4096 ||
		!strings.Contains("|h264|hevc|vp8|vp9|av1|", "|"+job.VideoCodec+"|") || (codec != "libx264" && codec != "libvpx-vp9") {
		return args, "software"
	}
	w, h := scaledDimensions(job.VideoWidth, job.VideoHeight, profileMaxWidth(job.Profile), profileMaxHeight(job.Profile))
	format := "nv12"
	if job.HDR {
		format = "p010le"
	}
	filter := fmt.Sprintf("scale_rkrga=w=%d:h=%d:format=%s", w, h, format)
	device := []string{"-init_hw_device", "rkmpp=rk"}
	if job.HDR {
		device = append(device, "-init_hw_device", "opencl=ocl@rk", "-filter_hw_device", "ocl")
		filter += ",hwmap=derive_device=opencl,tonemap_opencl=tonemap=hable:format=nv12"
		// Software VP9 can download the OpenCL result directly. Mapping it
		// back to MPP requires a downstream DRM consumer (encoder/overlay);
		// hwdownload alone cannot negotiate that reverse mapping.
		if codec != "libvpx-vp9" || job.SubtitleStream != nil {
			filter += ",hwmap=derive_device=rkmpp:reverse=1"
		}
	} else {
		device = append(device, "-filter_hw_device", "rk")
	}
	var video []string
	if job.SubtitleStream == nil {
		if codec == "libvpx-vp9" {
			filter += ",hwdownload,format=nv12,format=yuv420p"
		}
		video = []string{"-map", "0:v:0", "-vf", filter}
	} else {
		// Scale the complete subtitle canvas with the same factor as the video,
		// retaining its original coordinates rather than stretching captions.
		graph := fmt.Sprintf("[0:v:0]%s[main];[0:%d]scale=w='trunc(iw*%d/%d/2)*2':h='trunc(ih*%d/%d/2)*2',format=bgra,hwupload=derive_device=rkmpp[sub];[main][sub]overlay_rkrga=shortest=1:format=nv12", filter, *job.SubtitleStream, w, job.VideoWidth, h, job.VideoHeight)
		if codec == "libvpx-vp9" {
			graph += ",hwdownload,format=nv12,format=yuv420p"
		}
		video = []string{"-filter_complex", graph + "[v]", "-map", "[v]"}
	}
	result := make([]string, 0, len(args)+len(device)+len(video)+20)
	for i := 0; i < len(args); i++ {
		key := args[i]
		if key == "-i" {
			result = append(result, device...)
			result = append(result, "-hwaccel", "rkmpp", "-hwaccel_output_format", "drm_prime", "-afbc", "rga", key, args[i+1])
			result = append(result, video...)
			i++
			continue
		}
		if key == "-vf" || key == "-filter_complex" || (key == "-map" && (args[i+1] == "0:v:0" || args[i+1] == "[v]")) {
			i++
			continue
		}
		if codec == "libx264" && (key == "-preset" || key == "-crf" || key == "-pix_fmt") {
			i++
			continue
		}
		if key == "-c:v" && codec == "libx264" {
			result = append(result, key, "h264_rkmpp", "-rc_mode", "CQP", "-qp_init", "18", "-qp_min", "18", "-qp_max", "18", "-qp_min_i", "18", "-qp_max_i", "18")
			i++
			continue
		}
		result = append(result, key)
	}
	method := "hardware"
	if codec == "libvpx-vp9" {
		method = "hybrid"
	}
	return result, method
}

func scaledDimensions(width, height, maxWidth, maxHeight int) (int, int) {
	ratio := math.Min(1, math.Min(float64(maxWidth)/float64(width), float64(maxHeight)/float64(height)))
	return max(2, int(float64(width)*ratio)/2*2), max(2, int(float64(height)*ratio)/2*2)
}
