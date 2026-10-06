package analysis

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/spf13/afero"
)

const (
	DefaultSampleBytes = int64(64 * 1024)
	DefaultResultFiles = 10_000
	maxSkippedDetails  = 100
)

type Checker interface {
	Check(path string) bool
}

type ScanProgress struct {
	TotalItems     int
	ProcessedItems int
	TotalBytes     int64
	ProcessedBytes int64
}

type DuplicateFile struct {
	Identity *files.Identity `json:"identity,omitempty"`
	Created  *time.Time      `json:"created,omitempty"`
	Path     string          `json:"path"`
	Size     int64           `json:"size"`
	Modified int64           `json:"modified"`
}

type DuplicateGroup struct {
	SuggestedKeepPath string          `json:"suggestedKeepPath,omitempty"`
	KeepReason        string          `json:"keepReason"`
	SHA256            string          `json:"sha256"`
	Size              int64           `json:"size"`
	TotalFiles        int             `json:"totalFiles"`
	ReclaimableBytes  int64           `json:"reclaimableBytes"`
	Files             []DuplicateFile `json:"files"`
}

type SkippedFile struct {
	Path   string `json:"path"`
	Reason string `json:"reason"`
}

type DuplicateReport struct {
	SchemaVersion    int              `json:"schemaVersion"`
	Scopes           []string         `json:"scopes"`
	ScannedFiles     int              `json:"scannedFiles"`
	ScannedBytes     int64            `json:"scannedBytes"`
	CandidateFiles   int              `json:"candidateFiles"`
	DuplicateGroups  int              `json:"duplicateGroups"`
	DuplicateFiles   int              `json:"duplicateFiles"`
	ReclaimableBytes int64            `json:"reclaimableBytes"`
	Groups           []DuplicateGroup `json:"groups"`
	SkippedCount     int              `json:"skippedCount"`
	Skipped          []SkippedFile    `json:"skipped,omitempty"`
	Truncated        bool             `json:"truncated"`
	CompletedAt      int64            `json:"completedAt"`
	ResultFileLimit  int              `json:"resultFileLimit"`
}

type duplicateCandidate struct {
	path     string
	size     int64
	modified time.Time
	created  *time.Time
	identity *files.Identity
}

type duplicateScanner struct {
	ctx             context.Context
	fs              afero.Fs
	checker         Checker
	sampleBytes     int64
	resultFileLimit int
	reportProgress  func(ScanProgress) error
	report          DuplicateReport
	skipped         map[string]struct{}
}

func FindDuplicates(
	ctx context.Context,
	filesystem afero.Fs,
	scopes []string,
	checker Checker,
	reportProgress func(ScanProgress) error,
) (*DuplicateReport, error) {
	if filesystem == nil || len(scopes) == 0 {
		return nil, fmt.Errorf("扫描文件系统和范围不能为空")
	}
	scanner := &duplicateScanner{
		ctx: ctx, fs: filesystem, checker: checker,
		sampleBytes: DefaultSampleBytes, resultFileLimit: DefaultResultFiles,
		reportProgress: reportProgress, skipped: make(map[string]struct{}),
	}
	scanner.report.SchemaVersion = 3
	scanner.report.Scopes = append([]string(nil), scopes...)
	scanner.report.Groups = make([]DuplicateGroup, 0)
	scanner.report.ResultFileLimit = scanner.resultFileLimit

	files, err := scanner.collect(scopes)
	if err != nil {
		return nil, err
	}
	if err := scanner.compare(files); err != nil {
		return nil, err
	}
	scanner.report.CompletedAt = time.Now().UnixMilli()
	return &scanner.report, nil
}

