using System.Runtime.InteropServices;
using Fileway.Core.Contracts;
using Fileway.Playback.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;

namespace Fileway.App.MediaSurfaces;

internal sealed class PlayerWindow : Window, IPlaybackSurface
{
    private readonly IPlaybackSession _session;
    private readonly CancellationTokenSource _watchCancellation = new();
    private readonly Grid _root = new() { Background = new SolidColorBrush(Microsoft.UI.Colors.Black), RowSpacing = 0 };
    private readonly Grid _video = new() { MinHeight = 100 };
    private readonly TextBlock _status = new() { Text = "正在准备播放…", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(20, 12, 20, 12) };
    private readonly Button _pause = new() { Content = "暂停", MinWidth = 76 };
    private readonly Slider _seek = new() { Minimum = 0, Maximum = 1, IsEnabled = false, MinWidth = 160, HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly TextBlock _time = new() { Text = "00:00 / --:--", VerticalAlignment = VerticalAlignment.Center, MinWidth = 114 };
    private bool _paused;
    private bool _applyingSnapshot;
    private bool _userSeeking;
    private bool _closing;
    private bool _disposed;
    private bool _detached;
    private nint _child;

    public PlayerWindow(IPlaybackSession session)
    {
        _session = session;
        Title = session.Snapshot.DisplayName + " · Fileway 播放器";
        SystemBackdrop = new MicaBackdrop();
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1080, 760));
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition());
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var heading = new Grid { Background = (Brush)Application.Current.Resources["LayerFillColorDefaultBrush"] };
        heading.Children.Add(_status); _root.Children.Add(heading);
        Grid.SetRow(_video, 1); _root.Children.Add(_video);
        var controls = new Grid { Padding = new Thickness(20, 14, 20, 14), ColumnSpacing = 16, Background = (Brush)Application.Current.Resources["LayerFillColorDefaultBrush"] };
        controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); controls.ColumnDefinitions.Add(new ColumnDefinition()); controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); controls.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(130) });
        controls.Children.Add(_pause); Grid.SetColumn(_seek, 1); controls.Children.Add(_seek); Grid.SetColumn(_time, 2); controls.Children.Add(_time);
        var volume = new Slider { Minimum = 0, Maximum = 100, Value = 100, Header = "音量", VerticalAlignment = VerticalAlignment.Center };
        Grid.SetColumn(volume, 3); controls.Children.Add(volume); Grid.SetRow(controls, 2); _root.Children.Add(controls);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(_seek, "播放进度");
        _pause.Click += async (_, _) => await RunCommandAsync(() => _session.SetPausedAsync(!_paused, _watchCancellation.Token));
        volume.ValueChanged += async (_, args) => { if (!_applyingSnapshot && !_detached) await RunCommandAsync(() => _session.SetVolumeAsync(args.NewValue, _watchCancellation.Token)); };
        _seek.AddHandler(UIElement.PointerPressedEvent, new Microsoft.UI.Xaml.Input.PointerEventHandler((_, _) => _userSeeking = true), true);
        _seek.AddHandler(UIElement.PointerReleasedEvent, new Microsoft.UI.Xaml.Input.PointerEventHandler(async (_, _) => { if (!_userSeeking) return; _userSeeking = false; await SeekAsync(); }), true);
        _seek.KeyUp += async (_, args) => { if (args.Key is Windows.System.VirtualKey.Left or Windows.System.VirtualKey.Right or Windows.System.VirtualKey.Home or Windows.System.VirtualKey.End) await SeekAsync(); };
        Content = _root;
        var parent = WinRT.Interop.WindowNative.GetWindowHandle(this);
        _child = NativeVideoWindow.CreateWindowExW(0, "STATIC", "Fileway video", 0x50000004 | 0x04000000 | 0x02000000, 0, 0, 1, 1, parent, 0, 0, 0);
        if (_child == 0) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "无法创建原生视频容器。");
        _video.SizeChanged += (_, _) => UpdateBounds();
        _root.Loaded += (_, _) => { if (_root.XamlRoot is not null) _root.XamlRoot.Changed += Root_Changed; UpdateBounds(); };
        AppWindow.Changed += (_, _) => UpdateBounds();
        AppWindow.Closing += (sender, args) => { if (_disposed) return; args.Cancel = true; if (!_closing) { _closing = true; _ = CloseSessionAsync(); } };
        Closed += (_, _) => { if (!_disposed) _watchCancellation.Cancel(); };
        _ = WatchAsync();
    }

    public nint ParentHwnd => _child;
    private void Root_Changed(Microsoft.UI.Xaml.XamlRoot sender, Microsoft.UI.Xaml.XamlRootChangedEventArgs args) => UpdateBounds();
    private void UpdateBounds()
    {
        if (_child == 0 || _root.XamlRoot is null) return;
        var point = _video.TransformToVisual(_root).TransformPoint(new Point(0, 0));
        var scale = _root.XamlRoot.RasterizationScale;
        NativeVideoWindow.MoveWindow(_child, (int)Math.Round(point.X * scale), (int)Math.Round(point.Y * scale), Math.Max(1, (int)Math.Round(_video.ActualWidth * scale)), Math.Max(1, (int)Math.Round(_video.ActualHeight * scale)), true);
    }

    private async Task WatchAsync()
    {
        try
        {
            await foreach (var snapshot in _session.WatchAsync(_watchCancellation.Token).ConfigureAwait(false))
            {
                DispatcherQueue.TryEnqueue(() => ApplySnapshot(snapshot));
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception exception) { DispatcherQueue.TryEnqueue(() => { if (!_detached) _status.Text = MainPage.ErrorMessage(exception); }); }
    }
    private void ApplySnapshot(PlaybackSnapshot snapshot)
    {
        if (_detached) return;
        _applyingSnapshot = true;
        _paused = snapshot.Status == PlaybackStatus.Paused;
        _pause.Content = _paused ? "播放" : "暂停";
        _pause.IsEnabled = snapshot.Status is PlaybackStatus.Playing or PlaybackStatus.Paused or PlaybackStatus.Buffering;
        _status.Text = snapshot.Error?.Message ?? snapshot.Status switch
        {
            PlaybackStatus.Preparing => "正在准备播放…", PlaybackStatus.Buffering => "正在缓冲…", PlaybackStatus.Playing => snapshot.DisplayName,
            PlaybackStatus.Paused => "已暂停 · " + snapshot.DisplayName, PlaybackStatus.Seeking => "正在定位…", PlaybackStatus.Ended => "播放结束", PlaybackStatus.Failed => "播放失败", _ => "正在关闭播放…",
        };
        var position = snapshot.ConfirmedPosition ?? snapshot.Position;
        _time.Text = FormatTime(position) + " / " + (snapshot.Duration is { } duration ? FormatTime(duration) : "--:--");
        _seek.IsEnabled = snapshot.Seekable && snapshot.Duration is { TotalSeconds: > 0 };
        if (!_userSeeking) { _seek.Maximum = Math.Max(1, snapshot.Duration?.TotalSeconds ?? 1); _seek.Value = Math.Clamp(position.TotalSeconds, 0, _seek.Maximum); }
        _applyingSnapshot = false;
    }
    private Task SeekAsync() => _seek.IsEnabled && !_detached ? RunCommandAsync(() => _session.SeekAsync(TimeSpan.FromSeconds(_seek.Value), _watchCancellation.Token)) : Task.CompletedTask;
    private async Task RunCommandAsync(Func<Task> command)
    {
        try { await command(); }
        catch (OperationCanceledException) { }
        catch (Exception exception) { if (!_detached) _status.Text = MainPage.ErrorMessage(exception); }
    }
    private async Task CloseSessionAsync()
    {
        try
        {
            try { await _session.StopAsync(CancellationToken.None); }
            catch (Exception) { /* Disposal still owns native renderer teardown. */ }
            await _session.DisposeAsync();
        }
        catch (Exception exception) { _closing = false; _status.Text = MainPage.ErrorMessage(exception); }
    }
    public ValueTask DetachAsync(CancellationToken cancellationToken) => OnUiAsync(() => { if (_disposed) return; _detached = true; _watchCancellation.Cancel(); _pause.IsEnabled = false; _seek.IsEnabled = false; });
    public ValueTask DisposeAsync() => OnUiAsync(() =>
    {
        if (_disposed) return;
        _disposed = true; _detached = true; _watchCancellation.Cancel();
        if (_root.XamlRoot is not null) _root.XamlRoot.Changed -= Root_Changed;
        if (_child != 0) { NativeVideoWindow.DestroyWindow(_child); _child = 0; }
        _watchCancellation.Dispose();
        Close();
    });
    private ValueTask OnUiAsync(Action action)
    {
        if (DispatcherQueue.HasThreadAccess) { action(); return ValueTask.CompletedTask; }
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!DispatcherQueue.TryEnqueue(() => { try { action(); completion.TrySetResult(); } catch (Exception exception) { completion.TrySetException(exception); } })) completion.TrySetException(new InvalidOperationException("播放窗口调度器已关闭。"));
        return new ValueTask(completion.Task);
    }
    private static string FormatTime(TimeSpan time) => time.TotalHours >= 1 ? time.ToString(@"h\:mm\:ss", System.Globalization.CultureInfo.InvariantCulture) : time.ToString(@"mm\:ss", System.Globalization.CultureInfo.InvariantCulture);
}

internal static class NativeVideoWindow
{
    [DllImport("user32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
    internal static extern nint CreateWindowExW(uint exStyle, string className, string windowName, uint style, int x, int y, int width, int height, nint parent, nint menu, nint instance, nint parameter);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool MoveWindow(nint window, int x, int y, int width, int height, [MarshalAs(UnmanagedType.Bool)] bool repaint);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool DestroyWindow(nint window);
}
