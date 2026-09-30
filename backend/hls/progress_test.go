package hls

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"
)

func TestReadProgressHandlesFFmpegBlocksAndInvalidValues(t *testing.T) {
	var reports []Progress
	input := "out_time_us=2500000\nout_time_ms=2500000\nfps=24.3\nspeed=1.25x\nprogress=continue\nout_time_us=-1\nspeed=N/A\nprogress=continue\nout_time_us=15000000\nspeed=NaN\nfps=Inf\nprogress=end\n"
	err := readProgress(strings.NewReader(input), 10, func(p Progress) error { reports = append(reports, p); return nil })
	if err != nil || len(reports) != 3 {
		t.Fatalf("reports=%+v err=%v", reports, err)
	}
	if reports[0].ProcessedSeconds != 2.5 || reports[0].Speed != 1.25 || reports[0].FPS != 24.3 {
		t.Fatalf("first block=%+v", reports[0])
	}
	if reports[1].ProcessedSeconds != 2.5 || reports[1].Speed != 0 {
		t.Fatalf("invalid block=%+v", reports[1])
	}
	if reports[2].ProcessedSeconds != 10 || reports[2].Phase != "finalizing" || reports[2].Speed != 0 || reports[2].FPS != 0 {
		t.Fatalf("final block=%+v", reports[2])
	}
}

func TestReadProgressPropagatesReporterFailure(t *testing.T) {
	want := errors.New("storage unavailable")
	err := readProgress(strings.NewReader("progress=continue\n"), 0, func(Progress) error { return want })
	if !errors.Is(err, want) {
		t.Fatal(err)
	}
}

func TestRunReportsQueueAndPreparation(t *testing.T) {
	service := newFakeService(t, 1, DefaultMaxBytes, 0)
	job := reserveJobs(t, service, 1)[0]
	var phases []string
	if err := service.RunWithProgress(context.Background(), job, func(p Progress) error { phases = append(phases, p.Phase); return nil }); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(phases, []string{"queued", "preparing"}) {
		t.Fatal(phases)
	}
}