func (scanner *duplicateScanner) collect(scopes []string) ([]duplicateCandidate, error) {
	seen := make(map[string]struct{})
	files := make([]duplicateCandidate, 0)
	for _, scope := range scopes {
		if err := scanner.ctx.Err(); err != nil {
			return nil, err
		}
		err := afero.Walk(scanner.fs, scope, func(filePath string, info os.FileInfo, walkErr error) error {
			if err := scanner.ctx.Err(); err != nil {
				return err
			}
			if walkErr != nil {
				scanner.addSkipped(filePath, "无法读取路径")
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
				scanner.addSkipped(filePath, "已跳过符号链接")
				return nil
			}
			if info.IsDir() || !info.Mode().IsRegular() {
				return nil
			}
			if _, exists := seen[filePath]; exists {
				return nil
			}
			seen[filePath] = struct{}{}
			files = append(files, duplicateCandidate{
				path: filePath, size: info.Size(), modified: info.ModTime(),
			})
			scanner.report.ScannedFiles++
			scanner.report.ScannedBytes += info.Size()
			return nil
		})
		if err != nil {
			return nil, err
		}
	}
	return files, nil
}

func (scanner *duplicateScanner) compare(candidatesIn []duplicateCandidate) error {
	bySize := make(map[int64][]duplicateCandidate)
	for _, file := range candidatesIn {
		bySize[file.size] = append(bySize[file.size], file)
	}
	candidates := make([]duplicateCandidate, 0)
	for _, sameSize := range bySize {
		if len(sameSize) > 1 {
			candidates = append(candidates, sameSize...)
		}
	}
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].path < candidates[j].path })
	scanner.report.CandidateFiles = len(candidates)
	progress := ScanProgress{TotalItems: len(candidates) * 2}
	for _, file := range candidates {
		progress.TotalBytes += file.size
	}
	if err := scanner.progress(progress); err != nil {
		return err
	}

	type sampledCandidate struct {
		duplicateCandidate
		sample string
	}
	sampled := make([]sampledCandidate, 0, len(candidates))
	for _, candidate := range candidates {
		if err := scanner.ctx.Err(); err != nil {
			return err
		}
		candidate.created = files.CreatedTime(scanner.fs, candidate.path)
		candidate.identity = files.FileIdentity(scanner.fs, candidate.path)
		sample, err := scanner.hashSample(candidate)
		progress.ProcessedItems++
		if err != nil {
			scanner.addSkipped(candidate.path, "读取样本时文件不可用或已变化")
			progress.ProcessedItems++
			progress.ProcessedBytes += candidate.size
		} else {
			sampled = append(sampled, sampledCandidate{duplicateCandidate: candidate, sample: sample})
		}
		if err := scanner.progress(progress); err != nil {
			return err
		}
	}

	bySample := make(map[string][]duplicateCandidate)
	for _, candidate := range sampled {
		key := fmt.Sprintf("%d:%s", candidate.size, candidate.sample)
		bySample[key] = append(bySample[key], candidate.duplicateCandidate)
	}
	fullCandidates := make([]duplicateCandidate, 0)
	for _, sameSample := range bySample {
		if len(sameSample) > 1 {
			fullCandidates = append(fullCandidates, sameSample...)
			continue
		}
		progress.ProcessedItems++
		progress.ProcessedBytes += sameSample[0].size
	}
	if err := scanner.progress(progress); err != nil {
		return err
	}
	sort.Slice(fullCandidates, func(i, j int) bool { return fullCandidates[i].path < fullCandidates[j].path })

	byFullHash := make(map[string][]duplicateCandidate)
	for _, candidate := range fullCandidates {
		if err := scanner.ctx.Err(); err != nil {
			return err
		}
		fullHash, err := scanner.hashFull(candidate)
		progress.ProcessedItems++
		progress.ProcessedBytes += candidate.size
		if err != nil {
			scanner.addSkipped(candidate.path, "完整校验时文件不可用或已变化")
		} else {
			key := fmt.Sprintf("%d:%s", candidate.size, fullHash)
			byFullHash[key] = append(byFullHash[key], candidate)
		}
		if err := scanner.progress(progress); err != nil {
			return err
		}
	}

	groups := make([]DuplicateGroup, 0)
	for key, sameHash := range byFullHash {
		if len(sameHash) < 2 {
			continue
		}
		sort.Slice(sameHash, func(i, j int) bool { return sameHash[i].path < sameHash[j].path })
		separator := len(key) - 64
		group := DuplicateGroup{
			SHA256: key[separator:], Size: sameHash[0].size,
			TotalFiles: len(sameHash), ReclaimableBytes: int64(len(sameHash)-1) * sameHash[0].size,
			Files: make([]DuplicateFile, 0, len(sameHash)),
		}
		for _, file := range sameHash {
			group.Files = append(group.Files, DuplicateFile{
				Path: file.path, Size: file.size, Modified: file.modified.UnixMilli(),
				Created: file.created, Identity: file.identity,
			})
		}
		groups = append(groups, group)
		scanner.report.DuplicateGroups++
		scanner.report.DuplicateFiles += len(sameHash)
		scanner.report.ReclaimableBytes += group.ReclaimableBytes
	}
	sort.Slice(groups, func(i, j int) bool {
		if groups[i].ReclaimableBytes != groups[j].ReclaimableBytes {
			return groups[i].ReclaimableBytes > groups[j].ReclaimableBytes
		}
		return groups[i].Files[0].Path < groups[j].Files[0].Path
	})

	remaining := scanner.resultFileLimit
	for _, group := range groups {
		if remaining < 2 {
			scanner.report.Truncated = true
			break
		}
		if len(group.Files) > remaining {
			group.Files = group.Files[:remaining]
			scanner.report.Truncated = true
		}
		if len(group.Files) != group.TotalFiles {
			group.KeepReason = "truncated"
		} else {
			group.SuggestedKeepPath, group.KeepReason = RecommendDuplicateKeeper(group.Files)
		}
		scanner.report.Groups = append(scanner.report.Groups, group)
		remaining -= len(group.Files)
	}
	return nil
}

