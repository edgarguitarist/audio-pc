using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using AudioPc.Server.Data;

namespace AudioPc.Server.Api;

public sealed record PendingPairing(string RequestId, string DeviceId, string DeviceName, string Code, DateTimeOffset ExpiresAt);

public enum ConfirmResult { Ok, BadCode, Locked, Expired }

/// <summary>Pairing requests: 6-digit code shown on the PC, typed on the phone.</summary>
public sealed class PairingService
{
    public const int ExpiresInSeconds = 120;
    private const int MaxAttempts = 5;
    private const int MaxPending = 10;
    private const int MaxRequestsPerIpPerMinute = 5;

    private sealed class Entry
    {
        public required PendingPairing Info;
        public int Attempts;
    }

    private readonly object _gate = new();
    private readonly Dictionary<string, Entry> _byRequest = new();
    private readonly ConcurrentDictionary<string, Queue<DateTimeOffset>> _ipHits = new();
    private readonly PairedStore _paired;

    /// <summary>A new code was issued (deviceName, formatted code) — the tray shows a balloon.</summary>
    public event Action<PendingPairing>? CodeIssued;
    public event EventHandler? PendingChanged;

    public PairingService(PairedStore paired) { _paired = paired; }

    public IReadOnlyList<PendingPairing> Pending()
    {
        lock (_gate) { Purge(); return _byRequest.Values.Select(e => e.Info).ToList(); }
    }

    public static string Format(string code) => code.Length == 6 ? $"{code[..3]} {code[3..]}" : code;

    /// <returns>The pending request, or null when rate limited / too many pending.</returns>
    public PendingPairing? Request(string ip, string deviceId, string deviceName)
    {
        PendingPairing info;
        lock (_gate)
        {
            Purge();
            if (!AllowIp(ip)) return null;
            // One pending request per deviceId: a new one replaces the old.
            foreach (var old in _byRequest.Where(kv => kv.Value.Info.DeviceId == deviceId).Select(kv => kv.Key).ToList())
                _byRequest.Remove(old);
            if (_byRequest.Count >= MaxPending) return null;
            var code = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
            var id = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
            info = new PendingPairing(id, deviceId, deviceName, code, DateTimeOffset.UtcNow.AddSeconds(ExpiresInSeconds));
            _byRequest[id] = new Entry { Info = info };
        }
        PendingChanged?.Invoke(this, EventArgs.Empty);
        CodeIssued?.Invoke(info);
        return info;
    }

    public ConfirmResult Confirm(string requestId, string code, out string? token, out PendingPairing? pairing)
    {
        token = null; pairing = null;
        ConfirmResult result;
        lock (_gate)
        {
            Purge();
            if (!_byRequest.TryGetValue(requestId, out var e)) return ConfirmResult.Expired;
            var ok = CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(code.Replace(" ", "")), Encoding.ASCII.GetBytes(e.Info.Code));
            if (ok)
            {
                _byRequest.Remove(requestId);
                pairing = e.Info;
                result = ConfirmResult.Ok;
            }
            else if (++e.Attempts >= MaxAttempts)
            {
                _byRequest.Remove(requestId);
                result = ConfirmResult.Locked;
            }
            else return ConfirmResult.BadCode;
        }
        if (result == ConfirmResult.Ok) token = _paired.Issue(pairing!.DeviceId, pairing.DeviceName);
        PendingChanged?.Invoke(this, EventArgs.Empty);
        return result;
    }

    private void Purge()
    {
        var now = DateTimeOffset.UtcNow;
        foreach (var k in _byRequest.Where(kv => kv.Value.Info.ExpiresAt <= now).Select(kv => kv.Key).ToList())
            _byRequest.Remove(k);
    }

    private bool AllowIp(string ip)
    {
        var now = DateTimeOffset.UtcNow;
        var q = _ipHits.GetOrAdd(ip, _ => new Queue<DateTimeOffset>());
        while (q.Count > 0 && now - q.Peek() > TimeSpan.FromMinutes(1)) q.Dequeue();
        if (q.Count >= MaxRequestsPerIpPerMinute) return false;
        q.Enqueue(now);
        if (_ipHits.Count > 1000)
            foreach (var k in _ipHits.Where(kv => kv.Value.Count == 0).Select(kv => kv.Key).ToList()) _ipHits.TryRemove(k, out _);
        return true;
    }
}
