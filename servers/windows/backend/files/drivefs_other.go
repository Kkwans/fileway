//go:build !windows

package files

import (
	"os"
	"time"

	"github.com/spf13/afero"
)

// DriveFs is a stub on non-Windows platforms so NAS/linux builds compile.
// It implements afero.Fs but every operation fails; UseDriveFs is false
// outside Windows.
type DriveFs struct {
	base afero.Fs
}

// NewDriveFs returns a stub filesystem that rejects operations.
func NewDriveFs() *DriveFs {
	return &DriveFs{base: afero.NewOsFs()}
}

func (d *DriveFs) Name() string { return "DriveFs-stub" }

func (d *DriveFs) RealPath(name string) (string, error) {
	return name, errNotDrivePath
}

func (d *DriveFs) Create(string) (afero.File, error) { return nil, errNotDrivePath }
func (d *DriveFs) Mkdir(string, os.FileMode) error   { return errNotDrivePath }
func (d *DriveFs) MkdirAll(string, os.FileMode) error {
	return errNotDrivePath
}
func (d *DriveFs) Open(string) (afero.File, error) { return nil, errNotDrivePath }
func (d *DriveFs) OpenFile(string, int, os.FileMode) (afero.File, error) {
	return nil, errNotDrivePath
}
func (d *DriveFs) Remove(string) error         { return errNotDrivePath }
func (d *DriveFs) RemoveAll(string) error      { return errNotDrivePath }
func (d *DriveFs) Rename(string, string) error { return errNotDrivePath }
func (d *DriveFs) Stat(string) (os.FileInfo, error) {
	return nil, errNotDrivePath
}
func (d *DriveFs) Chmod(string, os.FileMode) error { return errNotDrivePath }
func (d *DriveFs) Chown(string, int, int) error    { return errNotDrivePath }
func (d *DriveFs) Chtimes(string, time.Time, time.Time) error {
	return errNotDrivePath
}

func (d *DriveFs) LstatIfPossible(string) (os.FileInfo, bool, error) {
	return nil, false, errNotDrivePath
}

// LogicalDrive is unused outside Windows.
type LogicalDrive struct {
	Letter    string
	Root      string
	Label     string
	DriveType string
	Info      os.FileInfo
}

// ListLogicalDrives returns empty on non-Windows platforms.
func ListLogicalDrives() ([]LogicalDrive, error) { return nil, nil }

var (
	_ afero.Fs      = (*DriveFs)(nil)
	_ afero.Lstater = (*DriveFs)(nil)
)
