using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace AudioPc.Server.Discovery;

/// <summary>Answers "AUDIOPC_DISCOVER 1" datagrams on UDP 7798 with the /api/info JSON plus the HTTP port.</summary>
public sealed class UdpDiscovery : IDisposable
{
    public const int DiscoveryPort = 7798;
    private static readonly byte[] Probe = Encoding.ASCII.GetBytes("AUDIOPC_DISCOVER 1");
    // Our reply is much larger than the probe, so a spoofed source address could use us as a
    // reflector/amplifier: cap replies per source and overall (fixed one-second windows).
    private const int MaxRepliesPerIpPerSecond = 5;
    private const int MaxRepliesPerSecond = 50;
    private readonly Dictionary<IPAddress, int> _perIp = new();
    private long _windowStart;
    private int _windowTotal;
    private readonly UdpClient _udp;
    private readonly CancellationTokenSource _cts = new();
    private readonly ILogger _log;

    private UdpDiscovery(UdpClient udp, Func<int, string> reply, int httpPort, ILogger log)
    {
        _udp = udp; _log = log;
        _ = Task.Run(() => Loop(reply, httpPort));
    }

    /// <returns>null (and a log line) if the port is taken; discovery is optional.</returns>
    public static UdpDiscovery? TryStart(Func<int, string> reply, int httpPort, ILogger log)
    {
        try
        {
            var udp = new UdpClient(new IPEndPoint(IPAddress.Any, DiscoveryPort));
            return new UdpDiscovery(udp, reply, httpPort, log);
        }
        catch (Exception ex)
        {
            log.LogWarning("UDP discovery disabled: {Message}", ex.Message);
            return null;
        }
    }

    private async Task Loop(Func<int, string> reply, int httpPort)
    {
        try
        {
            var payload = Encoding.UTF8.GetBytes(reply(httpPort));
            while (!_cts.IsCancellationRequested)
            {
                try
                {
                    var r = await _udp.ReceiveAsync(_cts.Token);
                    if (r.Buffer.AsSpan().SequenceEqual(Probe) && AllowReply(r.RemoteEndPoint.Address))
                        await _udp.SendAsync(payload, r.RemoteEndPoint, _cts.Token);
                }
                catch (OperationCanceledException) { return; }
                catch (ObjectDisposedException) { return; }
                // ICMP "port unreachable" from a client that already left surfaces as a reset; keep going.
                catch (SocketException) { }
                catch (Exception ex) { _log.LogDebug(ex, "discovery loop"); }
            }
        }
        catch (Exception ex) { _log.LogError(ex, "UDP discovery stopped"); }
    }

    // Only the loop thread calls this. The per-IP table is cleared every second and only grows
    // while the global budget allows, so it holds at most MaxRepliesPerSecond entries.
    private bool AllowReply(IPAddress ip)
    {
        var now = Environment.TickCount64;
        if (now - _windowStart >= 1000) { _windowStart = now; _windowTotal = 0; _perIp.Clear(); }
        if (_windowTotal >= MaxRepliesPerSecond) return false;
        _perIp.TryGetValue(ip, out var n);
        if (n >= MaxRepliesPerIpPerSecond) return false;
        _perIp[ip] = n + 1;
        _windowTotal++;
        return true;
    }

    public static string BuildReply(object info, int port)
    {
        var json = JsonSerializer.SerializeToElement(info, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            foreach (var p in json.EnumerateObject()) p.WriteTo(w);
            w.WriteNumber("port", port);
            w.WriteEndObject();
        }
        return Encoding.UTF8.GetString(ms.ToArray());
    }

    public void Dispose() { _cts.Cancel(); _udp.Dispose(); }
}
