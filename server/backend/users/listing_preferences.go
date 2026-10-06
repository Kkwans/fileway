package users

import (
	"fmt"
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	ListingPreferencesVersion = 1
	MaxCustomPrefixRules      = 20
	MaxPrefixRunes            = 8
)

var builtInPrefixes = []string{".", "@", "#", "~", "$"}

// PrefixRule controls whether directory-listing entries with a matching name
// prefix are shown and expanded. Order is stable across devices.
type PrefixRule struct {
	Prefix   string `json:"prefix"`
	Visible  bool   `json:"visible"`
	Expanded bool   `json:"expanded"`
	Order    int    `json:"order"`
}

// ListingPreferences is the versioned per-user directory-listing contract.
type ListingPreferences struct {
	Version     int          `json:"version"`
	PrefixRules []PrefixRule `json:"prefixRules"`
}

// DefaultListingPreferences returns the built-in rules. Legacy hideDotfiles is
// migrated only into the dot-prefix visibility flag; it no longer controls
// direct path access.
func DefaultListingPreferences(hideDotfiles bool) ListingPreferences {
	rules := make([]PrefixRule, 0, len(builtInPrefixes))
	for index, prefix := range builtInPrefixes {
		rules = append(rules, PrefixRule{
			Prefix:   prefix,
			Visible:  prefix != "." || !hideDotfiles,
			Expanded: true,
			Order:    index,
		})
	}
	return ListingPreferences{Version: ListingPreferencesVersion, PrefixRules: rules}
}

// NormalizeListingPreferences validates, completes and deterministically sorts
// a preference payload. Missing version zero is the legacy migration state.
func NormalizeListingPreferences(value ListingPreferences, hideDotfiles bool) (ListingPreferences, error) {
	if value.Version == 0 {
		return DefaultListingPreferences(hideDotfiles), nil
	}
	if value.Version != ListingPreferencesVersion {
		return ListingPreferences{}, fmt.Errorf("不支持的列表偏好版本: %d", value.Version)
	}

	builtIns := make(map[string]struct{}, len(builtInPrefixes))
	for _, prefix := range builtInPrefixes {
		builtIns[prefix] = struct{}{}
	}

	seen := make(map[string]struct{}, len(value.PrefixRules))
	rules := make([]PrefixRule, 0, len(value.PrefixRules)+len(builtInPrefixes))
	customCount := 0
	for _, rule := range value.PrefixRules {
		if err := validatePrefix(rule.Prefix); err != nil {
			return ListingPreferences{}, err
		}
		if _, exists := seen[rule.Prefix]; exists {
			return ListingPreferences{}, fmt.Errorf("前缀规则重复: %q", rule.Prefix)
		}
		seen[rule.Prefix] = struct{}{}
		if _, builtIn := builtIns[rule.Prefix]; !builtIn {
			customCount++
			if customCount > MaxCustomPrefixRules {
				return ListingPreferences{}, fmt.Errorf("自定义前缀最多 %d 个", MaxCustomPrefixRules)
			}
		}
		rules = append(rules, rule)
	}

	defaults := DefaultListingPreferences(hideDotfiles)
	for _, rule := range defaults.PrefixRules {
		if _, exists := seen[rule.Prefix]; exists {
			continue
		}
		rule.Order = len(rules)
		rules = append(rules, rule)
	}

	sort.SliceStable(rules, func(left, right int) bool {
		if rules[left].Order == rules[right].Order {
			return false
		}
		return rules[left].Order < rules[right].Order
	})
	for index := range rules {
		rules[index].Order = index
	}

	return ListingPreferences{Version: ListingPreferencesVersion, PrefixRules: rules}, nil
}

// MatchPrefixRule selects the longest matching prefix, preserving rule order
// when lengths are equal.
func (p ListingPreferences) MatchPrefixRule(name string) (PrefixRule, bool) {
	var matched PrefixRule
	matchedRunes := -1
	for _, rule := range p.PrefixRules {
		if !strings.HasPrefix(name, rule.Prefix) {
			continue
		}
		length := utf8.RuneCountInString(rule.Prefix)
		if length > matchedRunes {
			matched = rule
			matchedRunes = length
		}
	}
	return matched, matchedRunes >= 0
}

func validatePrefix(prefix string) error {
	length := utf8.RuneCountInString(prefix)
	if length < 1 || length > MaxPrefixRunes {
		return fmt.Errorf("前缀长度必须为 1-%d 个可见字符", MaxPrefixRunes)
	}
	for _, character := range prefix {
		if character == '/' || character == '\\' {
			return fmt.Errorf("前缀不能包含路径分隔符")
		}
		if unicode.IsControl(character) || unicode.IsSpace(character) {
			return fmt.Errorf("前缀只能包含可见字符")
		}
	}
	return nil
}
