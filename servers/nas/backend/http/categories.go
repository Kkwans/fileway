package fbhttp

import (
	"bufio"
	"fmt"
	"io"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/risk"
)

// CategoryRule defines a classification rule for directories.
type CategoryRule struct {
	ID       string   `json:"id"`
	Name     string   `json:"name"`
	Icon     string   `json:"icon"`
	Color    string   `json:"color"`
	Patterns []string `json:"patterns"` // glob patterns or prefix matches
}

// Category represents a group of directories with shared properties.
type Category struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Icon  string `json:"icon"`
	Color string `json:"color"`
}

// DirectoryCategory maps a directory path to its category.
type DirectoryCategory struct {
	Path     string   `json:"path"`
	Name     string   `json:"name"`
	Category Category `json:"category"`
	Risk     string   `json:"risk"` // high, medium, low
}

// Built-in category rules for NAS directory classification.
var builtinCategoryRules = []CategoryRule{
	{
		ID:    "personal",
		Name:  "个人文件夹",
		Icon:  "person",
		Color: "#4CAF50",
		Patterns: []string{
			"/volume*/@home/*",
		},
	},
	{
		ID:    "shared",
		Name:  "共享文件夹",
		Icon:  "group",
		Color: "#2196F3",
		Patterns: []string{
			"/volume*/OpenClaw",
			"/volume*/Project",
			"/volume*/Hermes",
			"/volume*/docker",
			"/volume*/docker-apps",
			"/volume*/DockerProject",
			"/volume*/Download",
			"/volume*/Movie",
			"/volume*/Movies",
			"/volume*/Music",
			"/volume*/Photos",
			"/volume*/Pictures",
			"/volume*/TV",
			"/volume*/Video",
			"/volume*/Videos",
			"/volume*/Documents",
			"/volume*/Common",
			"/volume*/ViEDO",
			"/volume*/公共文件夹",
			"/volume*/团队文件夹",
			"/volume*/迅雷下载",
		},
	},
	{
		ID:    "system",
		Name:  "系统文件夹",
		Icon:  "settings",
		Color: "#FF9800",
		Patterns: []string{
			"/volume*/@appstore",
			"/volume*/@docker",
			"/volume*/@home",
			"/volume*/@tmp",
			"/volume*/@upload",
			"/volume*/@search",
			"/volume*/@thumbnail",
			"/volume*/@RecentlyScan",
			"/volume*/@eaDir",
			"/volume*/Docker",
		},
	},
}

const defaultSharedFolderConfigPath = "/run/ugreen/smbshare.conf"

func parseRegisteredSharedFolderPatterns(reader io.Reader) ([]string, error) {
	patterns := make([]string, 0)
	seen := make(map[string]struct{})
	scanner := bufio.NewScanner(reader)
	section := ""

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.TrimSpace(strings.TrimSuffix(strings.TrimPrefix(line, "["), "]"))
			continue
		}
		if section == "" || section == "personal_folder" {
			continue
		}

		key, value, found := strings.Cut(line, "=")
		if !found || !strings.EqualFold(strings.TrimSpace(key), "path") {
			continue
		}
		registeredPath := path.Clean(strings.TrimSpace(value))
		if !strings.HasPrefix(registeredPath, "/") {
			continue
		}
		if _, exists := seen[registeredPath]; exists {
			continue
		}
		seen[registeredPath] = struct{}{}
		patterns = append(patterns, registeredPath)
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}

	sort.Strings(patterns)
	return patterns, nil
}

func registeredSharedFolderPatterns(configPath string) ([]string, error) {
	file, err := os.Open(configPath)
	if err != nil {
		return nil, err
	}
	defer func() { _ = file.Close() }()

	return parseRegisteredSharedFolderPatterns(file)
}

func buildCategoryRules(registeredSharedFolders []string) []CategoryRule {
	rules := make([]CategoryRule, len(builtinCategoryRules))
	for i, rule := range builtinCategoryRules {
		rules[i] = rule
		rules[i].Patterns = append([]string(nil), rule.Patterns...)
	}

	if len(registeredSharedFolders) == 0 {
		return rules
	}
	for i := range rules {
		if rules[i].ID != "shared" {
			continue
		}
		rules[i].Patterns = append([]string(nil), registeredSharedFolders...)
		break
	}

	return rules
}

func currentCategoryRules() []CategoryRule {
	configPath := os.Getenv("UGREEN_SMB_SHARE_CONFIG")
	if configPath == "" {
		configPath = defaultSharedFolderConfigPath
	}
	registered, err := registeredSharedFolderPatterns(configPath)
	if err != nil {
		return buildCategoryRules(nil)
	}
	return buildCategoryRules(registered)
}

// classifyPath determines which category a directory path belongs to.
// Returns the matching category, or a default "other" category if no match.
func classifyPath(path string) Category {
	cleaned := filepath.Clean(path)

	for _, rule := range currentCategoryRules() {
		for _, pattern := range rule.Patterns {
			if matchPattern(pattern, cleaned) {
				return Category{
					ID:    rule.ID,
					Name:  rule.Name,
					Icon:  rule.Icon,
					Color: rule.Color,
				}
			}
		}
	}

	// Default category for unmatched paths
	return Category{
		ID:    "other",
		Name:  "其他",
		Icon:  "folder",
		Color: "#9E9E9E",
	}
}

// matchPattern checks if a path matches a glob-like pattern.
// Supports * wildcard for single path component and ** for multi-level.
func matchPattern(pattern, path string) bool {
	// Normalize paths
	pattern = filepath.Clean(pattern)
	path = filepath.Clean(path)

	// Direct prefix match for patterns ending with /*
	if strings.HasSuffix(pattern, "/*") {
		prefix := strings.TrimSuffix(pattern, "/*")
		// Split pattern and path into components for glob matching
		patternParts := strings.Split(prefix, "/")
		pathParts := strings.Split(path, "/")
		if len(pathParts) < len(patternParts) {
			return false
		}
		// Match each component with glob support
		for i, pp := range patternParts {
			matched, _ := filepath.Match(pp, pathParts[i])
			if !matched {
				return false
			}
		}
		return true
	}

	// Use filepath.Match for glob matching
	matched, err := filepath.Match(pattern, path)
	if err != nil {
		return false
	}
	return matched
}

// riskLevel determines the risk level of a directory path.
func riskLevel(path string) string {
	return string(risk.Classify(path))
}

// categoriesHandler returns the category classification rules and risk levels.
var categoriesHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if !d.user.Perm.Admin {
		return http.StatusForbidden, fmt.Errorf("没有访问权限")
	}

	type CategoryInfo struct {
		Categories []CategoryRule `json:"categories"`
	}

	info := CategoryInfo{
		Categories: currentCategoryRules(),
	}

	return renderJSON(w, r, info)
})

// classifyHandler classifies a given path and returns its category + risk level.
var classifyHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	path := r.URL.Query().Get("path")
	if path == "" {
		return http.StatusBadRequest, fmt.Errorf("缺少 path 参数")
	}

	type ClassifyResult struct {
		Path     string   `json:"path"`
		Category Category `json:"category"`
		Risk     string   `json:"risk"`
	}

	result := ClassifyResult{
		Path:     path,
		Category: classifyPath(path),
		Risk:     riskLevel(path),
	}

	return renderJSON(w, r, result)
})
