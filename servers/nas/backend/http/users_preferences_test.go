package fbhttp

import (
	"bytes"
	"net/http"
	"strconv"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestUserListingPreferencesRejectInvalidAndPersistValidRules(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"})
	owner := firstTrashHTTPUser(h)
	ownerID := strconv.FormatUint(uint64(owner.ID), 10)

	invalid := bytes.NewBufferString(`{
		"what":"user",
		"which":["listingPreferences"],
		"data":{"id":` + ownerID + `,"listingPreferences":{"version":1,"prefixRules":[{"prefix":"a/b","visible":true,"expanded":true,"order":0}]}}
	}`)
	response := h.request(t, owner.ID, userPutHandler, http.MethodPut, "/users/owner", invalid, map[string]string{"id": ownerID})
	if response.Code != http.StatusBadRequest {
		t.Fatalf("invalid preference status = %d body=%s", response.Code, response.Body.String())
	}

	valid := bytes.NewBufferString(`{
		"what":"user",
		"which":["listingPreferences"],
		"data":{"id":` + ownerID + `,"listingPreferences":{"version":1,"prefixRules":[{"prefix":"@@","visible":false,"expanded":false,"order":0}]}}
	}`)
	response = h.request(t, owner.ID, userPutHandler, http.MethodPut, "/users/owner", valid, map[string]string{"id": ownerID})
	if response.Code != http.StatusOK {
		t.Fatalf("valid preference status = %d body=%s", response.Code, response.Body.String())
	}

	stored, err := h.storage.Users.Get("/", owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	matched, ok := stored.ListingPreferences.MatchPrefixRule("@@cache")
	if !ok || matched.Prefix != "@@" || matched.Visible || matched.Expanded {
		t.Fatalf("stored longest rule = %#v, %t", matched, ok)
	}
}
