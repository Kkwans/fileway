package fbhttp

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"io/fs"
	"log"
	"mime"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/auth"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/storage"
	"github.com/Kkwans/nas-file-browser/backend/version"
)

var hashedStaticAsset = regexp.MustCompile(`-[A-Za-z0-9_-]{8,}\.[A-Za-z0-9]+$`)

func acceptsContentEncoding(header, candidate string) bool {
	wildcardQuality := -1.0
	for _, value := range strings.Split(header, ",") {
		parts := strings.Split(value, ";")
		name := strings.ToLower(strings.TrimSpace(parts[0]))
		quality := 1.0
		for _, parameter := range parts[1:] {
			key, raw, found := strings.Cut(strings.TrimSpace(parameter), "=")
			if !found || !strings.EqualFold(key, "q") {
				continue
			}
			parsed, err := strconv.ParseFloat(raw, 64)
			if err != nil {
				quality = 0
			} else {
				quality = parsed
			}
		}
		if name == strings.ToLower(candidate) {
			return quality > 0
		}
		if name == "*" {
			wildcardQuality = quality
		}
	}
	return wildcardQuality > 0
}

func handleWithStaticData(w http.ResponseWriter, _ *http.Request, d *data, fSys fs.FS, file, contentType string) (int, error) {
	w.Header().Set("Content-Type", contentType)

	auther, err := d.store.Auth.Get(d.settings.AuthMethod)
	if err != nil {
		return http.StatusInternalServerError, err
	}

	data := map[string]interface{}{
		"Name":                  d.settings.Branding.Name,
		"DisableExternal":       d.settings.Branding.DisableExternal,
		"DisableUsedPercentage": d.settings.Branding.DisableUsedPercentage,
		"Color":                 d.settings.Branding.Color,
		"BaseURL":               d.server.BaseURL,
		"Version":               version.Version,
		"StaticURL":             path.Join(d.server.BaseURL, "/static"),
		"Signup":                d.settings.Signup,
		"NoAuth":                d.settings.AuthMethod == auth.MethodNoAuth,
		"AuthMethod":            d.settings.AuthMethod,
		"LogoutPage":            d.settings.LogoutPage,
		"LoginPage":             auther.LoginPage(),
		"CSS":                   false,
		"ReCaptcha":             false,
		"Theme":                 d.settings.Branding.Theme,
		"EnableThumbs":          d.server.EnableThumbnails,
		"ResizePreview":         d.server.ResizePreview,
		"EnableExec":            d.server.EnableExec,
		"TusSettings":           d.settings.Tus,
		"HideLoginButton":       d.settings.HideLoginButton,
	}

	if d.settings.Branding.Files != "" {
		fPath := filepath.Join(d.settings.Branding.Files, "custom.css")
		_, err := os.Stat(fPath)

		if err != nil && !os.IsNotExist(err) {
			log.Printf("couldn't load custom styles: %v", err)
		}

		if err == nil {
			data["CSS"] = true
		}
	}

	if d.settings.AuthMethod == auth.MethodJSONAuth {
		raw, err := d.store.Auth.Get(d.settings.AuthMethod)
		if err != nil {
			return http.StatusInternalServerError, err
		}

		auther := raw.(*auth.JSONAuth)

		if auther.ReCaptcha != nil {
			data["ReCaptcha"] = auther.ReCaptcha.Key != "" && auther.ReCaptcha.Secret != ""
			data["ReCaptchaHost"] = auther.ReCaptcha.Host
			data["ReCaptchaKey"] = auther.ReCaptcha.Key
		}
	}

	b, err := json.Marshal(data)
	if err != nil {
		return http.StatusInternalServerError, err
	}

	data["Json"] = template.JS(strings.ReplaceAll(string(b), `'`, `\'`))

	fileContents, err := fs.ReadFile(fSys, file)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return http.StatusNotFound, err
		}
		return http.StatusInternalServerError, err
	}
	index := template.Must(template.New("index").Delims("[{[", "]}]").Parse(string(fileContents)))
	err = index.Execute(w, data)
	if err != nil {
		return http.StatusInternalServerError, err
	}

	return 0, nil
}

func getStaticHandlers(store *storage.Storage, server *settings.Server, assetsFs fs.FS) (index, static http.Handler) {
	index = handle(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if r.Method != http.MethodGet {
			return http.StatusNotFound, fmt.Errorf("resource not found")
		}

		w.Header().Set("x-xss-protection", "1; mode=block")
		w.Header().Set("Cache-Control", "no-cache")
		return handleWithStaticData(w, r, d, assetsFs, "public/index.html", "text/html; charset=utf-8")
	}, "", store, server)

	static = handle(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if r.Method != http.MethodGet {
			return http.StatusNotFound, fmt.Errorf("resource not found")
		}

		if strings.HasSuffix(r.URL.Path, "/") {
			return http.StatusNotFound, fmt.Errorf("resource not found")
		}

		w.Header().Add("Vary", "Accept-Encoding")
		if hashedStaticAsset.MatchString(r.URL.Path) {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		} else {
			w.Header().Set("Cache-Control", "public, max-age=86400")
		}

		if d.settings.Branding.Files != "" {
			if strings.HasPrefix(r.URL.Path, "img/") {
				fPath := filepath.Join(d.settings.Branding.Files, r.URL.Path)
				_, err := os.Stat(fPath)
				if err != nil && !os.IsNotExist(err) {
					log.Printf("could not load branding file override: %v", err)
				} else if err == nil {
					http.ServeFile(w, r, fPath)
					return 0, nil
				}
			} else if r.URL.Path == "custom.css" && d.settings.Branding.Files != "" {
				http.ServeFile(w, r, filepath.Join(d.settings.Branding.Files, "custom.css"))
				return 0, nil
			}
		}

		acceptEncoding := r.Header.Get("Accept-Encoding")
		for _, candidate := range []struct {
			name     string
			suffix   string
			encoding string
		}{
			{name: "br", suffix: ".br", encoding: "br"},
			{name: "gzip", suffix: ".gz", encoding: "gzip"},
		} {
			if !acceptsContentEncoding(acceptEncoding, candidate.name) {
				continue
			}
			compressed, err := assetsFs.Open(r.URL.Path + candidate.suffix)
			if err != nil {
				continue
			}
			defer func() { _ = compressed.Close() }()
			w.Header().Set("Content-Encoding", candidate.encoding)
			contentType := mime.TypeByExtension(filepath.Ext(r.URL.Path))
			if contentType != "" {
				w.Header().Set("Content-Type", contentType)
			}
			info, statErr := compressed.Stat()
			if statErr != nil {
				return http.StatusInternalServerError, statErr
			}
			content, seekable := compressed.(io.ReadSeeker)
			if !seekable {
				payload, readErr := io.ReadAll(compressed)
				if readErr != nil {
					return http.StatusInternalServerError, readErr
				}
				content = bytes.NewReader(payload)
			}
			http.ServeContent(w, r, r.URL.Path, info.ModTime(), content)
			return 0, nil
		}

		http.FileServer(http.FS(assetsFs)).ServeHTTP(w, r)
		return 0, nil
	}, "/static/", store, server)

	return index, static
}
