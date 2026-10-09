package archivefs

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/mholt/archives"
	"github.com/shirou/gopsutil/v4/disk"
	"github.com/spf13/afero"
)

var (
	ErrEntryNotFound  = errors.New("archive entry preparation not found")
	ErrEntryForbidden = errors.New("archive entry preparation belongs to another scope")
	ErrEntryNotReady  = errors.New("archive entry is not ready")
	ErrEntryBudget    = errors.New("archive entry temporary cache budget exhausted")
)

type EntryInput struct {
	UserID         uint
	Namespace      string
	Filesystem     afero.Fs
	ArchivePath    string
	EntryPath      string
	SourceSize     int64
	SourceModified int64
}
type EntryProgress struct {
	Stage          string
	Size           int64
	ProcessedBytes int64
}
type EntryStatus struct {
	ID             string
	State          string
	Stage          string
	EntryPath      string
	Size           int64
	ProcessedBytes int64
	Error          string
}
type EntryCacheConfig struct {
	WorkDir        string
	MaxBytes       int64
	MaxItems       int
	Workers        int
	TTL            time.Duration
	PrepareTimeout time.Duration
	Limits         Limits
}
type entryJob struct {
	input     EntryInput
	status    EntryStatus
	key       string
	directory string
	reserved  int64
	refs      int
	touched   time.Time
	cancel    context.CancelFunc
}
type EntryCache struct {
	mu        sync.Mutex
	config    EntryCacheConfig
	directory string
	jobs      map[string]*entryJob
	keys      map[string]string
	used      int64
	ctx       context.Context
	cancel    context.CancelFunc
	slots     chan struct{}
	wg        sync.WaitGroup
}

// WorkDir is explicitly supplied by the service. This cache creates one private
// nonce child and never sweeps siblings, user filesystems or other instances.
func NewEntryCache(config EntryCacheConfig) (*EntryCache, error) {
	if !filepath.IsAbs(config.WorkDir) {
		return nil, fmt.Errorf("压缩包临时工作目录须为明确的绝对路径")
	}
	info, err := os.Lstat(config.WorkDir)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, fmt.Errorf("压缩包临时工作目录不可用")
	}
	if config.MaxBytes <= 0 {
		config.MaxBytes = 10 << 30
	}
	if config.MaxItems <= 0 {
		config.MaxItems = 64
	}
	if config.Workers <= 0 {
		config.Workers = 1
	}
	if config.TTL <= 0 {
		config.TTL = 30 * time.Minute
	}
	if config.PrepareTimeout <= 0 {
		config.PrepareTimeout = 10 * time.Minute
	}
	config.Limits = config.Limits.normalized()
	directory, err := os.MkdirTemp(config.WorkDir, ".fileway-archive-entry-")
	if err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join(directory, "owner"), []byte("fileway-archive-entry-v1\n"), 0o600); err != nil {
		_ = os.RemoveAll(directory)
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	cache := &EntryCache{config: config, directory: directory, jobs: make(map[string]*entryJob), keys: make(map[string]string), ctx: ctx, cancel: cancel, slots: make(chan struct{}, config.Workers)}
	cache.wg.Add(1)
	go func() {
		defer cache.wg.Done()
		interval := config.TTL / 2
		if interval > time.Minute {
			interval = time.Minute
		}
		if interval < time.Second {
			interval = time.Second
		}
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case now := <-ticker.C:
				cache.sweep(now)
			}
		}
	}()
	return cache, nil
}

func entryKey(input EntryInput) string {
	hash := sha256.New()
	_, _ = fmt.Fprintf(hash, "%d:%d:%s%d:%s%d:%s:%d:%d", input.UserID, len(input.Namespace), input.Namespace,
		len(input.ArchivePath), input.ArchivePath, len(input.EntryPath), input.EntryPath, input.SourceSize, input.SourceModified)
	return hex.EncodeToString(hash.Sum(nil))
}
func VerifyEntrySource(filesystem afero.Fs, input EntryInput) error {
	if filesystem == nil {
		return ErrUnsafeEntry
	}
	info, err := filesystem.Stat(input.ArchivePath)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return ErrUnsafeEntry
	}
	if info.Size() != input.SourceSize || info.ModTime().UnixMilli() != input.SourceModified {
		return ErrArchiveChanged
	}
	return nil
}

