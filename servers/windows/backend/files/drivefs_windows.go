//go:build windows

package files

import (
	"os"
	"path/filepath"
	"strings"
	"time"
	"unsafe"

	"github.com/spf13/afero"
	"golang.org/x/sys/windows"
)

// DriveFs maps virtual multi-drive paths (/C/Users) onto Windows paths (C:\Users).
// The virtual root "/" lists logical drives as directory entries.
type DriveFs struct {
	base afero.Fs
}

// NewDriveFs creates a multi-drive filesystem over the OS filesystem.
func NewDriveFs() *DriveFs {
	return &DriveFs{base: afero.NewOsFs()}
}

func (d *DriveFs) Name() string { return "DriveFs" }

// RealPath resolves a virtual path to a native Windows path.
func (d *DriveFs) RealPath(name string) (string, error) {
	cleaned := NormalizeVirtualPath(name)
	if cleaned == "/" {
		return "", errVirtualRoot
	}
	return VirtualToWindowsPath(cleaned)
}

func (d *DriveFs) resolve(name string) (string, error) {
	return d.RealPath(name)
}

func (d *DriveFs) Create(name string) (afero.File, error) {
	real, err := d.resolve(name)
	if err != nil {
		return nil, err
	}
	f, err := d.base.Create(real)
	if err != nil {
		return nil, err
	}
	return &driveFile{File: f, virtual: NormalizeVirtualPath(name), fs: d}, nil
}

func (d *DriveFs) Mkdir(name string, perm os.FileMode) error {
	real, err := d.resolve(name)
	if err != nil {
		return err
	}
	return d.base.Mkdir(real, perm)
}

func (d *DriveFs) MkdirAll(path string, perm os.FileMode) error {
	cleaned := NormalizeVirtualPath(path)
	if cleaned == "/" {
		return nil
	}
	real, err := d.resolve(cleaned)
	if err != nil {
		return err
	}
	return d.base.MkdirAll(real, perm)
}

func (d *DriveFs) Open(name string) (afero.File, error) {
	cleaned := NormalizeVirtualPath(name)
	if cleaned == "/" {
		return d.openRoot()
	}
	real, err := d.resolve(cleaned)
	if err != nil {
		return nil, err
	}
	f, err := d.base.Open(real)
	if err != nil {
		return nil, err
	}
	return &driveFile{File: f, virtual: cleaned, fs: d}, nil
}

func (d *DriveFs) OpenFile(name string, flag int, perm os.FileMode) (afero.File, error) {
	cleaned := NormalizeVirtualPath(name)
	if cleaned == "/" {
		if flag&(os.O_WRONLY|os.O_RDWR|os.O_CREATE|os.O_TRUNC|os.O_APPEND) != 0 {
			return nil, errVirtualRoot
		}
		return d.openRoot()
	}
	real, err := d.resolve(cleaned)
	if err != nil {
		return nil, err
	}
	f, err := d.base.OpenFile(real, flag, perm)
	if err != nil {
		return nil, err
	}
	return &driveFile{File: f, virtual: cleaned, fs: d}, nil
}

func (d *DriveFs) Remove(name string) error {
	if err := d.guardManagedPath(name); err != nil {
		return err
	}
	real, err := d.resolve(name)
	if err != nil {
		return err
	}
	return d.base.Remove(real)
}

func (d *DriveFs) RemoveAll(path string) error {
	if err := d.guardManagedPath(path); err != nil {
		return err
	}
	real, err := d.resolve(path)
	if err != nil {
		return err
	}
	return d.base.RemoveAll(real)
}

func (d *DriveFs) Rename(oldname, newname string) error {
	if err := d.guardManagedPath(oldname); err != nil {
		return err
	}
	if err := d.guardManagedPath(newname); err != nil {
		return err
	}
	oldReal, err := d.resolve(oldname)
	if err != nil {
		return err
	}
	newReal, err := d.resolve(newname)
	if err != nil {
		return err
	}
	return d.base.Rename(oldReal, newReal)
}

// guardManagedPath blocks destructive ops on virtual root and drive roots.
func (d *DriveFs) guardManagedPath(name string) error {
	cleaned := NormalizeVirtualPath(name)
	if cleaned == "/" {
		return errVirtualRoot
	}
	trimmed := strings.TrimPrefix(cleaned, "/")
	if IsDriveSegment(trimmed) {
		return errVirtualRoot
	}
	return nil
}

