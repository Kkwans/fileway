package bridge

import (
	"context"
	"errors"

	"github.com/Kkwans/nas-file-browser-client/core/transport"
)

func commandControl(ctx context.Context, broker *transport.Broker, command Command) (any, error) {
	switch command.Op {
	case "command_start":
		id, err := broker.StartCommand(ctx, command.Session, command.Path, command.WirePath, command.RawCommand)
		if err == nil && ctx.Err() != nil {
			_ = broker.CancelCommand(command.Session, id)
			return nil, ctx.Err()
		}
		return id, err
	case "command_poll":
		return broker.PollCommand(command.Session, command.CommandHandle)
	case "command_cancel":
		return nil, broker.CancelCommand(command.Session, command.CommandHandle)
	default:
		return nil, errors.New("unknown command output operation")
	}
}
