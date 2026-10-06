// Package events provides a small in-process event broker used by the task
// center SSE endpoint. REST remains the durable source of truth; the broker
// only carries short-lived change notifications.
package events

import (
	"encoding/json"
	"sync"
)

const defaultHistorySize = 256

type Event struct {
	ID       uint64          `json:"id"`
	Type     string          `json:"type"`
	Data     json.RawMessage `json:"data,omitempty"`
	Audience uint            `json:"-"`
}

type subscriber struct {
	channel       chan Event
	audience      uint
	resyncPending bool
}

type Broker struct {
	mu          sync.Mutex
	nextID      uint64
	maxHistory  int
	history     []Event
	subscribers map[chan Event]subscriber
}

func New(maxHistory int) *Broker {
	if maxHistory < 1 {
		maxHistory = defaultHistorySize
	}
	return &Broker{maxHistory: maxHistory, subscribers: make(map[chan Event]subscriber)}
}

func (broker *Broker) Publish(eventType string, value interface{}) Event {
	return broker.publish(0, eventType, value)
}

// PublishForUser emits a user-scoped notification. The event payload is kept
// in the in-process ring for replay, but subscribers for other users never
// receive it. The audience is deliberately omitted from JSON because it is a
// routing detail, not a client-facing field.
func (broker *Broker) PublishForUser(userID uint, eventType string, value interface{}) Event {
	return broker.publish(userID, eventType, value)
}

func (broker *Broker) publish(audience uint, eventType string, value interface{}) Event {
	data, _ := json.Marshal(value)
	broker.mu.Lock()
	defer broker.mu.Unlock()
	broker.nextID++
	event := Event{ID: broker.nextID, Type: eventType, Data: data, Audience: audience}
	broker.history = append(broker.history, event)
	if len(broker.history) > broker.maxHistory {
		broker.history = broker.history[len(broker.history)-broker.maxHistory:]
	}
	for channel, subscriber := range broker.subscribers {
		if event.Audience != 0 && event.Audience != subscriber.audience {
			continue
		}
		select {
		case subscriber.channel <- event:
		default:
			// Do not silently discard a slow subscriber's updates. Replace one
			// buffered event with an explicit resync marker; the HTTP handler
			// closes that stream so the client reconnects from a fresh snapshot.
			if subscriber.resyncPending {
				continue
			}
			select {
			case <-subscriber.channel:
			default:
			}
			select {
			case subscriber.channel <- Event{
				ID:       event.ID,
				Type:     "resync.required",
				Data:     json.RawMessage(`{"reason":"subscriber-overflow"}`),
				Audience: subscriber.audience,
			}:
				subscriber.resyncPending = true
				broker.subscribers[channel] = subscriber
			default:
				// The channel can only be full if another writer won the slot;
				// leave the pending marker unset and try again on the next event.
			}
		}
	}
	return event
}

// Subscribe returns events newer than lastID, a live channel, a cancellation
// function, and whether the requested cursor fell outside the ring buffer.
func (broker *Broker) Subscribe(lastID uint64) ([]Event, <-chan Event, func(), bool) {
	return broker.SubscribeForUser(lastID, 0)
}

// SubscribeForUser replays global events and notifications addressed to the
// supplied user. audience=0 retains the original all-events behaviour for
// internal callers and tests.
func (broker *Broker) SubscribeForUser(lastID uint64, audience uint) ([]Event, <-chan Event, func(), bool) {
	broker.mu.Lock()
	defer broker.mu.Unlock()
	gap := false
	if len(broker.history) > 0 && lastID > 0 && (lastID < broker.history[0].ID-1 || lastID > broker.nextID) {
		gap = true
	}
	var replay []Event
	for _, event := range broker.history {
		if event.ID > lastID && (event.Audience == 0 || audience == 0 || event.Audience == audience) {
			replay = append(replay, event)
		}
	}
	channel := make(chan Event, 32)
	broker.subscribers[channel] = subscriber{channel: channel, audience: audience}
	cancel := func() {
		broker.mu.Lock()
		if _, exists := broker.subscribers[channel]; exists {
			delete(broker.subscribers, channel)
			close(channel)
		}
		broker.mu.Unlock()
	}
	return replay, channel, cancel, gap
}

var Default = New(defaultHistorySize)
