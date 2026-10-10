using System.Text.Json.Serialization;

namespace Fileway.Core.Contracts;

public abstract record PlaybackInput;

public sealed record RemotePlaybackInput(RemoteResourceRef Resource) : PlaybackInput
{
    public override string ToString() => "RemotePlaybackInput (source redacted)";
}

public sealed record LocalPlaybackInput(
    [property: JsonIgnore] string FullPath,
    string DisplayName,
    RemoteResourceRef? Origin = null) : PlaybackInput
{
    public override string ToString() => "LocalPlaybackInput (source redacted)";
}

public sealed record PlaybackOpenRequest(PlaybackInput Input, TimeSpan? ResumePosition = null);

public enum PlaybackStatus
{
    Preparing,
    Buffering,
    Playing,
    Paused,
    Seeking,
    Ended,
    Stopped,
    Failed,
    Closing,
}

public enum MediaTrackKind
{
    Video,
    Audio,
    Subtitle,
}

public sealed record MediaTrack(long Id, MediaTrackKind Kind, string? Title, string? Language, string? Codec, bool Selected, bool External);

public sealed record SubtitleCapabilities(bool CanChangeFont, bool CanScale, bool CanPosition, bool CanDelay, bool PreservesScriptStyle);

public sealed record SubtitlePreferences(
    double Scale = 1.0,
    double VerticalPositionPercent = 95.0,
    string? FontFamily = null,
    TimeSpan Delay = default,
    bool OverrideScriptStyle = false);

/// <summary>Each value is observed or null. Input HDR metadata never implies HDR display output.</summary>
public sealed record PlaybackDiagnostics(
    string? VideoCodec = null,
    string? AudioCodec = null,
    string? Decoder = null,
    string? SourceDynamicRange = null,
    bool? DisplayHdrSupported = null,
    bool? DisplayHdrEnabled = null,
    string? SwapChainFormat = null,
    string? SwapChainColorSpace = null,
    string? ActualOutputMode = null);

public sealed record PlaybackSnapshot(
    Guid SessionId,
    long Generation,
    string DisplayName,
    PlaybackStatus Status,
    TimeSpan Position,
    TimeSpan? ConfirmedPosition,
    TimeSpan? Duration,
    bool Seekable,
    IReadOnlyList<MediaTrack> Tracks,
    SubtitleCapabilities SubtitleCapabilities,
    PlaybackDiagnostics Diagnostics,
    ErrorInfo? Error = null)
{
    public override string ToString() => "PlaybackSnapshot (private media details redacted)";
}

public interface IPlaybackSession : IAsyncDisposable
{
    Guid Id { get; }
    PlaybackSnapshot Snapshot { get; }
    IAsyncEnumerable<PlaybackSnapshot> WatchAsync(CancellationToken cancellationToken);
    Task SetPausedAsync(bool paused, CancellationToken cancellationToken);
    Task SeekAsync(TimeSpan position, CancellationToken cancellationToken);
    Task SetVolumeAsync(double volume, CancellationToken cancellationToken);
    Task SelectTrackAsync(MediaTrackKind kind, long? trackId, CancellationToken cancellationToken);
    Task SetSubtitlesAsync(SubtitlePreferences preferences, CancellationToken cancellationToken);
    Task AddSubtitleAsync(PlaybackInput subtitle, CancellationToken cancellationToken);
    Task StopAsync(CancellationToken cancellationToken);
}

public interface IPlaybackService : IAsyncDisposable
{
    Task<IPlaybackSession> OpenAsync(PlaybackOpenRequest request, CancellationToken cancellationToken);
    Task CloseAccountAsync(AccountKey account, CancellationToken cancellationToken);
}
