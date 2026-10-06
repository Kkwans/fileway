package analysis

import (
	"container/heap"
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/spf13/afero"
)

const DefaultStorageResultLimit = 200

type StorageScope struct {
	Path        string `json:"path"`
	IsDir       bool   `json:"isDir"`
	Files       int    `json:"files"`
	Directories int    `json:"directories"`
	Bytes       int64  `json:"bytes"`
}

type StorageFile struct {
	Path     string `json:"path"`
	Size     int64  `json:"size"`
	Modified int64  `json:"modified"`
}

type StorageDirectory struct {
	Path  string `json:"path"`
	Files int    `json:"files"`
	Bytes int64  `json:"bytes"`
}

type StorageReport struct {
	Scopes             []StorageScope     `json:"scopes"`
	ScannedFiles       int                `json:"scannedFiles"`
	ScannedDirectories int                `json:"scannedDirectories"`
	ScannedBytes       int64              `json:"scannedBytes"`
	LargestFiles       []StorageFile      `json:"largestFiles"`
	LargestDirectories []StorageDirectory `json:"largestDirectories"`
	SkippedCount       int                `json:"skippedCount"`
	Skipped            []SkippedFile      `json:"skipped,omitempty"`
	Truncated          bool               `json:"truncated"`
	CompletedAt        int64              `json:"completedAt"`
	ResultLimit        int                `json:"resultLimit"`
}

type storageScanner struct {
	ctx            context.Context
	fs             afero.Fs
	checker        Checker
	reportProgress func(ScanProgress) error
	report         StorageReport
	directories    map[string]*StorageDirectory
	largestFiles   storageFileHeap
	seen           map[string]struct{}
	skipped        map[string]struct{}
	progress       ScanProgress
}

func AnalyzeStorage(
	ctx context.Context,
	filesystem afero.Fs,
	scopes []string,
	checker Checker,
	reportProgress func(ScanProgress) error,
) (*StorageReport, error) {
	if filesystem == nil || len(scopes) == 0 {
		return nil, fmt.Errorf("扫描文件系统和范围不能为空")
	}
	scanner := &storageScanner{
		ctx: ctx, fs: filesystem, checker: checker, reportProgress: reportProgress,
		directories: make(map[string]*StorageDirectory),
		seen:        make(map[string]struct{}), skipped: make(map[string]struct{}),
	}
	scanner.report.ResultLimit = DefaultStorageResultLimit
	scanner.report.Scopes = make([]StorageScope, 0, len(scopes))
	if err := scanner.publishProgress(true); err != nil {
		return nil, err
	}
	for _, scope := range scopes {
		if err := scanner.scanScope(filepath.Clean(scope)); err != nil {
			return nil, err
		}
	}
	if err := scanner.publishProgress(true); err != nil {
		return nil, err
	}
	scanner.finalize()
	scanner.report.CompletedAt = time.Now().UnixMilli()
	return &scanner.report, nil
}

