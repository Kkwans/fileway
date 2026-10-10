using System.Collections.Concurrent;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Playback.Contracts;
using Fileway.Playback.Native;

namespace Fileway.Playback;

public sealed class MpvPlaybackService : IPlaybackService
{
    private readonly IResourceLeaseProvider _leases;
    private readonly IPlaybackSurfaceFactory _surfaces;
    private readonly Lazy<MpvNative> _native;
    private readonly ConcurrentDictionary<Guid, MpvPlaybackSession> _sessions = new();
    private readonly SemaphoreSlim _lifecycle = new(1, 1);
    private readonly object _disposeGate = new();
    private Task? _disposeTask;
    private long _generation;
    private int _disposed;

    public MpvPlaybackService(IResourceLeaseProvider leases, IPlaybackSurfaceFactory surfaces, string runtimeDirectory)
    {
        ArgumentNullException.ThrowIfNull(leases);
        ArgumentNullException.ThrowIfNull(surfaces);
        ArgumentException.ThrowIfNullOrWhiteSpace(runtimeDirectory);
        _leases = leases;
        _surfaces = surfaces;
        _native = new Lazy<MpvNative>(() => new MpvNative(runtimeDirectory));
    }

    public async Task<IPlaybackSession> OpenAsync(PlaybackOpenRequest request, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
        await _lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
            var native = await Task.Run(() => _native.Value, cancellationToken).ConfigureAwait(false);
            var session = new MpvPlaybackSession(request, Interlocked.Increment(ref _generation), native, _leases, _surfaces,
                id => _sessions.TryRemove(id, out _));
            _sessions.TryAdd(session.Id, session);
            try
            {
                await session.StartAsync(cancellationToken).ConfigureAwait(false);
                return session;
            }
            catch
            {
                await session.DisposeAsync().ConfigureAwait(false);
                throw;
            }
        }
        finally { _lifecycle.Release(); }
    }

    public async Task CloseAccountAsync(AccountKey account, CancellationToken cancellationToken)
    {
        await _lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            foreach (var session in _sessions.Values.Where(session => session.Account == account))
                await session.StopAsync(cancellationToken).ConfigureAwait(false);
        }
        finally { _lifecycle.Release(); }
    }

    public ValueTask DisposeAsync()
    {
        lock (_disposeGate)
        {
            // Publish disposal intent before waiting: later opens must reject,
            // while the shared task still waits for the in-flight open to finish.
            Volatile.Write(ref _disposed, 1);
            _disposeTask ??= DisposeCoreAsync();
            return new ValueTask(_disposeTask);
        }
    }

    private async Task DisposeCoreAsync()
    {
        await _lifecycle.WaitAsync(CancellationToken.None).ConfigureAwait(false);
        try
        {
            foreach (var session in _sessions.Values) await session.DisposeAsync().ConfigureAwait(false);
            // Failed native teardown retains its module and surface rather than
            // unloading code which a native thread might still be executing.
            if (_sessions.IsEmpty && _native.IsValueCreated) _native.Value.Dispose();
        }
        finally { _lifecycle.Release(); }
    }
}
