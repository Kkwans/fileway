package fbhttp

import (
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/events"
)

var taskCenterEventsHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		return http.StatusInternalServerError, fmt.Errorf("当前响应不支持实时事件")
	}
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	lastID, _ := strconv.ParseUint(r.Header.Get("Last-Event-ID"), 10, 64)
	if lastID == 0 {
		lastID, _ = strconv.ParseUint(r.URL.Query().Get("lastEventId"), 10, 64)
	}
	replay, channel, cancel, gap := events.Default.SubscribeForUser(lastID, d.user.ID)
	defer cancel()
	write := func(event events.Event) error {
		if _, err := fmt.Fprintf(w, "id: %d\nevent: %s\ndata: %s\n\n", event.ID, event.Type, event.Data); err != nil {
			return err
		}
		flusher.Flush()
		return nil
	}
	if gap {
		if err := write(events.Event{ID: lastID, Type: "resync.required", Data: []byte(`{"reason":"event-gap"}`)}); err != nil {
			return 0, nil
		}
	}
	for _, event := range replay {
		if err := write(event); err != nil {
			return 0, nil
		}
	}
	keepAlive := time.NewTicker(20 * time.Second)
	defer keepAlive.Stop()
	for {
		select {
		case <-r.Context().Done():
			return 0, nil
		case event, open := <-channel:
			if !open {
				return 0, nil
			}
			if err := write(event); err != nil {
				return 0, nil
			}
			if event.Type == "resync.required" {
				// A resync marker means the bounded broker buffer could no
				// longer preserve ordering. Let EventSource reconnect with this
				// cursor after the client has loaded a REST snapshot.
				return 0, nil
			}
		case <-keepAlive.C:
			if _, err := fmt.Fprint(w, ": keep-alive\n\n"); err != nil {
				return 0, nil
			}
			flusher.Flush()
		}
	}
})
