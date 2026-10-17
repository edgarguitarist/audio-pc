using System.Security.Cryptography;
using System.Text;
using AudioPc.Server.Data;

namespace AudioPc.Server.Api;

public sealed record PendingPairing(string RequestId, string DeviceId, string DeviceName, string Code, DateTimeOffset ExpiresAt);

public enum ConfirmResult { Ok, BadCode, Locked, GloballyLocked, Expired }

public enum RequestStatus { Ok, RateLimited, TooManyClients, TooManyPendingForIp, TooManyPending, GloballyLocked }

/// <summary>Pairing requests: 6-digit code shown on the PC, typed on the phone.</summary>
public sealed class PairingService
{
    public const int ExpiresInSeconds = 120;
    private const int MaxAttempts = 5;              // wrong codes per request
    private const int MaxPending = 10;              // pending requests, all clients
    private const int MaxPendingPerIp = 2;
    private const int MaxRequestsPerIpPerMinute = 5;
    private const int MaxTrackedIps = 4096;
    // Global budget: a per-request/per-IP limit alone is beaten by rotating source addresses
    // (~1000 guesses/s were measured), so wrong codes from ANY client count towards one budget
    // and exhausting it disables pairing until the user re-enables it from the tray.
    private const int GlobalFailBudget = 10;
    private static readonly TimeSpan GlobalFailWindow = TimeSpan.FromMinutes(10);

    private sealed class Entry
    {
        public required PendingPairing Info;
        public required string Ip;
        public int Attempts;
    }

    private readonly object _gate = new();
    private readonly Dictionary<string, Entry> _byRequest = new();
    private readonly KeyedWindow _ipHits = new(TimeSpan.FromMinutes(1), MaxTrackedIps);
    private readonly Queue<DateTimeOffset> _failures = new();
    private readonly PairedStore _paired;
    private bool _locked;

    /// <summary>A new code was issued — the tray shows a balloon.</summary>
    public event Action<PendingPairing>? CodeIssued;
    public event EventHandler? PendingChanged;
    /// <summary>Raised when the global lock turns on or off (see <see cref="IsLocked"/>).</summary>
    public event EventHandler? LockChanged;

    public PairingService(PairedStore paired) { _paired = paired; }

    /// <summary>True after too many wrong codes; pairing stays off until <see cref="Unlock"/>.</summary>
    public bool IsLocked { get { lock (_gate) return _locked; } }

    public void Unlock()
    {
        lock (_gate)
        {
            if (!_locked) return;
            _locked = false;
            _failures.Clear();
        }
        LockChanged?.Invoke(this, EventArgs.Empty);
    }

    public IReadOnlyList<PendingPairing> Pending()
    {
        lock (_gate) { Purge(); return _byRequest.Values.Select(e => e.Info).ToList(); }
    }

    public static string Format(string code) => code.Length == 6 ? $"{code[..3]} {code[3..]}" : code;

    public RequestStatus Request(string ip, string deviceId, string deviceName, out PendingPairing? pending)
    {
        pending = null;
        PendingPairing info;
        lock (_gate)
        {
            if (_locked) return RequestStatus.GloballyLocked;
            Purge();
            switch (_ipHits.TryHit(ip, MaxRequestsPerIpPerMinute))
            {
                case HitResult.OverLimit: return RequestStatus.RateLimited;
                case HitResult.TableFull: return RequestStatus.TooManyClients;
            }
            // One pending request per deviceId: a new one replaces the old — but only one made from
            // the same address, so nobody can cancel someone else's request by reusing their deviceId.
            foreach (var old in _byRequest.Where(kv => kv.Value.Info.DeviceId == deviceId && kv.Value.Ip == ip).Select(kv => kv.Key).ToList())
                _byRequest.Remove(old);
            // Full pool → refuse the newcomer; never evict others (that would let one client
            // starve everyone else's pairing).
            if (_byRequest.Values.Count(e => e.Ip == ip) >= MaxPendingPerIp) return RequestStatus.TooManyPendingForIp;
            if (_byRequest.Count >= MaxPending) return RequestStatus.TooManyPending;
            var code = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
            var id = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
            info = new PendingPairing(id, deviceId, deviceName, code, DateTimeOffset.UtcNow.AddSeconds(ExpiresInSeconds));
            _byRequest[id] = new Entry { Info = info, Ip = ip };
        }
        pending = info;
        PendingChanged?.Invoke(this, EventArgs.Empty);
        CodeIssued?.Invoke(info);
        return RequestStatus.Ok;
    }

    public ConfirmResult Confirm(string requestId, string code, out string? token, out PendingPairing? pairing)
    {
        token = null; pairing = null;
        ConfirmResult result;
        var lockedNow = false;
        lock (_gate)
        {
            if (_locked) return ConfirmResult.GloballyLocked;
            Purge();
            if (!_byRequest.TryGetValue(requestId, out var e)) return ConfirmResult.Expired;
            var ok = CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(code.Replace(" ", "")), Encoding.ASCII.GetBytes(e.Info.Code));
            if (ok)
            {
                _byRequest.Remove(requestId);
                pairing = e.Info;
                result = ConfirmResult.Ok;
            }
            else
            {
                var now = DateTimeOffset.UtcNow;
                while (_failures.Count > 0 && now - _failures.Peek() > GlobalFailWindow) _failures.Dequeue();
                _failures.Enqueue(now);
                if (_failures.Count >= GlobalFailBudget)
                {
                    // Drop every pending code too: none of them may be confirmed while locked,
                    // and after the unlock clients simply request a new one.
                    _locked = true;
                    lockedNow = true;
                    _byRequest.Clear();
                    result = ConfirmResult.GloballyLocked;
                }
                else if (++e.Attempts >= MaxAttempts)
                {
                    _byRequest.Remove(requestId);
                    result = ConfirmResult.Locked;
                }
                else return ConfirmResult.BadCode;
            }
        }
        if (result == ConfirmResult.Ok) token = _paired.Issue(pairing!.DeviceId, pairing.DeviceName);
        PendingChanged?.Invoke(this, EventArgs.Empty);
        if (lockedNow) LockChanged?.Invoke(this, EventArgs.Empty);
        return result;
    }

    private void Purge()
    {
        var now = DateTimeOffset.UtcNow;
        foreach (var k in _byRequest.Where(kv => kv.Value.Info.ExpiresAt <= now).Select(kv => kv.Key).ToList())
            _byRequest.Remove(k);
    }
}
