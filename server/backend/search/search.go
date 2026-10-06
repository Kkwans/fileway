package search

import (
	"context"
	"errors"
	"os"
	"path"
	"path/filepath"
	"strings"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/rules"
)

var ErrLimitReached = errors.New("search result limit reached")

type Scope string

const (
	ScopeCurrent   Scope = "current"
	ScopeRecursive Scope = "recursive"
)

type Options struct {
	Scope               Scope
	Limit               int
	ExcludedDirectories map[string]struct{}
}

type searchOptions struct {
	CaseSensitive bool
	Conditions    []condition
	Terms         []string
}

// Search searches for a query in a fs.
func Search(ctx context.Context, fs afero.Fs, scope, query string, checker rules.Checker, options Options, found func(path string, f os.FileInfo) error) error {
	search := parseSearch(query)

	scope = filepath.ToSlash(filepath.Clean(scope))
	scope = path.Join("/", scope)

	count := 0
	visit := func(fPath string, f os.FileInfo) error {
		if ctx.Err() != nil {
			return context.Cause(ctx)
		}
		fPath = filepath.ToSlash(filepath.Clean(fPath))
		fPath = path.Join("/", fPath)
		relativePath := strings.TrimPrefix(fPath, scope)
		relativePath = strings.TrimPrefix(relativePath, "/")

		if fPath == scope {
			return nil
		}
		if f.IsDir() {
			if _, excluded := options.ExcludedDirectories[strings.ToLower(f.Name())]; excluded {
				return filepath.SkipDir
			}
			if !checker.Check(fPath) {
				return filepath.SkipDir
			}
		}

		// Optimization: match filename first (cheap string ops) before
		// expensive permission checking. This avoids running the checker
		// on files that don't match the search terms.
		if len(search.Terms) > 0 {
			_, fileName := path.Split(fPath)
			matched := false
			for _, term := range search.Terms {
				checkName := fileName
				checkTerm := term
				if !search.CaseSensitive {
					checkName = strings.ToLower(checkName)
					checkTerm = strings.ToLower(checkTerm)
				}
				if strings.Contains(checkName, checkTerm) {
					matched = true
					break
				}
			}
			if !matched {
				return nil
			}
		}

		if !f.IsDir() && !checker.Check(fPath) {
			return nil
		}

		if len(search.Conditions) > 0 {
			match := false

			for _, t := range search.Conditions {
				if t(fPath) {
					match = true
					break
				}
			}

			if !match {
				return nil
			}
		}

		if err := found(relativePath, f); err != nil {
			return err
		}
		count++
		if options.Limit > 0 && count >= options.Limit {
			return ErrLimitReached
		}
		return nil
	}

	if options.Scope == ScopeCurrent {
		entries, err := afero.ReadDir(fs, scope)
		if err != nil {
			return err
		}
		for _, entry := range entries {
			err := visit(path.Join(scope, entry.Name()), entry)
			if errors.Is(err, filepath.SkipDir) {
				continue
			}
			if err != nil {
				return err
			}
		}
		return nil
	}

	return afero.Walk(fs, scope, func(fPath string, f os.FileInfo, walkErr error) error {
		if walkErr != nil {
			if f != nil && f.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		return visit(fPath, f)
	})
}
