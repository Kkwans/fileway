package fbhttp

import (
	"path"
	"reflect"
	"strings"
	"testing"
)

func TestUGREENSharedFoldersAreClassifiedAsShared(t *testing.T) {
	t.Parallel()

	paths := []string{
		"/volume2/OpenClaw",
		"/volume2/Project",
		"/volume2/Hermes",
		"/volume2/docker",
		"/volume2/docker-apps",
		"/volume2/Common",
		"/volume2/DockerProject",
		"/volume2/Download",
		"/volume2/迅雷下载",
		"/volume2/ViEDO",
	}

	for _, path := range paths {
		path := path
		t.Run(path, func(t *testing.T) {
			t.Parallel()
			if category := classifyPath(path); category.ID != "shared" {
				t.Fatalf("classifyPath(%q) = %q, want shared", path, category.ID)
			}
		})
	}
}

func TestDockerSystemFolderRemainsSystem(t *testing.T) {
	t.Parallel()

	if category := classifyPath("/volume2/Docker"); category.ID != "system" {
		t.Fatalf("classifyPath(/volume2/Docker) = %q, want system", category.ID)
	}
}

func TestParseRegisteredSharedFolderPatterns(t *testing.T) {
	t.Parallel()

	config := `
[personal_folder]
path = %H

[Project]
path = /volume2/Project

[家庭资料]
path = /volume1/家庭资料

[外接备份]
path = /mnt/@ext/disk-1

[无路径配置]
browseable = yes
`
	got, err := parseRegisteredSharedFolderPatterns(strings.NewReader(config))
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		path.Clean("/mnt/@ext/disk-1"),
		path.Clean("/volume1/家庭资料"),
		path.Clean("/volume2/Project"),
	}

	if !reflect.DeepEqual(got, want) {
		t.Fatalf("parseRegisteredSharedFolderPatterns() = %#v, want %#v", got, want)
	}
}

func TestBuildCategoryRulesUsesRegisteredSharedFoldersAsAuthority(t *testing.T) {
	t.Parallel()

	sharedPath := path.Clean("/volume1/家庭资料")
	rules := buildCategoryRules([]string{sharedPath})
	var shared CategoryRule
	for _, rule := range rules {
		if rule.ID == "shared" {
			shared = rule
			break
		}
	}

	found := false
	for _, pattern := range shared.Patterns {
		if pattern == sharedPath {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("shared rules do not include discovered path %q: %#v", sharedPath, shared.Patterns)
	}
	if len(shared.Patterns) != 1 {
		t.Fatalf("shared rules = %#v, want only registered paths", shared.Patterns)
	}
}
