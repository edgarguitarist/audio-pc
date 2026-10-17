using System.Net;
using System.Net.NetworkInformation;
using Microsoft.Extensions.Logging;

namespace AudioPc.Server.Api;

/// <summary>
/// DNS-rebinding defence: a web page on attacker.example can make its own hostname resolve to
/// this PC, and the browser then treats our API as same-origin. The browser still sends
/// <c>Host: attacker.example</c>, so only requests for names that really belong to this PC
/// (or IP literals, which a rebinding page cannot use) are served.
/// </summary>
public sealed class HostGuard
{
    private readonly Func<IEnumerable<string?>> _configured;
    private readonly ILogger _log;
    private volatile HashSet<string> _suffixNames;

    /// <param name="configured">Display name + <c>allowedHosts</c> from config.json (read per request, so they stay current).</param>
    public HostGuard(Func<IEnumerable<string?>> configured, ILogger log)
    {
        _configured = configured;
        _log = log;
        _suffixNames = BuildSuffixNames();
        // DNS suffixes come from DHCP: refresh when the network changes. This handler runs on a
        // system thread, so it must never throw.
        NetworkChange.NetworkAddressChanged += (_, _) =>
        {
            try { _suffixNames = BuildSuffixNames(); }
            catch (Exception ex) { _log.LogWarning(ex, "could not refresh DNS suffixes"); }
        };
    }

    /// <summary>True when <paramref name="host"/> (the Host header without port) is one of ours.</summary>
    public bool IsAllowed(string? host)
    {
        // No Host at all only happens with HTTP/1.0 tools; browsers always send it, so it is not a rebinding vector.
        if (string.IsNullOrEmpty(host)) return true;
        var h = Normalize(host);
        if (h.Length == 0) return false;
        if (h.StartsWith('[') && h.EndsWith(']')) h = h[1..^1];
        if (IPAddress.TryParse(h, out _)) return true;

        var machine = Environment.MachineName;
        if (Eq(h, "localhost") || Eq(h, "host.docker.internal") || Eq(h, machine) || Eq(h, machine + ".local")) return true;
        if (_suffixNames.Contains(h)) return true;
        foreach (var c in _configured())
            if (!string.IsNullOrWhiteSpace(c) && Eq(h, Normalize(c))) return true;
        return false;
    }

    private static string Normalize(string s) => s.Trim().TrimEnd('.').ToLowerInvariant();
    private static bool Eq(string a, string b) => string.Equals(a, b, StringComparison.OrdinalIgnoreCase);

    // "<machine>.<suffix>" for the connection-specific DNS suffixes (e.g. "pc-edgar.lan",
    // "pc-edgar.home") and the primary domain: what a user typing the PC name usually ends up with.
    private static HashSet<string> BuildSuffixNames()
    {
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var machine = Environment.MachineName.ToLowerInvariant();
        void Add(string? suffix)
        {
            suffix = suffix?.Trim().Trim('.');
            if (!string.IsNullOrEmpty(suffix)) set.Add($"{machine}.{suffix.ToLowerInvariant()}");
        }
        try { Add(IPGlobalProperties.GetIPGlobalProperties().DomainName); } catch { /* not available */ }
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                try { Add(ni.GetIPProperties().DnsSuffix); } catch { /* interface vanished */ }
            }
        }
        catch { /* not available */ }
        return set;
    }
}