func (scanner *storageScanner) scanScope(scope string) error {
	info, err := scanner.fs.Stat(scope)
	if err != nil {
		return fmt.Errorf("无法读取分析路径 %s: %w", scope, err)
	}
	summary := StorageScope{Path: scope, IsDir: info.IsDir()}
	err = afero.Walk(scanner.fs, scope, func(filePath string, info os.FileInfo, walkErr error) error {
		if err := scanner.ctx.Err(); err != nil {
			return err
		}
		if walkErr != nil {
			scanner.addStorageSkipped(filePath, "无法读取路径")
			if info != nil && info.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if scanner.checker != nil && !scanner.checker.Check(filePath) {
			if info.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if info.Mode()&os.ModeSymlink != 0 {
			scanner.addStorageSkipped(filePath, "已跳过符号链接")
			return nil
		}
		if _, exists := scanner.seen[filePath]; exists {
			if info.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		scanner.seen[filePath] = struct{}{}
		if info.IsDir() {
			scanner.ensureDirectory(filePath)
			scanner.report.ScannedDirectories++
			summary.Directories++
			scanner.progress.ProcessedItems++
			return scanner.publishProgress(false)
		}
		if !info.Mode().IsRegular() {
			scanner.addStorageSkipped(filePath, "已跳过特殊文件")
			return nil
		}
		size := info.Size()
		scanner.report.ScannedFiles++
		scanner.report.ScannedBytes += size
		summary.Files++
		summary.Bytes += size
		scanner.progress.ProcessedItems++
		scanner.progress.ProcessedBytes += size
		scanner.addLargestFile(StorageFile{
			Path: filePath, Size: size, Modified: info.ModTime().UnixMilli(),
		})
		if summary.IsDir {
			scanner.addDirectorySize(scope, filePath, size)
		}
		return scanner.publishProgress(false)
	})
	if err != nil {
		return err
	}
	scanner.report.Scopes = append(scanner.report.Scopes, summary)
	return nil
}

func (scanner *storageScanner) ensureDirectory(path string) *StorageDirectory {
	if existing := scanner.directories[path]; existing != nil {
		return existing
	}
	entry := &StorageDirectory{Path: path}
	scanner.directories[path] = entry
	return entry
}

func (scanner *storageScanner) addDirectorySize(scope, filePath string, size int64) {
	for directory := filepath.Dir(filePath); pathWithin(scope, directory); directory = filepath.Dir(directory) {
		entry := scanner.ensureDirectory(directory)
		entry.Files++
		entry.Bytes += size
		if directory == scope {
			break
		}
	}
}

func pathWithin(root, candidate string) bool {
	if root == string(filepath.Separator) {
		return strings.HasPrefix(candidate, root)
	}
	return candidate == root || strings.HasPrefix(candidate, root+string(filepath.Separator))
}

func (scanner *storageScanner) addLargestFile(file StorageFile) {
	if scanner.largestFiles.Len() < DefaultStorageResultLimit {
		heap.Push(&scanner.largestFiles, file)
		return
	}
	if storageFileBetter(file, scanner.largestFiles[0]) {
		heap.Pop(&scanner.largestFiles)
		heap.Push(&scanner.largestFiles, file)
		scanner.report.Truncated = true
		return
	}
	scanner.report.Truncated = true
}

func (scanner *storageScanner) addStorageSkipped(filePath, reason string) {
	if _, exists := scanner.skipped[filePath]; exists {
		return
	}
	scanner.skipped[filePath] = struct{}{}
	scanner.report.SkippedCount++
	if len(scanner.report.Skipped) < maxSkippedDetails {
		scanner.report.Skipped = append(scanner.report.Skipped, SkippedFile{Path: filePath, Reason: reason})
	}
}

func (scanner *storageScanner) publishProgress(force bool) error {
	if err := scanner.ctx.Err(); err != nil {
		return err
	}
	if scanner.reportProgress == nil || (!force && scanner.progress.ProcessedItems%64 != 0) {
		return nil
	}
	return scanner.reportProgress(scanner.progress)
}

func (scanner *storageScanner) finalize() {
	scanner.report.LargestFiles = make([]StorageFile, scanner.largestFiles.Len())
	for index := len(scanner.report.LargestFiles) - 1; index >= 0; index-- {
		scanner.report.LargestFiles[index] = heap.Pop(&scanner.largestFiles).(StorageFile)
	}
	sort.Slice(scanner.report.LargestFiles, func(left, right int) bool {
		return storageFileBetter(scanner.report.LargestFiles[left], scanner.report.LargestFiles[right])
	})

	directories := make([]StorageDirectory, 0, len(scanner.directories))
	for _, entry := range scanner.directories {
		directories = append(directories, *entry)
	}
	sort.Slice(directories, func(left, right int) bool {
		if directories[left].Bytes != directories[right].Bytes {
			return directories[left].Bytes > directories[right].Bytes
		}
		return directories[left].Path < directories[right].Path
	})
	if len(directories) > DefaultStorageResultLimit {
		directories = directories[:DefaultStorageResultLimit]
		scanner.report.Truncated = true
	}
	scanner.report.LargestDirectories = directories
}

func storageFileBetter(left, right StorageFile) bool {
	if left.Size != right.Size {
		return left.Size > right.Size
	}
	return left.Path < right.Path
}

type storageFileHeap []StorageFile

func (files storageFileHeap) Len() int { return len(files) }
func (files storageFileHeap) Less(left, right int) bool {
	return storageFileBetter(files[right], files[left])
}
func (files storageFileHeap) Swap(left, right int) {
	files[left], files[right] = files[right], files[left]
}
func (files *storageFileHeap) Push(value any) { *files = append(*files, value.(StorageFile)) }
func (files *storageFileHeap) Pop() any {
	previous := *files
	last := len(previous) - 1
	value := previous[last]
	*files = previous[:last]
	return value
}