func (d *DriveFs) Stat(name string) (os.FileInfo, error) {
	cleaned := NormalizeVirtualPath(name)
	if cleaned == "/" {
		return rootDirInfo{}, nil
	}
	trimmed := strings.TrimPrefix(cleaned, "/")
	if IsDriveSegment(trimmed) {
		real, err := d.resolve(cleaned)
		if err != nil {
			return nil, err
		}
		info, err := d.base.Stat(real)
		if err != nil {
			return nil, err
		}
		return &driveRootInfo{FileInfo: info, name: strings.ToUpper(trimmed)}, nil
	}
	real, err := d.resolve(cleaned)
	if err != nil {
		return nil, err
	}
	return d.base.Stat(real)
}

func (d *DriveFs) LstatIfPossible(name string) (os.FileInfo, bool, error) {
	cleaned := NormalizeVirtualPath(name)
	if cleaned == "/" {
		return rootDirInfo{}, true, nil
	}
	real, err := d.resolve(cleaned)
	if err != nil {
		// Drive root still uses Stat path above; Lstat for drive letters.
		trimmed := strings.TrimPrefix(cleaned, "/")
		if IsDriveSegment(trimmed) {
			info, statErr := d.Stat(cleaned)
			return info, statErr == nil, statErr
		}
		return nil, false, err
	}
	if lstater, ok := d.base.(afero.Lstater); ok {
		info, supported, err := lstater.LstatIfPossible(real)
		if err != nil {
			return nil, false, err
		}
		return info, supported, nil
	}
	info, err := d.base.Stat(real)
	return info, false, err
}

func (d *DriveFs) Chmod(name string, mode os.FileMode) error {
	real, err := d.resolve(name)
	if err != nil {
		return err
	}
	return d.base.Chmod(real, mode)
}

func (d *DriveFs) Chown(name string, uid, gid int) error {
	real, err := d.resolve(name)
	if err != nil {
		return err
	}
	return d.base.Chown(real, uid, gid)
}

func (d *DriveFs) Chtimes(name string, atime, mtime time.Time) error {
	real, err := d.resolve(name)
	if err != nil {
		return err
	}
	return d.base.Chtimes(real, atime, mtime)
}

func (d *DriveFs) openRoot() (afero.File, error) {
	entries, err := ListLogicalDrives()
	if err != nil {
		return nil, err
	}
	infos := make([]os.FileInfo, 0, len(entries))
	for _, e := range entries {
		infos = append(infos, &driveRootInfo{
			FileInfo: e.Info,
			name:     e.Letter,
		})
	}
	return &driveRootDir{infos: infos}, nil
}