func (cache *EntryCache) Prepare(input EntryInput) (EntryStatus, error) {
	if input.Filesystem == nil || input.SourceSize < 0 {
		return EntryStatus{}, ErrUnsafeEntry
	}
	var err error
	input.ArchivePath, err = NormalizeFilesystemPath(input.ArchivePath)
	if err != nil {
		return EntryStatus{}, err
	}
	input.EntryPath, err = cleanEntryPath(input.EntryPath)
	if err != nil || input.EntryPath == "." {
		return EntryStatus{}, ErrUnsafeEntry
	}
	if err := VerifyEntrySource(input.Filesystem, input); err != nil {
		return EntryStatus{}, err
	}
	key := entryKey(input)
	cache.mu.Lock()
	defer cache.mu.Unlock()
	if cache.ctx.Err() != nil {
		return EntryStatus{}, cache.ctx.Err()
	}
	if id := cache.keys[key]; id != "" {
		job := cache.jobs[id]
		if job != nil && job.status.State != "failed" && job.status.State != "canceled" {
			job.touched = time.Now()
			return job.status, nil
		}
		if job != nil && job.refs == 0 {
			cache.removeLocked(job)
		}
	}
	cache.sweepLocked(time.Now())
	if len(cache.jobs) >= cache.config.MaxItems {
		return EntryStatus{}, ErrEntryBudget
	}
	token := make([]byte, 32)
	if _, err := rand.Read(token); err != nil {
		return EntryStatus{}, err
	}
	id := hex.EncodeToString(token)
	directory := filepath.Join(cache.directory, id)
	if err := os.Mkdir(directory, 0o700); err != nil {
		return EntryStatus{}, err
	}
	ctx, cancel := context.WithTimeout(cache.ctx, cache.config.PrepareTimeout)
	job := &entryJob{input: input, status: EntryStatus{ID: id, State: "queued", Stage: "等待准备所选条目", EntryPath: input.EntryPath}, key: key, directory: directory, touched: time.Now(), cancel: cancel}
	cache.jobs[id] = job
	cache.keys[key] = id
	cache.wg.Add(1)
	go cache.prepare(ctx, job)
	return job.status, nil
}

