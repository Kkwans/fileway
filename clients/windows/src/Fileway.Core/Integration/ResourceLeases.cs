using Fileway.Core.Contracts;

namespace Fileway.Core.Integration;

/// <summary>Infrastructure-only capability. Its address must never enter UI snapshots, history or logs.</summary>
public interface IResourceLease : IAsyncDisposable
{
    RemoteResourceRef Resource { get; }
    Uri Address { get; }
}

public interface IResourceLeaseProvider
{
    Task<IResourceLease> AcquireAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
}
