namespace Fileway.Core.Contracts;

public interface IImageContent : IAsyncDisposable
{
    RemoteResourceRef Resource { get; }
    Stream Content { get; }
    string? ContentType { get; }
    long Length { get; }
}

public interface IImageContentService
{
    Task<IImageContent> OpenAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
}