func (cache *EntryCache) prepare(ctx context.Context, job *entryJob) {
	defer cache.wg.Done()
	defer job.cancel()
	select {
	case cache.slots <- struct{}{}:
		defer func() { <-cache.slots }()
	case <-ctx.Done():
		cache.finish(job, ctx.Err())
		return
	}
	cache.mu.Lock()
	job.status.State = "checking"
	job.status.Stage = "正在检查压缩包及条目安全性"
	cache.mu.Unlock()
	file, err := os.OpenFile(filepath.Join(job.directory, "content"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		cache.finish(job, err)
		return
	}
	_, err = MaterializeEntry(ctx, job.input, file, cache.config.Limits, func(entry Entry) error {
		usage, err := disk.UsageWithContext(ctx, cache.directory)
		if err != nil {
			return fmt.Errorf("无法确认临时磁盘可用空间")
		}
		cache.mu.Lock()
		defer cache.mu.Unlock()
		if ctx.Err() != nil {
			return ctx.Err()
		}
		remaining := int64(0)
		for _, pending := range cache.jobs {
			if pending.status.State == "copying" {
				remaining += pending.reserved - pending.status.ProcessedBytes
			}
		}
		needed := uint64(entry.Size) + uint64(remaining)
		if entry.Size > cache.config.MaxBytes-cache.used || needed > usage.Free || usage.Free-needed < 64<<20 {
			return ErrEntryBudget
		}
		cache.used += entry.Size
		job.reserved = entry.Size
		job.status.Size = entry.Size
		job.status.State = "copying"
		job.status.Stage = "正在准备所选文件"
		return nil
	}, func(processed int64) error {
		cache.mu.Lock()
		defer cache.mu.Unlock()
		job.status.ProcessedBytes = processed
		if processed == job.status.Size {
			job.status.State = "checking"
			job.status.Stage = "文件已准备，正在核对归档完整性"
		}
		return ctx.Err()
	})
	closeErr := file.Close()
	if err == nil {
		err = closeErr
	}
	if ctx.Err() != nil {
		err = ctx.Err()
	}
	cache.finish(job, err)
}

func (cache *EntryCache) finish(job *entryJob, err error) {
	cache.mu.Lock()
	defer cache.mu.Unlock()
	job.touched = time.Now()
	if err == nil && job.status.State != "canceling" && cache.ctx.Err() == nil {
		job.status.State = "ready"
		job.status.Stage = "所选文件已就绪"
		job.status.ProcessedBytes = job.status.Size
		return
	}
	canceling := job.status.State == "canceling"
	job.status.State = "failed"
	job.status.Stage = "准备失败"
	if errors.Is(err, context.Canceled) || canceling || cache.ctx.Err() != nil {
		job.status.State = "canceled"
		job.status.Stage = "已取消准备"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		job.status.Error = "准备超过时间限制，请选择较小条目或稍后重试"
	} else if errors.Is(err, ErrEntryBudget) {
		job.status.Error = "临时空间或缓存预算不足，请稍后重试"
	} else if job.status.State == "failed" {
		job.status.Error = "条目无法安全准备，可能已变化、损坏或超过安全限制"
	}
	if os.RemoveAll(job.directory) == nil {
		cache.used -= job.reserved
		job.reserved = 0
	}
}

func (cache *EntryCache) Lookup(userID uint, namespace, id string) (EntryInput, EntryStatus, error) {
	cache.mu.Lock()
	defer cache.mu.Unlock()
	job := cache.jobs[id]
	if job == nil {
		return EntryInput{}, EntryStatus{}, ErrEntryNotFound
	}
	if job.input.UserID != userID || job.input.Namespace != namespace {
		return EntryInput{}, EntryStatus{}, ErrEntryForbidden
	}
	job.touched = time.Now()
	return job.input, job.status, nil
}
func (cache *EntryCache) Cancel(userID uint, namespace, id string) (EntryStatus, error) {
	cache.mu.Lock()
	defer cache.mu.Unlock()
	job := cache.jobs[id]
	if job == nil {
		return EntryStatus{}, ErrEntryNotFound
	}
	if job.input.UserID != userID || job.input.Namespace != namespace {
		return EntryStatus{}, ErrEntryForbidden
	}
	job.cancel()
	job.touched = time.Now()
	if job.status.State == "ready" || job.status.State == "failed" || job.status.State == "canceled" {
		job.status.State = "canceled"
		job.status.Stage = "已取消准备"
		if job.refs == 0 && os.RemoveAll(job.directory) == nil {
			cache.used -= job.reserved
			job.reserved = 0
		}
	} else {
		job.status.State = "canceling"
		job.status.Stage = "正在取消准备"
	}
	return job.status, nil
}

type EntryFile struct {
	*os.File
	once    sync.Once
	release func()
}

func (file *EntryFile) Close() error {
	err := file.File.Close()
	file.once.Do(file.release)
	return err
}
func (cache *EntryCache) Open(userID uint, namespace, id string) (*EntryFile, EntryStatus, error) {
	cache.mu.Lock()
	defer cache.mu.Unlock()
	job := cache.jobs[id]
	if job == nil {
		return nil, EntryStatus{}, ErrEntryNotFound
	}
	if job.input.UserID != userID || job.input.Namespace != namespace {
		return nil, EntryStatus{}, ErrEntryForbidden
	}
	if job.status.State != "ready" {
		return nil, job.status, ErrEntryNotReady
	}
	file, err := os.Open(filepath.Join(job.directory, "content"))
	if err != nil {
		return nil, job.status, err
	}
	job.refs++
	job.touched = time.Now()
	return &EntryFile{File: file, release: func() {
		cache.mu.Lock()
		defer cache.mu.Unlock()
		job.refs--
		job.touched = time.Now()
		if job.status.State == "canceled" && job.refs == 0 && os.RemoveAll(job.directory) == nil {
			cache.used -= job.reserved
			job.reserved = 0
		}
	}}, job.status, nil
}
func (cache *EntryCache) removeLocked(job *entryJob) {
	if job.refs != 0 {
		return
	}
	if err := os.RemoveAll(job.directory); err != nil {
		return
	}
	cache.used -= job.reserved
	delete(cache.jobs, job.status.ID)
	if cache.keys[job.key] == job.status.ID {
		delete(cache.keys, job.key)
	}
}
func (cache *EntryCache) sweep(now time.Time) {
	cache.mu.Lock()
	defer cache.mu.Unlock()
	cache.sweepLocked(now)
}
func (cache *EntryCache) sweepLocked(now time.Time) {
	for _, job := range cache.jobs {
		if now.Sub(job.touched) <= cache.config.TTL || job.refs > 0 {
			continue
		}
		if job.status.State == "queued" || job.status.State == "checking" || job.status.State == "copying" || job.status.State == "canceling" {
			job.cancel()
			continue
		}
		cache.removeLocked(job)
	}
}
func (cache *EntryCache) Close() error {
	cache.mu.Lock()
	cache.cancel()
	cache.mu.Unlock()
	cache.wg.Wait()
	return os.RemoveAll(cache.directory)
}

// MaterializeEntry copies exactly one safe regular entry, then finishes scanning
// to reject later duplicate entries. Publication happens only after this returns.
func MaterializeEntry(ctx context.Context, input EntryInput, target io.Writer, limits Limits, reserve func(Entry) error, progress func(int64) error) (Entry, error) {
	if input.Filesystem == nil || target == nil || input.SourceSize < 0 {
		return Entry{}, ErrUnsafeEntry
	}
	limits = limits.normalized()
	selected, err := cleanEntryPath(input.EntryPath)
	if err != nil || selected == "." {
		return Entry{}, ErrUnsafeEntry
	}
	filesystem := entryContextFS{Fs: input.Filesystem, ctx: ctx}
	listing, err := List(ctx, filesystem, input.ArchivePath, limits)
	if err != nil {
		return Entry{}, err
	}
	if listing.Truncated {
		return Entry{}, ErrLimitExceeded
	}
	if listing.SourceSize != input.SourceSize || listing.SourceModified != input.SourceModified {
		return Entry{}, ErrArchiveChanged
	}
	var expected *Entry
	for index := range listing.Entries {
		if listing.Entries[index].Path == selected {
			expected = &listing.Entries[index]
			break
		}
	}
	if expected == nil || expected.IsDir {
		return Entry{}, ErrUnsafeEntry
	}
	if reserve != nil {
		if err := reserve(*expected); err != nil {
			return Entry{}, err
		}
	}
	opened, err := openArchive(ctx, filesystem, input.ArchivePath)
	if err != nil {
		return Entry{}, err
	}
	defer func() { _ = opened.Close() }()
	if opened.info.Size() != input.SourceSize || opened.info.ModTime().UnixMilli() != input.SourceModified {
		return Entry{}, ErrArchiveChanged
	}
	seen := false
	err = opened.extractor.Extract(ctx, opened.reader, func(ctx context.Context, info archives.FileInfo) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		entry, cleanErr := cleanEntryPath(info.NameInArchive)
		if cleanErr != nil || entry != selected {
			return nil
		}
		if seen || isLink(info) || !info.Mode().IsRegular() || info.IsDir() {
			return ErrUnsafeEntry
		}
		seen = true
		if info.Size() != expected.Size || info.ModTime().UnixMilli() != expected.Modified {
			return ErrArchiveChanged
		}
		reader, err := info.Open()
		if err != nil {
			return err
		}
		defer func() { _ = reader.Close() }()
		writer := &entryWriter{ctx: ctx, target: target, report: progress, maximum: expected.Size}
		written, err := io.Copy(writer, io.LimitReader(reader, expected.Size+1))
		if err != nil {
			return err
		}
		if written != expected.Size {
			return ErrArchiveChanged
		}
		return nil
	})
	if err != nil {
		return Entry{}, err
	}
	if !seen {
		return Entry{}, ErrArchiveChanged
	}
	if err := VerifyEntrySource(input.Filesystem, input); err != nil {
		return Entry{}, err
	}
	return *expected, ctx.Err()
}

