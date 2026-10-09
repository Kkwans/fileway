package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strconv"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestSubmittedRulesUseCompleteGoSyntaxAndRejectInvalidExpressions(t *testing.T) {
	for _, raw := range []string{"", "(?P<name>docs)/[[:alpha:]]+", "(?i)^/文档/", "\\p{Han}+", "(?s:.*)"} {
		if err := validateSubmittedRules([]rules.Rule{{Regex: true, Regexp: &rules.Regexp{Raw: raw}}}); err != nil {
			t.Fatalf("valid Go expression rejected: %q %v", raw, err)
		}
	}
	for _, value := range []rules.Rule{{Regex: true}, {Regex: true, Regexp: &rules.Regexp{Raw: "["}}, {Regex: true, Regexp: &rules.Regexp{Raw: "(?=lookahead)"}}} {
		if err := validateSubmittedRules([]rules.Rule{value}); err == nil {
			t.Fatalf("invalid Go expression accepted: %#v", value)
		}
	}
	if err := validateSubmittedRules([]rules.Rule{{Path: "/allowed", Regex: false}}); err != nil {
		t.Fatal("ordinary path rule must not require a regexp object", err)
	}
}

func TestUserRulesRejectBeforePersistAndDoNotValidateUnsubmittedFields(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "admin", Perm: users.Permissions{Admin: true}})
	admin := firstTrashHTTPUser(h)
	id := strconv.FormatUint(uint64(admin.ID), 10)
	for _, which := range [][]string{{"rules"}, {"dateFormat"}} {
		body := map[string]interface{}{"what": "user", "which": which, "data": map[string]interface{}{
			"id": admin.ID, "dateFormat": true, "rules": []map[string]interface{}{{"regex": true, "regexp": map[string]string{"raw": "["}}},
		}}
		raw, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		response := h.request(t, admin.ID, userPutHandler, http.MethodPut, "/users/"+id, bytes.NewReader(raw), map[string]string{"id": id})
		expected := http.StatusBadRequest
		if which[0] == "dateFormat" {
			expected = http.StatusOK
		}
		if response.Code != expected {
			t.Fatalf("which=%v status=%d body=%s", which, response.Code, response.Body.String())
		}
		stored, err := h.storage.Users.Get("", admin.ID)
		if err != nil || len(stored.Rules) != 0 {
			t.Fatalf("invalid rule was persisted: %#v %v", stored, err)
		}
	}
	response := h.request(t, admin.ID, userPostHandler, http.MethodPost, "/users", bytes.NewBufferString(`{"what":"user","which":[],"data":{"username":"new-owned-user","password":"owned-password","rules":[{"regex":true,"regexp":{"raw":"["}}]}}`), nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("create accepted invalid rule: %d %s", response.Code, response.Body.String())
	}
	rows, err := h.storage.Users.Gets("")
	if err != nil || len(rows) != 1 {
		t.Fatalf("invalid create changed users: %#v %v", rows, err)
	}
}

func TestGlobalRulesRejectBeforeAnySettingsAreWritten(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "admin", Perm: users.Permissions{Admin: true}})
	admin := firstTrashHTTPUser(h)
	response := h.request(t, admin.ID, settingsGetHandler, http.MethodGet, "/settings", nil, nil)
	var original map[string]interface{}
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &original) != nil {
		t.Fatal("settings fixture read failed")
	}
	original["signup"] = true
	original["rules"] = []map[string]interface{}{{"regex": true, "regexp": nil}}
	body, err := json.Marshal(original)
	if err != nil {
		t.Fatal(err)
	}
	response = h.request(t, admin.ID, settingsPutHandler, http.MethodPut, "/settings", bytes.NewReader(body), nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("settings accepted nil regexp: %d %s", response.Code, response.Body.String())
	}
	stored, err := h.storage.Settings.Get()
	if err != nil || stored.Signup || len(stored.Rules) != 0 {
		t.Fatalf("invalid global rules changed settings: %#v %v", stored, err)
	}
}

func TestRulesValidationDoesNotBypassMemberPermissionsOrJsonReauthentication(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "admin", Perm: users.Permissions{Admin: true}}, users.User{Username: "member"})
	admin := trashHTTPUserByName(t, h, "admin")
	member := trashHTTPUserByName(t, h, "member")
	memberID := strconv.FormatUint(uint64(member.ID), 10)
	badRules := map[string]interface{}{"what": "user", "which": []string{"rules"}, "data": map[string]interface{}{
		"id": member.ID, "rules": []map[string]interface{}{{"regex": true, "regexp": map[string]string{"raw": "["}}},
	}}
	body, err := json.Marshal(badRules)
	if err != nil {
		t.Fatal(err)
	}
	response := h.request(t, member.ID, userPutHandler, http.MethodPut, "/users/"+memberID, bytes.NewReader(body), map[string]string{"id": memberID})
	if response.Code != http.StatusForbidden {
		t.Fatalf("rule validation changed member authorization: %d %s", response.Code, response.Body.String())
	}
	policy, err := h.storage.Settings.Get()
	if err != nil {
		t.Fatal(err)
	}
	policy.AuthMethod = "json"
	if err := h.storage.Settings.Save(policy); err != nil {
		t.Fatal(err)
	}
	response = h.request(t, admin.ID, userPostHandler, http.MethodPost, "/users", bytes.NewBufferString(`{"what":"user","which":[],"current_password":"owned-wrong-operator-password","data":{"username":"new-owned-user","password":"owned-new-password","rules":[]}}`), nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("JSON reauthentication was bypassed: %d %s", response.Code, response.Body.String())
	}
	rows, err := h.storage.Users.Gets("")
	if err != nil || len(rows) != 2 {
		t.Fatalf("failed reauthentication changed users: %#v %v", rows, err)
	}
}
