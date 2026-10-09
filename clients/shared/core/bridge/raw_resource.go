package bridge

import (
	"bytes"
	"encoding/base64"
	"errors"
	"net/url"
	"strings"
)

const maxRawResourceBody = 10 << 20
const maxRawResourceEnvelope = 15 << 20

func rawResourceBody(command Command) ([]byte, error) {
	endpoint, err := url.Parse(command.Endpoint)
	jsonBody := bytes.TrimSpace(command.Body)
	if err != nil || endpoint.IsAbs() || endpoint.Host != "" || !strings.HasPrefix(endpoint.Path, "/api/resources/") ||
		(command.Method != "PUT" && command.Method != "POST") || (len(jsonBody) != 0 && string(jsonBody) != "null") || command.BodyBase64 == nil ||
		len(*command.BodyBase64) > base64.StdEncoding.EncodedLen(maxRawResourceBody) {
		return nil, errors.New("invalid raw resource write")
	}
	body, err := base64.StdEncoding.DecodeString(*command.BodyBase64)
	if err != nil || len(body) > maxRawResourceBody {
		return nil, errors.New("invalid raw resource bytes")
	}
	return body, nil
}
