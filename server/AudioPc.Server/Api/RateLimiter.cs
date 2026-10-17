namespace AudioPc.Server.Api;

/// <summary>
/// Per-key sliding-window hit counter with a bounded table. Entries whose hits all expired are
/// removed (on access and by a periodic sweep), so an attacker rotating source addresses cannot
/// grow memory without limit: once <c>maxKeys</c> live keys exist, new keys are refused.
/// Thread-safe; uses the monotonic clock so wall-clock changes do not reset windows.
/// </summary>
public sealed class KeyedWindow
{
    private readonly object _gate = new();
    private readonly Dictionary<string, Queue<long>> _hits = new(StringComparer.Ordinal);
    private readonly long _windowMs;
    private readonly int _maxKeys;
    private long _lastSweep = Environment.TickCount64;

    public KeyedWindow(TimeSpan window, int maxKeys)
    {
        _windowMs = (long)window.TotalMilliseconds;
        _maxKeys = maxKeys;
    }

    public int KeyCount { get { lock (_gate) return _hits.Count; } }

    /// <summary>Records a hit unless the key already has <paramref name="limit"/> hits in the window.</summary>
    /// <returns><see cref="HitResult.Ok"/>, <see cref="HitResult.OverLimit"/>, or <see cref="HitResult.TableFull"/>.</returns>
    public HitResult TryHit(string key, int limit)
    {
        var now = Environment.TickCount64;
        lock (_gate)
        {
            SweepIfDue(now);
            if (!_hits.TryGetValue(key, out var q))
            {
                if (_hits.Count >= _maxKeys) return HitResult.TableFull;
                q = new Queue<long>();
                _hits[key] = q;
            }
            Expire(q, now);
            if (q.Count >= limit) return HitResult.OverLimit;
            q.Enqueue(now);
            return HitResult.Ok;
        }
    }

    /// <summary>Records a hit unconditionally and returns the hit count in the window (or -1 if the table is full).</summary>
    public int Hit(string key)
    {
        var now = Environment.TickCount64;
        lock (_gate)
        {
            SweepIfDue(now);
            if (!_hits.TryGetValue(key, out var q))
            {
                if (_hits.Count >= _maxKeys) return -1;
                q = new Queue<long>();
                _hits[key] = q;
            }
            Expire(q, now);
            q.Enqueue(now);
            return q.Count;
        }
    }

    public void Remove(string key) { lock (_gate) _hits.Remove(key); }

    private void Expire(Queue<long> q, long now)
    {
        while (q.Count > 0 && now - q.Peek() > _windowMs) q.Dequeue();
    }

    // Full sweep at most once per window: O(keys) but amortised, and it is what actually frees
    // entries of clients that never come back.
    private void SweepIfDue(long now)
    {
        if (now - _lastSweep < _windowMs) return;
        _lastSweep = now;
        List<string>? dead = null;
        foreach (var (k, q) in _hits)
        {
            Expire(q, now);
            if (q.Count == 0) (dead ??= new()).Add(k);
        }
        if (dead != null) foreach (var k in dead) _hits.Remove(k);
    }
}

public enum HitResult { Ok, OverLimit, TableFull }

/// <summary>
/// Throttles clients that keep failing authentication: after <c>MaxFailures</c> 401s within a
/// minute an address is answered 429 for a minute, even with a valid token, which makes online
/// token guessing pointless and keeps a misbehaving client from flooding the log.
/// </summary>
public sealed class AuthThrottle
{
    private const int MaxFailures = 20;
    private const int MaxKeys = 4096;
    private static readonly TimeSpan Window = TimeSpan.FromMinutes(1);
    private static readonly long BlockMs = (long)TimeSpan.FromMinutes(1).TotalMilliseconds;

    private readonly KeyedWindow _failures = new(Window, MaxKeys);
    private readonly object _gate = new();
    private readonly Dictionary<string, long> _blockedUntil = new(StringComparer.Ordinal);

    public bool IsBlocked(string ip)
    {
        var now = Environment.TickCount64;
        lock (_gate)
        {
            if (!_blockedUntil.TryGetValue(ip, out var until)) return false;
            if (now < until) return true;
            _blockedUntil.Remove(ip);
            return false;
        }
    }

    /// <summary>Counts a failed authentication.</summary>
    /// <returns>true when the failure cannot be tracked (table full) and should be answered 429 right away.</returns>
    public bool RecordFailure(string ip)
    {
        var count = _failures.Hit(ip);
        if (count < 0) return true; // fail closed for unknown addresses that are already failing
        if (count < MaxFailures) return false;
        var now = Environment.TickCount64;
        lock (_gate)
        {
            // Bounded too: drop expired blocks before adding (at most MaxKeys live entries).
            if (_blockedUntil.Count >= MaxKeys)
                foreach (var k in _blockedUntil.Where(kv => kv.Value <= now).Select(kv => kv.Key).ToList()) _blockedUntil.Remove(k);
            if (_blockedUntil.Count < MaxKeys) _blockedUntil[ip] = now + BlockMs;
        }
        _failures.Remove(ip); // the block itself now carries the state
        return false;
    }
}
