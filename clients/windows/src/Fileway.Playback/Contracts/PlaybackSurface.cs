using Fileway.Core.Contracts;

namespace Fileway.Playback.Contracts;

public interface IPlaybackSurface : IAsyncDisposable
{
    nint ParentHwnd { get; }
    ValueTask DetachAsync(CancellationToken cancellationToken);
}

public interface IPlaybackSurfaceFactory
{
    Task<IPlaybackSurface> CreateAsync(IPlaybackSession session, CancellationToken cancellationToken);
}
