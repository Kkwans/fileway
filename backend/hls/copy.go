package hls

// Preserve video packets and their quality; convert only unsupported audio.
func copyTrackArgs(args []string, job Job) []string {
	for i := 0; i+1 < len(args); i++ {
		if args[i] == "-map" && args[i+1] == "0:a:0?" {
			args[i+1] = selectedAudioMap(job.AudioStream)
		}
	}
	if job.Profile == DefaultAudioHLSProfile || job.Profile == DefaultMP4AudioProfile {
		output := args[len(args)-1]
		args = args[:len(args)-1]
		args = append(args, "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-ac", "2", output)
	}
	return args
}
