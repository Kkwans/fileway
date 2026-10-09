package fbhttp

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/rules"
)

// Validate the same Go regexp syntax used by rules.Rule.Matches before a
// submitted rule can be persisted. Non-regex rows need no regexp object.
func validateSubmittedRules(values []rules.Rule) error {
	for index, rule := range values {
		if !rule.Regex {
			continue
		}
		if rule.Regexp == nil {
			return fmt.Errorf("第 %d 条路径规则缺少正则表达式", index+1)
		}
		if _, err := regexp.Compile(rule.Regexp.Raw); err != nil {
			return fmt.Errorf("第 %d 条路径规则的 Go 正则表达式无效", index+1)
		}
	}
	return nil
}

func submitsUserRules(which []string) bool {
	if len(which) == 0 || len(which) == 1 && which[0] == "all" {
		return true
	}
	for _, field := range which {
		if strings.EqualFold(field, "rules") {
			return true
		}
	}
	return false
}
