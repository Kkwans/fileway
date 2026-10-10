using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
namespace Fileway.App;

public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        AppWindow.SetIcon("Assets/AppIcon.ico");
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1240, 820));
        RootFrame.Navigate(typeof(MainPage));
        Closed += (_, _) => ((MainPage)RootFrame.Content).Shutdown();
    }
    public void SetServices(IConnectionService connection, IFileRepository files, IImageContentService images, IPlaybackService playback)
        => ((MainPage)RootFrame.Content).SetServices(connection, files, images, playback);
    public void ShowStartupError(string message) => ((MainPage)RootFrame.Content).ShowStartupError(message);
}
