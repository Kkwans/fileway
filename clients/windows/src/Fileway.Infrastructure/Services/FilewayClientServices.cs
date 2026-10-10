using System.Globalization;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

/// <summary>Typed, account-bound application services over the supervised Go Host.</summary>
public sealed class FilewayClientServices : IConnectionService, IFileRepository, IImageContentService,
    IResourceLeaseProvider, IAsyncDisposable
{
    private const long MaximumImageBytes = 64L * 1024 * 1024;
    private readonly IHostControlClient _host;
    private readonly object _gate = new();
    private readonly SemaphoreSlim _connections = new(1, 1);
    private readonly Dictionary<AccountKey, SessionState> _sessions = [];
    private readonly HttpClient _images = new(new SocketsHttpHandler { UseProxy = false, AllowAutoRedirect = false });
    private bool _disposed;

    private sealed class SessionState
    {
        internal SessionState(string coreSession, ConnectionSession session)
        {
            CoreSession = coreSession;
            Session = session;
            LifetimeToken = Lifetime.Token;
        }
        internal string CoreSession { get; }
        internal ConnectionSession Session { get; }
        internal CancellationTokenSource Lifetime { get; } = new();
        internal CancellationToken LifetimeToken { get; }
        internal HashSet<ResourceLease> Leases { get; } = [];
        internal bool Active { get; set; } = true;
    }

    public FilewayClientServices(IHostControlClient host) => _host = host ?? throw new ArgumentNullException(nameof(host));

    public async Task<ConnectionSession> ConnectAsync(ConnectionProfile profile, LoginInput login, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(profile);
        ArgumentNullException.ThrowIfNull(login);
        ValidateProfile(profile, login);
        await _connections.WaitAsync(cancellationToken).ConfigureAwait(false);
        string? coreSession = null;
        try
        {
            lock (_gate) ObjectDisposedException.ThrowIf(_disposed, this);
            await RetireAllAsync().ConfigureAwait(false);
            var opened = await CallAsync("open", new { baseUrl = profile.BaseAddress.AbsoluteUri, network = "direct", token = "" }, null, cancellationToken).ConfigureAwait(false);
            coreSession = RequiredText(opened);
            var authenticated = await CallAsync("login", new
            {
                username = login.Mode == AuthenticationMode.Password ? login.Username : "",
                password = login.Mode == AuthenticationMode.Password ? login.Password : "",
            }, coreSession, cancellationToken).ConfigureAwait(false);
            string token = HttpBody(authenticated);
            string userId = ReadTokenUserId(token);
            using var accountBody = ParseBody(await RequestAsync(coreSession, "/api/users/" + userId, cancellationToken).ConfigureAwait(false));
            var user = accountBody.RootElement;
            if (ReadUserId(user) != userId) throw InvalidResponse();
            using var capabilityBody = ParseBody(await RequestAsync(coreSession, "/api/client-capabilities", cancellationToken).ConfigureAwait(false));
            var capabilities = capabilityBody.RootElement;
            string authMethod = RequiredText(capabilities.GetProperty("authMethod"));
            if (authMethod != (login.Mode == AuthenticationMode.NoAuthentication ? "noauth" : "json"))
                throw Safe(ErrorCode.CapabilityMissing, "服务器认证方式与所选登录方式不一致，请确认后重新连接。");
            var permissions = user.GetProperty("perm");
            var session = new ConnectionSession(Guid.NewGuid(), new AccountKey(profile.Id, profile.SourceRevision, userId),
                OptionalText(user, "username") ?? profile.DisplayName,
                new PermissionSet(Flag(permissions, "download"), Flag(permissions, "create"), Flag(permissions, "rename"),
                    Flag(permissions, "modify"), Flag(permissions, "delete"), Flag(permissions, "execute")),
                new ServerCapabilities(Flag(capabilities, "resourcePaginationV1"), Flag(capabilities, "conditionalDownloadV1"),
                    Flag(capabilities, "searchWirePathV1"), Flag(capabilities, "resourceWireOperations")));
            cancellationToken.ThrowIfCancellationRequested();
            lock (_gate)
            {
                ObjectDisposedException.ThrowIf(_disposed, this);
                _sessions.Add(session.Account, new SessionState(coreSession, session));
            }
            coreSession = null;
            return session;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        {
            throw InvalidResponse();
        }
        finally
        {
            if (coreSession is not null) await CloseCoreAsync(coreSession).ConfigureAwait(false);
            _connections.Release();
        }
    }

    public Task DisconnectAsync(AccountKey account, CancellationToken cancellationToken) => RetireAccountAsync(account, cancellationToken);
    public Task LogoutAsync(AccountKey account, CancellationToken cancellationToken) => RetireAccountAsync(account, cancellationToken);

    public Task<RemoteResourceRef> GetRootAsync(ConnectionSession session, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(session);
        cancellationToken.ThrowIfCancellationRequested();
        var state = GetState(session.Account);
        if (state.Session.Id != session.Id) throw Safe(ErrorCode.SourceChanged, "此账户会话已更换，请重新打开目录。");
        return Task.FromResult(new RemoteResourceRef(session.Account, "/", "根目录", "/"));
    }

    public async Task<DirectoryPage> ListAsync(RemoteResourceRef directory, DirectoryQuery query, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(directory);
        ArgumentNullException.ThrowIfNull(query);
        if (query.Cursor is not null) throw Safe(ErrorCode.CapabilityMissing, "首预览尚不支持分页目录。");
        var state = GetState(directory.Account);
        using var document = await ResourceDocumentAsync(state, directory, false, cancellationToken).ConfigureAwait(false);
        try
        {
            var root = document.RootElement;
            var parsedDirectory = ParseFile(root, directory.Account);
            if (!parsedDirectory.IsDirectory || parsedDirectory.Resource.WirePath != directory.WirePath) throw InvalidResponse();
            var items = root.GetProperty("items").EnumerateArray().Select(item => ParseFile(item, directory.Account)).ToArray();
            Array.Sort(items, (left, right) => CompareFiles(left, right, query));
            EnsureActive(state);
            return new DirectoryPage(parsedDirectory.Resource, items, null, null);
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    public async Task<RemoteFile> GetAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        var state = GetState(resource.Account);
        using var document = await ResourceDocumentAsync(state, resource, true, cancellationToken).ConfigureAwait(false);
        try
        {
            var file = ParseFile(document.RootElement, resource.Account);
            if (file.Resource.WirePath != resource.WirePath) throw InvalidResponse();
            EnsureActive(state);
            return file;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    public Task<FileOperationResult> CreateDirectoryAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken) => UnsupportedWrite(cancellationToken);
    public Task<FileOperationResult> RenameAsync(RemoteResourceRef resource, string name, CancellationToken cancellationToken) => UnsupportedWrite(cancellationToken);
    public Task<FileOperationResult> TrashAsync(IReadOnlyList<RemoteResourceRef> resources, CancellationToken cancellationToken) => UnsupportedWrite(cancellationToken);

    public async Task<IResourceLease> AcquireAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        ValidateWirePath(resource.WirePath);
        var state = GetState(resource.Account);
        if (!state.Session.Permissions.Download) throw Safe(ErrorCode.PermissionDenied, "此账户没有读取文件内容的权限。");
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        var result = await CallAsync("lease", new { wirePath = resource.WirePath }, state.CoreSession, linked.Token).ConfigureAwait(false);
        string address = RequiredText(result);
        if (!Uri.TryCreate(address, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttp ||
            uri.Host != "127.0.0.1" || uri.Port <= 0 || uri.IsDefaultPort || uri.UserInfo.Length != 0 ||
            uri.Query.Length != 0 || uri.Fragment.Length != 0)
            throw InvalidResponse();
        var lease = new ResourceLease(this, state, resource, uri);
        lock (_gate)
        {
            if (state.Active) { state.Leases.Add(lease); return lease; }
        }
        await lease.DisposeAsync().ConfigureAwait(false);
        throw Safe(ErrorCode.SourceChanged, "账户连接已关闭，请重新连接。");
    }

    public async Task<IImageContent> OpenAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        var lease = (ResourceLease)await AcquireAsync(resource, cancellationToken).ConfigureAwait(false);
        HttpResponseMessage? response = null;
        MemoryStream? content = null;
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, lease.State.LifetimeToken);
        try
        {
            response = await _images.GetAsync(lease.Address, HttpCompletionOption.ResponseHeadersRead, linked.Token).ConfigureAwait(false);
            CheckStatus((int)response.StatusCode);
            if (response.Content.Headers.ContentLength > MaximumImageBytes) throw Safe(ErrorCode.ResponseTooLarge, "图片超过首预览的 64 MiB 大小限制。");
            content = new MemoryStream();
            await using var input = await response.Content.ReadAsStreamAsync(linked.Token).ConfigureAwait(false);
            byte[] buffer = new byte[81920];
            while (true)
            {
                int count = await input.ReadAsync(buffer, linked.Token).ConfigureAwait(false);
                if (count == 0) break;
                if (content.Length + count > MaximumImageBytes) throw Safe(ErrorCode.ResponseTooLarge, "图片超过首预览的 64 MiB 大小限制。");
                await content.WriteAsync(buffer.AsMemory(0, count), linked.Token).ConfigureAwait(false);
            }
            content.Position = 0;
            var image = new ImageContent(resource, content, response, lease);
            lease.Attach(image);
            EnsureActive(lease.State);
            return image;
        }
        catch (FilewayException) { content?.Dispose(); response?.Dispose(); await lease.DisposeAsync().ConfigureAwait(false); throw; }
        catch (OperationCanceledException)
        {
            content?.Dispose(); response?.Dispose(); await lease.DisposeAsync().ConfigureAwait(false);
            var code = cancellationToken.IsCancellationRequested ? ErrorCode.Canceled :
                lease.State.LifetimeToken.IsCancellationRequested ? ErrorCode.SourceChanged : ErrorCode.Timeout;
            throw Safe(code, Message(code));
        }
        catch (Exception error) when (error is HttpRequestException or IOException or ObjectDisposedException)
        { content?.Dispose(); response?.Dispose(); await lease.DisposeAsync().ConfigureAwait(false); throw Safe(ErrorCode.NetworkUnavailable, "无法读取图片内容，请检查连接后重试。"); }
    }

    public async ValueTask DisposeAsync()
    {
        await _connections.WaitAsync().ConfigureAwait(false);
        try
        {
            lock (_gate) { if (_disposed) return; _disposed = true; }
            await RetireAllAsync().ConfigureAwait(false);
            _images.Dispose();
        }
        finally { _connections.Release(); }
    }

    private static void ValidateProfile(ConnectionProfile profile, LoginInput login)
    {
        if (profile.Id == Guid.Empty || profile.SourceRevision == Guid.Empty || !profile.BaseAddress.IsAbsoluteUri ||
            profile.BaseAddress.Scheme is not ("http" or "https") || profile.BaseAddress.UserInfo.Length != 0 ||
            profile.BaseAddress.Query.Length != 0 || profile.BaseAddress.Fragment.Length != 0)
            throw Safe(ErrorCode.InvalidRequest, "服务器地址无效，请输入 HTTP 或 HTTPS 地址。");
        if (profile.Network is not (NetworkMode.SystemNetwork or NetworkMode.SystemTailnet))
            throw Safe(ErrorCode.CapabilityMissing, "首预览仅支持系统网络或系统 Tailscale 连接。");
        if (profile.Proxy.Mode != ProxyMode.Direct)
            throw Safe(ErrorCode.CapabilityMissing, "首预览尚未接入系统或手动代理，请明确选择直接连接。");
        if (login.Mode is not (AuthenticationMode.Password or AuthenticationMode.NoAuthentication))
            throw Safe(ErrorCode.InvalidRequest, "请选择受支持的登录方式。");
        if (login.Mode == AuthenticationMode.Password && (string.IsNullOrWhiteSpace(login.Username) || string.IsNullOrEmpty(login.Password)))
            throw Safe(ErrorCode.InvalidRequest, "请输入用户名和密码。");
    }

    private SessionState GetState(AccountKey account)
    {
        lock (_gate)
        {
            if (!_disposed && _sessions.TryGetValue(account, out var state) && state.Active) return state;
        }
        throw Safe(ErrorCode.SourceChanged, "账户连接已失效，请重新连接。");
    }

    private void EnsureActive(SessionState state)
    {
        lock (_gate) if (!state.Active || _disposed) throw Safe(ErrorCode.SourceChanged, "账户连接已更换，请刷新后重试。");
    }

    private async Task RetireAccountAsync(AccountKey account, CancellationToken cancellationToken)
    {
        await _connections.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            SessionState? state;
            lock (_gate) _sessions.Remove(account, out state);
            if (state is not null) await RetireAsync(state).ConfigureAwait(false);
        }
        finally { _connections.Release(); }
    }

    private async Task RetireAllAsync()
    {
        SessionState[] states;
        lock (_gate) { states = [.. _sessions.Values]; _sessions.Clear(); }
        foreach (var state in states) await RetireAsync(state).ConfigureAwait(false);
    }

    private async Task RetireAsync(SessionState state)
    {
        ResourceLease[] leases;
        lock (_gate) { state.Active = false; leases = [.. state.Leases]; }
        await state.Lifetime.CancelAsync().ConfigureAwait(false);
        foreach (var lease in leases) await lease.DisposeAsync().ConfigureAwait(false);
        await CloseCoreAsync(state.CoreSession).ConfigureAwait(false);
        state.Lifetime.Dispose();
    }

    private async Task CloseCoreAsync(string coreSession)
    {
        try { await CallAsync("close_session", new { }, coreSession, CancellationToken.None).ConfigureAwait(false); }
        catch (FilewayException) { /* Host exit already invalidates the entire session. */ }
    }

    private async Task<JsonDocument> ResourceDocumentAsync(SessionState state, RemoteResourceRef resource, bool metadata, CancellationToken cancellationToken)
    {
        ValidateWirePath(resource.WirePath);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        var response = await RequestAsync(state.CoreSession, "/api/resources" + resource.WirePath + (metadata ? "?metadata=1" : ""), linked.Token).ConfigureAwait(false);
        EnsureActive(state);
        return ParseBody(response);
    }

    private Task<JsonElement> RequestAsync(string session, string endpoint, CancellationToken cancellationToken) =>
        CallAsync("request", new { method = "GET", endpoint }, session, cancellationToken);

    private async Task<JsonElement> CallAsync(string operation, object parameters, string? session, CancellationToken cancellationToken)
    {
        try
        {
            var reply = await _host.CallAsync(new HostCall(operation, JsonSerializer.SerializeToElement(parameters), session,
                Safety: operation is "open" or "close_session" or "revoke" ? HostCallSafety.Control : HostCallSafety.ReadOnly), cancellationToken).ConfigureAwait(false);
            if (!reply.IsSuccess)
            {
                if (reply.Error?.HttpStatus is { } status) CheckStatus(status);
                var code = reply.Error?.Code switch
                {
                    "Canceled" => ErrorCode.Canceled, "Busy" => ErrorCode.Busy, "ResponseTooLarge" => ErrorCode.ResponseTooLarge,
                    "ProtocolMismatch" or "HandshakeRequired" => ErrorCode.ProtocolMismatch,
                    "HostClosing" or "HostFailure" => ErrorCode.HostExited, "InvalidRequest" => ErrorCode.InvalidRequest,
                    _ => ErrorCode.NetworkUnavailable,
                };
                throw Safe(code, Message(code));
            }
            return reply.Result ?? throw InvalidResponse();
        }
        catch (FilewayException error) { throw new FilewayException(error.Error with { Message = Message(error.Error.Code) }); }
        catch (OperationCanceledException) { throw Safe(ErrorCode.Canceled, "操作已取消。"); }
    }

    private static string HttpBody(JsonElement response)
    {
        try { CheckStatus(response.GetProperty("status").GetInt32()); return response.GetProperty("body").GetString() ?? throw InvalidResponse(); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or KeyNotFoundException) { throw InvalidResponse(); }
    }

    private static JsonDocument ParseBody(JsonElement response)
    {
        try { return JsonDocument.Parse(HttpBody(response)); }
        catch (JsonException) { throw InvalidResponse(); }
    }

    private static string ReadTokenUserId(string token)
    {
        try
        {
            var parts = token.Trim().Split('.');
            if (parts.Length != 3 || parts[1].Length > 256 * 1024) throw InvalidResponse();
            string payload = parts[1].Replace('-', '+').Replace('_', '/');
            payload = payload.PadRight((payload.Length + 3) / 4 * 4, '=');
            using var document = JsonDocument.Parse(Convert.FromBase64String(payload));
            return ReadUserId(document.RootElement.GetProperty("user"));
        }
        catch (Exception error) when (error is FormatException or JsonException or InvalidOperationException or KeyNotFoundException) { throw InvalidResponse(); }
    }

    private static string ReadUserId(JsonElement user)
    {
        if (!user.GetProperty("id").TryGetUInt64(out ulong id) || id == 0) throw InvalidResponse();
        return id.ToString(CultureInfo.InvariantCulture);
    }

    private static RemoteFile ParseFile(JsonElement item, AccountKey account)
    {
        string wirePath = RequiredText(item.GetProperty("wirePath"));
        ValidateWirePath(wirePath);
        string name = OptionalText(item, "name") is { } actualName && (actualName.Length > 0 || wirePath == "/")
            ? actualName.Length == 0 ? "根目录" : actualName : throw InvalidResponse();
        string displayPath = RequiredText(item.GetProperty("path"));
        bool isDirectory = item.GetProperty("isDir").GetBoolean();
        long size = item.GetProperty("size").GetInt64();
        if (size < 0) throw InvalidResponse();
        DateTimeOffset? modified = null;
        if (OptionalText(item, "modified") is { } date && DateTimeOffset.TryParse(date, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var parsed)) modified = parsed;
        return new RemoteFile(new RemoteResourceRef(account, wirePath, name, displayPath), isDirectory, size, modified, OptionalText(item, "type"));
    }

    private static int CompareFiles(RemoteFile left, RemoteFile right, DirectoryQuery query)
    {
        if (left.IsDirectory != right.IsDirectory) return left.IsDirectory ? -1 : 1;
        int order = query.SortBy switch
        {
            FileSortField.Size => left.Size.CompareTo(right.Size), FileSortField.Modified => Nullable.Compare(left.Modified, right.Modified),
            FileSortField.Type => StringComparer.OrdinalIgnoreCase.Compare(left.MediaType, right.MediaType),
            _ => StringComparer.OrdinalIgnoreCase.Compare(left.Resource.DisplayName, right.Resource.DisplayName),
        };
        if (order == 0) order = StringComparer.Ordinal.Compare(left.Resource.WirePath, right.Resource.WirePath);
        return query.Descending ? -Math.Sign(order) : order;
    }

    private static void ValidateWirePath(string wirePath)
    {
        if (string.IsNullOrEmpty(wirePath) || wirePath[0] != '/' || wirePath.Any(character => character is '?' or '#' or '\\' || char.IsControl(character)))
            throw InvalidResponse();
    }

    private static bool Flag(JsonElement value, string name) => value.TryGetProperty(name, out var flag) && flag.ValueKind == JsonValueKind.True;
    private static string? OptionalText(JsonElement value, string name) => value.TryGetProperty(name, out var text) && text.ValueKind == JsonValueKind.String ? text.GetString() : null;
    private static string RequiredText(JsonElement value) => value.ValueKind == JsonValueKind.String && value.GetString() is { Length: > 0 } text ? text : throw InvalidResponse();
    private static FilewayException Safe(ErrorCode code, string message, int? status = null) => new(new ErrorInfo(code, message, status));
    private static FilewayException InvalidResponse() => Safe(ErrorCode.ProtocolMismatch, "服务器返回的数据格式不受支持，请刷新或检查服务器版本。");
    private static Task<FileOperationResult> UnsupportedWrite(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        throw Safe(ErrorCode.CapabilityMissing, "首预览尚未开放文件写入操作。");
    }

    private static void CheckStatus(int status)
    {
        if (status is >= 200 and < 300) return;
        var code = status switch { 401 => ErrorCode.AuthenticationExpired, 403 => ErrorCode.PermissionDenied, 409 => ErrorCode.Conflict, 408 or 504 => ErrorCode.Timeout, _ => ErrorCode.NetworkUnavailable };
        throw Safe(code, Message(code), status);
    }

    private static string Message(ErrorCode code) => code switch
    {
        ErrorCode.AuthenticationExpired => "登录已失效，请重新登录。", ErrorCode.PermissionDenied => "没有权限执行此操作，请检查账户权限。",
        ErrorCode.Canceled => "操作已取消。", ErrorCode.Busy => "请求较多，请稍后重试。", ErrorCode.ResponseTooLarge => "服务器响应超过大小限制。",
        ErrorCode.ProtocolMismatch => "服务器返回的数据格式不受支持。", ErrorCode.HostExited => "连接服务已退出，请重新连接。",
        ErrorCode.InvalidRequest => "请求参数无效。", ErrorCode.Timeout => "连接超时，请稍后重试。", ErrorCode.Conflict => "服务器内容已变化，请刷新后重试。",
        ErrorCode.SourceChanged => "账户连接已更换，请重新连接。", ErrorCode.CapabilityMissing => "当前连接不支持此功能。",
        _ => "无法完成服务器请求，请检查网络或服务器后重试。",
    };

    private sealed class ResourceLease(FilewayClientServices owner, SessionState state, RemoteResourceRef resource, Uri address) : IResourceLease
    {
        private int _disposed;
        private ImageContent? _image;
        internal SessionState State { get; } = state;
        public RemoteResourceRef Resource { get; } = resource;
        public Uri Address { get; } = address;
        public override string ToString() => "ResourceLease (address redacted)";

        internal void Attach(ImageContent image)
        {
            lock (owner._gate)
            {
                if (_disposed != 0) { image.CloseContent(); throw Safe(ErrorCode.SourceChanged, "图片所属账户连接已关闭。"); }
                _image = image;
            }
        }

        public async ValueTask DisposeAsync()
        {
            lock (owner._gate)
            {
                if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
                State.Leases.Remove(this);
                _image?.CloseContent();
                _image = null;
            }
            try { await owner.CallAsync("revoke", new { url = Address.AbsoluteUri }, null, CancellationToken.None).ConfigureAwait(false); }
            catch (FilewayException) { /* Session closure also retires its leases. */ }
        }
    }

    private sealed class ImageContent(RemoteResourceRef resource, MemoryStream content, HttpResponseMessage response, ResourceLease lease) : IImageContent
    {
        public RemoteResourceRef Resource { get; } = resource;
        public Stream Content { get; } = content;
        public string? ContentType { get; } = response.Content.Headers.ContentType?.MediaType;
        public long Length { get; } = content.Length;
        public override string ToString() => "ImageContent (source redacted)";
        internal void CloseContent() { content.Dispose(); response.Dispose(); }
        public ValueTask DisposeAsync() => lease.DisposeAsync();
    }
}