// ListLogicalDrives returns ready logical drives with optional volume info.
func ListLogicalDrives() ([]LogicalDrive, error) {
	mask, err := windows.GetLogicalDrives()
	if err != nil {
		return nil, err
	}
	var result []LogicalDrive
	for i := 0; i < 26; i++ {
		if mask&(1<<uint(i)) == 0 {
			continue
		}
		letter := string(rune('A' + i))
		root := letter + `:\`
		info, statErr := os.Stat(root)
		if statErr != nil {
			// Drive exists in bitmask but is not ready (empty card reader, etc.).
			continue
		}
		result = append(result, LogicalDrive{
			Letter:      letter,
			Root:        root,
			Label:       volumeLabel(root),
			DriveType:   driveType(root),
			Info:        info,
		})
	}
	return result, nil
}

// LogicalDrive describes one Windows logical drive.
type LogicalDrive struct {
	Letter    string
	Root      string
	Label     string
	DriveType string
	Info      os.FileInfo
}

func volumeLabel(root string) string {
	rootPtr, err := windows.UTF16PtrFromString(root)
	if err != nil {
		return ""
	}
	var volumeName [windows.MAX_PATH + 1]uint16
	kernel32 := windows.NewLazySystemDLL("kernel32.dll")
	proc := kernel32.NewProc("GetVolumeInformationW")
	r1, _, _ := proc.Call(
		uintptr(unsafe.Pointer(rootPtr)),
		uintptr(unsafe.Pointer(&volumeName[0])),
		uintptr(len(volumeName)),
		0, 0, 0, 0, 0,
	)
	if r1 == 0 {
		return ""
	}
	return windows.UTF16ToString(volumeName[:])
}

func driveType(root string) string {
	rootPtr, err := windows.UTF16PtrFromString(root)
	if err != nil {
		return "system"
	}
	kernel32 := windows.NewLazySystemDLL("kernel32.dll")
	proc := kernel32.NewProc("GetDriveTypeW")
	r, _, _ := proc.Call(uintptr(unsafe.Pointer(rootPtr)))
	switch r {
	case 2:
		return "usb"
	case 3:
		return "system"
	case 4:
		return "network"
	case 5:
		return "cdrom"
	case 6:
		return "usb"
	default:
		return "system"
	}
}

// --- root listing ---

type rootDirInfo struct{}

func (rootDirInfo) Name() string       { return "computer" }
func (rootDirInfo) Size() int64        { return 0 }
func (rootDirInfo) Mode() os.FileMode  { return os.ModeDir | 0o555 }
func (rootDirInfo) ModTime() time.Time { return time.Unix(0, 0) }
func (rootDirInfo) IsDir() bool        { return true }
func (rootDirInfo) Sys() any           { return nil }

type driveRootInfo struct {
	os.FileInfo
	name string
}

func (d *driveRootInfo) Name() string { return d.name }
func (d *driveRootInfo) IsDir() bool  { return true }

type driveRootDir struct {
	infos  []os.FileInfo
	offset int
}

func (d *driveRootDir) Close() error               { return nil }
func (d *driveRootDir) Name() string               { return "computer" }
func (d *driveRootDir) Stat() (os.FileInfo, error) { return rootDirInfo{}, nil }
func (d *driveRootDir) Sync() error                { return nil }
func (d *driveRootDir) Truncate(int64) error       { return errVirtualRoot }
func (d *driveRootDir) WriteString(string) (int, error) {
	return 0, errVirtualRoot
}
func (d *driveRootDir) Write([]byte) (int, error) { return 0, errVirtualRoot }
func (d *driveRootDir) WriteAt([]byte, int64) (int, error) {
	return 0, errVirtualRoot
}
func (d *driveRootDir) Read([]byte) (int, error) { return 0, os.ErrInvalid }

func (d *driveRootDir) Seek(offset int64, whence int) (int64, error) {
	switch whence {
	case 0:
		if offset < 0 {
			return 0, os.ErrInvalid
		}
		d.offset = int(offset)
		return offset, nil
	default:
		return 0, os.ErrInvalid
	}
}

func (d *driveRootDir) ReadAt([]byte, int64) (int, error) { return 0, os.ErrInvalid }

func (d *driveRootDir) Readdir(count int) ([]os.FileInfo, error) {
	if d.offset >= len(d.infos) {
		if count <= 0 {
			return nil, nil
		}
		return nil, os.ErrNotExist
	}
	if count <= 0 {
		out := d.infos[d.offset:]
		d.offset = len(d.infos)
		return out, nil
	}
	end := d.offset + count
	if end > len(d.infos) {
		end = len(d.infos)
	}
	out := d.infos[d.offset:end]
	d.offset = end
	return out, nil
}

func (d *driveRootDir) Readdirnames(n int) ([]string, error) {
	infos, err := d.Readdir(n)
	if err != nil {
		return nil, err
	}
	names := make([]string, 0, len(infos))
	for _, info := range infos {
		names = append(names, info.Name())
	}
	return names, nil
}

// --- wrapped OS files ---

type driveFile struct {
	afero.File
	virtual string
	fs      *DriveFs
}

func (f *driveFile) Name() string { return f.virtual }

func (f *driveFile) Readdir(count int) ([]os.FileInfo, error) {
	infos, err := f.File.Readdir(count)
	if err != nil {
		return infos, err
	}
	// Keep native names; FileInfo.Path is synthesized by files package from parent path.
	_ = infos
	return infos, nil
}

// Ensure interface compliance.
var (
	_ afero.Fs        = (*DriveFs)(nil)
	_ afero.Lstater   = (*DriveFs)(nil)
	_ afero.File      = (*driveRootDir)(nil)
	_ afero.File      = (*driveFile)(nil)
)

// RealPathFs marker for files package.
func (d *DriveFs) RealPathFile(name string) string {
	p, err := d.RealPath(name)
	if err != nil {
		return filepath.FromSlash(NormalizeVirtualPath(name))
	}
	return p
}
