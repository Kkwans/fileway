package fileutils

import (
	"context"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"

	"github.com/spf13/afero"
)

// CopyContext copies a regular file or directory tree while observing ctx.
// Directory entries are consumed in bounded batches and every opened handle is
// closed before the next batch is read. Symlinks are rejected so a task never
// follows a link outside the checked source tree.
func CopyContext(ctx context.Context, afs afero.Fs, src, dst string, fileMode, dirMode fs.FileMode) error {
	return CopyContextProgress(ctx, afs, src, dst, fileMode, dirMode, nil)
}

// CopyContextProgress is CopyContext with an optional callback receiving bytes
// written. The callback runs from the copying goroutine and may return a
// cancellation/error to stop the copy.
func CopyContextProgress(ctx context.Context, afs afero.Fs, src, dst string, fileMode, dirMode fs.FileMode, onBytes func(int64) error) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	src = path.Clean("/" + src)
	dst = path.Clean("/" + dst)
	if src == "/" || dst == "/" || src == dst {
		return os.ErrInvalid
	}
	info, err := lstat(afs, src)
	if err != nil {
		return err
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("不支持复制符号链接: %s", src)
	}
	if info.IsDir() {
		return copyDirContext(ctx, afs, src, dst, fileMode, dirMode, onBytes)
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("不支持复制特殊文件: %s", src)
	}
	return copyFileContext(ctx, afs, src, dst, info, fileMode, dirMode, onBytes)
}

func lstat(afs afero.Fs, name string) (os.FileInfo, error) {
	if lstater, ok := afs.(afero.Lstater); ok {
		info, _, err := lstater.LstatIfPossible(name)
		return info, err
	}
	return afs.Stat(name)
}

func copyDirContext(ctx context.Context, afs afero.Fs, src, dst string, fileMode, dirMode fs.FileMode, onBytes func(int64) error) error {
	info, err := lstat(afs, src)
	if err != nil {
		return err
	}
	if err := afs.MkdirAll(dst, info.Mode().Perm()|dirMode.Perm()); err != nil {
		return err
	}
	dir, err := afs.Open(src)
	if err != nil {
		return err
	}
	defer dir.Close()
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		entries, readErr := dir.Readdir(64)
		if len(entries) == 0 && readErr == io.EOF {
			return nil
		}
		if readErr != nil && readErr != io.EOF {
			return readErr
		}
		for _, entry := range entries {
			if err := ctx.Err(); err != nil {
				return err
			}
			childSrc := path.Join(src, entry.Name())
			childDst := path.Join(dst, entry.Name())
			childInfo, err := lstat(afs, childSrc)
			if err != nil {
				return err
			}
			if childInfo.Mode()&os.ModeSymlink != 0 {
				return fmt.Errorf("不支持复制符号链接: %s", childSrc)
			}
			if childInfo.IsDir() {
				if err := copyDirContext(ctx, afs, childSrc, childDst, fileMode, dirMode, onBytes); err != nil {
					return err
				}
				continue
			}
			if !childInfo.Mode().IsRegular() {
				return fmt.Errorf("不支持复制特殊文件: %s", childSrc)
			}
			if err := copyFileContext(ctx, afs, childSrc, childDst, childInfo, fileMode, dirMode, onBytes); err != nil {
				return err
			}
		}
		if readErr == io.EOF {
			return nil
		}
	}
}

func copyFileContext(ctx context.Context, afs afero.Fs, srcPath, dstPath string, info os.FileInfo, fileMode, dirMode fs.FileMode, onBytes func(int64) error) (err error) {
	src, err := afs.Open(srcPath)
	if err != nil {
		return err
	}
	defer func() {
		if closeErr := src.Close(); err == nil {
			err = closeErr
		}
	}()
	if err := afs.MkdirAll(filepath.Dir(dstPath), dirMode); err != nil {
		return err
	}
	dst, err := afs.OpenFile(dstPath, os.O_RDWR|os.O_CREATE|os.O_TRUNC, fileMode)
	if err != nil {
		return err
	}
	defer func() {
		if closeErr := dst.Close(); err == nil {
			err = closeErr
		}
	}()
	reader := contextReader{ctx: ctx, reader: src, onBytes: onBytes}
	if _, err := io.Copy(dst, reader); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	return afs.Chmod(dstPath, info.Mode().Perm())
}

type contextReader struct {
	ctx     context.Context
	reader  io.Reader
	onBytes func(int64) error
}

func (reader contextReader) Read(buffer []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	count, err := reader.reader.Read(buffer)
	if count > 0 && reader.onBytes != nil {
		if callbackErr := reader.onBytes(int64(count)); callbackErr != nil {
			return count, callbackErr
		}
	}
	return count, err
}
