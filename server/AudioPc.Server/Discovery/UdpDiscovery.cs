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
        var payload = Encoding.UTF8.GetBytes(reply(httpPort));
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var r = await _udp.ReceiveAsync(_cts.Token);
                if (Encoding.ASCII.GetString(r.Buffer).Trim() == "AUDIOPC_DISCOVER 1")
                    await _udp.SendAsync(payload, r.RemoteEndPoint, _cts.Token);
            }
            catch (OperationCanceledException) { return; }
            catch (ObjectDisposedException) { return; }
            // ICMP "port unreachable" from a client that already left surfaces as a reset; keep going.
            catch (SocketException) { }
            catch (Exception ex) { _log.LogDebug(ex, "discovery loop"); }
        }
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