type entryWriter struct {
	ctx     context.Context
	target  io.Writer
	report  func(int64) error
	written int64
	maximum int64
}

// Closing the source interrupts both the initial listing scan and the second
// extraction pass, including long skips inside compressed TAR readers.
type entryContextFS struct {
	afero.Fs
	ctx context.Context
}

func (filesystem entryContextFS) Open(name string) (afero.File, error) {
	if err := filesystem.ctx.Err(); err != nil {
		return nil, err
	}
	file, err := filesystem.Fs.Open(name)
	if err != nil {
		return nil, err
	}
	owned := &entryContextFile{File: file, done: make(chan struct{})}
	go func() {
		select {
		case <-filesystem.ctx.Done():
			_ = owned.Close()
		case <-owned.done:
		}
	}()
	return owned, nil
}

type entryContextFile struct {
	afero.File
	done chan struct{}
	once sync.Once
}

func (file *entryContextFile) Close() error {
	var err error
	file.once.Do(func() { close(file.done); err = file.File.Close() })
	return err
}

func (writer *entryWriter) Write(value []byte) (int, error) {
	if err := writer.ctx.Err(); err != nil {
		return 0, err
	}
	if int64(len(value)) > writer.maximum-writer.written {
		return 0, ErrArchiveChanged
	}
	n, err := writer.target.Write(value)
	writer.written += int64(n)
	if err == nil && writer.report != nil {
		err = writer.report(writer.written)
	}
	return n, err
}
