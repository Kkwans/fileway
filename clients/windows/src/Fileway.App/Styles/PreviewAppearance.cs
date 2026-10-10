using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.UI;
using Windows.UI.ViewManagement;

namespace Fileway.App.Styles;

/// <summary>Small, transient comparison tokens. A selected preview is never a saved product preference.</summary>
internal static class PreviewAppearance
{
    internal static void Apply(Grid shell, Grid sidebar, Grid workspace, FrameworkElement connectionPanel, int direction)
    {
        var systemBackground = (Brush)Application.Current.Resources["ApplicationPageBackgroundThemeBrush"];
        var systemLayer = (Brush)Application.Current.Resources["LayerFillColorDefaultBrush"];
        if (direction == 0 || new AccessibilitySettings().HighContrast)
        {
            shell.Background = systemBackground; sidebar.Background = systemLayer; workspace.Background = null;
            if (connectionPanel is ScrollViewer scroll) scroll.Background = systemBackground;
            return;
        }
        var dark = shell.ActualTheme == ElementTheme.Dark;
        var background = direction == 1 ? dark ? 0x1E201Bu : 0xF5F1E8u : dark ? 0x161C23u : 0xEEF1F4u;
        var layer = direction == 1 ? dark ? 0x282B24u : 0xFFFCF6u : dark ? 0x202A34u : 0xFFFFFFu;
        shell.Background = Brush(background); workspace.Background = Brush(background); sidebar.Background = Brush(layer);
        if (connectionPanel is ScrollViewer panel) panel.Background = Brush(background);
    }
    private static SolidColorBrush Brush(uint rgb) => new(Color.FromArgb(255, (byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb));
}
