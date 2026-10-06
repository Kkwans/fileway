package files

import "errors"

var (
	errVirtualRoot    = errors.New("virtual computer root")
	errNotDrivePath   = errors.New("path is not under a windows drive")
	errDriveNotReady  = errors.New("drive is not ready")
	errInvalidListing = errors.New("invalid directory listing")
)
