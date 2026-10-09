package files

import (
	"context"
	"errors"
	"io"
)

var ErrChecksumSourceChanged = errors.New("file changed during checksum; refresh and retry")

type checksumReader struct {
	ctx    context.Context
	reader io.Reader
}

func (reader checksumReader) Read(buffer []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	return reader.reader.Read(buffer)
}