func (scanner *duplicateScanner) hashSample(file duplicateCandidate) (string, error) {
	if err := scanner.validate(file); err != nil {
		return "", err
	}
	handle, err := scanner.fs.Open(file.path)
	if err != nil {
		return "", err
	}
	defer func() { _ = handle.Close() }()
	hasher := sha256.New()
	firstBytes := min(file.size, scanner.sampleBytes)
	if _, err := io.CopyN(hasher, handle, firstBytes); err != nil {
		return "", err
	}
	if file.size > firstBytes {
		offset := max(firstBytes, file.size-scanner.sampleBytes)
		if _, err := handle.Seek(offset, io.SeekStart); err != nil {
			return "", err
		}
		if _, err := io.CopyN(hasher, handle, file.size-offset); err != nil {
			return "", err
		}
	}
	if err := scanner.validate(file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hasher.Sum(nil)), nil
}

func (scanner *duplicateScanner) hashFull(file duplicateCandidate) (string, error) {
	if err := scanner.validate(file); err != nil {
		return "", err
	}
	handle, err := scanner.fs.Open(file.path)
	if err != nil {
		return "", err
	}
	defer func() { _ = handle.Close() }()
	hasher := sha256.New()
	buffer := make([]byte, 128*1024)
	if _, err := io.CopyBuffer(hasher, &contextReader{ctx: scanner.ctx, reader: handle}, buffer); err != nil {
		return "", err
	}
	if err := scanner.validate(file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hasher.Sum(nil)), nil
}

func (scanner *duplicateScanner) validate(file duplicateCandidate) error {
	if err := scanner.ctx.Err(); err != nil {
		return err
	}
	info, err := scanner.fs.Stat(file.path)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || info.Size() != file.size || !info.ModTime().Equal(file.modified) {
		return fmt.Errorf("file changed during scan")
	}
	return nil
}

func (scanner *duplicateScanner) addSkipped(filePath, reason string) {
	if _, exists := scanner.skipped[filePath]; exists {
		return
	}
	scanner.skipped[filePath] = struct{}{}
	scanner.report.SkippedCount++
	if len(scanner.report.Skipped) < maxSkippedDetails {
		scanner.report.Skipped = append(scanner.report.Skipped, SkippedFile{Path: filePath, Reason: reason})
	}
}

func (scanner *duplicateScanner) progress(progress ScanProgress) error {
	if err := scanner.ctx.Err(); err != nil {
		return err
	}
	if scanner.reportProgress == nil {
		return nil
	}
	return scanner.reportProgress(progress)
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (reader *contextReader) Read(buffer []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	return reader.reader.Read(buffer)
}
