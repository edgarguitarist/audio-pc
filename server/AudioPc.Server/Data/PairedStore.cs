using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace AudioPc.Server.Data;

/// <summary>Paired devices (token hashes) persisted in paired.json.</summary>
public sealed class PairedStore
{
    private static readonly TimeSpan LastSeenWriteEvery = TimeSpan.FromMinutes(2);
    private readonly object _gate = new();
    private readonly string _path;
    private readonly ILogger _log;
    private const int MinLegacyTokenLength = 16;
    private readonly byte[]? _legacyHash; // null = no usable legacy token
    private readonly List<PairedDevice> _devices = new();

    public event EventHandler? Changed;
    /// <summary>Raised with the deviceId after a device is revoked (used to drop its sockets).</summary>
    public event Action<string>? Revoked;

    public PairedStore(string dir, ServerConfig config, ILogger log)
    {
        _path = Path.Combine(dir, "paired.json");
        _log = log;
        // Validated once (config.json is read at startup): a blank or short legacy token would be
        // trivially guessable, so it is ignored with a single warning instead of accepted.
        if (!string.IsNullOrWhiteSpace(config.LegacyToken))
        {
            if (config.LegacyToken.Length >= MinLegacyTokenLength)
                _legacyHash = Encoding.ASCII.GetBytes(HashToken(config.LegacyToken));
            else
                _log.LogWarning("legacyToken in config.json is shorter than {Min} characters and is ignored", MinLegacyTokenLength);
        }
        try
        {
            if (File.Exists(_path))
                _devices = JsonSerializer.Deserialize<List<PairedDevice>>(File.ReadAllText(_path), Json.Options) ?? new();
        }
        catch (Exception ex)
        {
            _log.LogError(ex, "paired.json is invalid; moved aside, devices must pair again");
            File.Move(_path, _path + ".bad", true);
        }
    }

    public static string HashToken(string token) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    public IReadOnlyList<PairedDevice> List() { lock (_gate) return _devices.ToList(); }

    /// <summary>Creates a new token for the device (replacing any previous one) and returns it.</summary>
    public string Issue(string deviceId, string deviceName)
    {
        var token = Base64Url(RandomNumberGenerator.GetBytes(32));
        var now = DateTimeOffset.UtcNow;
        lock (_gate)
        {
            _devices.RemoveAll(d => d.DeviceId == deviceId);
            _devices.Add(new PairedDevice { DeviceId = deviceId, DeviceName = deviceName, TokenHash = HashToken(token), CreatedAt = now, LastSeen = now });
            Save();
        }
        Changed?.Invoke(this, EventArgs.Empty);
        return token;
    }

    public bool Revoke(string deviceId)
    {
        bool removed;
        lock (_gate) { removed = _devices.RemoveAll(d => d.DeviceId == deviceId) > 0; if (removed) Save(); }
        if (removed) { Changed?.Invoke(this, EventArgs.Empty); Revoked?.Invoke(deviceId); }
        return removed;
    }

    /// <summary>Returns the matching paired device, or null. A configured legacy token yields a
    /// synthetic device (not stored). Compares hashes in constant time.</summary>
    public PairedDevice? Authenticate(string? token)
    {
        if (string.IsNullOrWhiteSpace(token) || token.Length > 256) return null;
        var hash = Encoding.ASCII.GetBytes(HashToken(token));
        PairedDevice? found = null;
        lock (_gate)
        {
            // No early exit: the loop time must not depend on which entry matched.
            foreach (var d in _devices)
                if (CryptographicOperations.FixedTimeEquals(hash, Encoding.ASCII.GetBytes(d.TokenHash))) found = d;
            if (found != null)
            {
                var now = DateTimeOffset.UtcNow;
                if (now - found.LastSeen > LastSeenWriteEvery) { found.LastSeen = now; Save(); }
            }
        }
        if (found != null) return found;
        if (_legacyHash != null && CryptographicOperations.FixedTimeEquals(hash, _legacyHash))
            return new PairedDevice { DeviceId = "legacy", DeviceName = "Legacy token" };
        return null;
    }

    private void Save()
    {
        try { Json.WriteAtomic(_path, _devices); }
        catch (Exception ex) { _log.LogError(ex, "could not write paired.json"); }
    }

    private static string Base64Url(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
