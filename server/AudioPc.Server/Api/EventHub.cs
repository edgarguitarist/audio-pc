using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using AudioPc.Server.Audio;
using AudioPc.Server.Data;
using Microsoft.Extensions.Logging;

namespace AudioPc.Server.Api;

/// <summary>WebSocket fan-out for /api/events. Subscribes to IAudioService.Changed once.</summary>
public sealed class EventHub : IDisposable
{
    private static readonly TimeSpan MinInterval = TimeSpan.FromMilliseconds(100);
    private static readonly TimeSpan PingEvery = TimeSpan.FromSeconds(30);
    private static readonly byte[] Ping = Encoding.UTF8.GetBytes("{\"type\":\"ping\"}");

    private sealed class Client
    {
        public required string DeviceId { get; init; }
        public required Channel<bool> Signal { get; init; }
        public required CancellationTokenSource Cts { get; init; }
    }

    private readonly ConcurrentDictionary<Client, byte> _clients = new();
    private readonly IAudioService _audio;
    private readonly DisplayComposer _composer;
    private readonly ILogger _log;

    public EventHub(IAudioService audio, DisplayComposer composer, DataStore data, PairedStore paired, ILogger log)
    {
        _audio = audio; _composer = composer; _log = log;
        _audio.Changed += OnChanged;
        data.DevicesReloaded += (_, _) => OnChanged(null, EventArgs.Empty);
        paired.Revoked += Disconnect;
    }

    private void OnChanged(object? sender, EventArgs e)
    {
        // Bounded(1)+DropWrite: any number of changes while a client is busy collapse into one pending signal.
        foreach (var c in _clients.Keys) c.Signal.Writer.TryWrite(true);
    }

    /// <summary>Closes every socket of a revoked device.</summary>
    public void Disconnect(string deviceId)
    {
        foreach (var c in _clients.Keys.Where(c => c.DeviceId == deviceId)) CancelQuietly(c);
    }

    public async Task HandleAsync(HttpContext ctx, string deviceId, CancellationToken appStopping)
    {
        using var ws = await ctx.WebSockets.AcceptWebSocketAsync();
        var spanish = DisplayComposer.IsSpanish(ctx.Request.Headers.AcceptLanguage);
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ctx.RequestAborted, appStopping);
        var client = new Client
        {
            DeviceId = deviceId,
            Signal = Channel.CreateBounded<bool>(new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropWrite }),
            Cts = cts,
        };
        _clients[client] = 0;
        try
        {
            // Register before the first state so a change in between is not lost.
            await SendStateAsync(ws, spanish, cts.Token);
            var reader = ReceiveLoopAsync(ws, cts);
            await SendLoopAsync(ws, client, spanish, cts.Token);
            cts.Cancel();
            await reader;
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException or ObjectDisposedException) { }
        catch (Exception ex) { _log.LogDebug(ex, "events socket ended"); }
        finally
        {
            _clients.TryRemove(client, out _);
            if (ws.State is WebSocketState.Open or WebSocketState.CloseReceived)
            {
                try
                {
                    using var closeTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                    await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, null, closeTimeout.Token);
                }
                catch { /* peer already gone */ }
            }
        }
    }

    private async Task SendLoopAsync(WebSocket ws, Client client, bool spanish, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && ws.State == WebSocketState.Open)
        {
            using var wait = CancellationTokenSource.CreateLinkedTokenSource(ct);
            wait.CancelAfter(PingEvery);
            try
            {
                if (!await client.Signal.Reader.WaitToReadAsync(wait.Token)) return;
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                await ws.SendAsync(Ping, WebSocketMessageType.Text, true, ct); // idle for 30 s
                continue;
            }
            client.Signal.Reader.TryRead(out _);
            await SendStateAsync(ws, spanish, ct);
            // Coalescing: whatever happens during this pause becomes a single next message.
            await Task.Delay(MinInterval, ct);
        }
    }

    // Clients send nothing; we only read to notice the close handshake.
    private static async Task ReceiveLoopAsync(WebSocket ws, CancellationTokenSource cts)
    {
        var buf = new byte[256];
        try
        {
            while (ws.State == WebSocketState.Open)
            {
                var r = await ws.ReceiveAsync(buf, cts.Token);
                if (r.MessageType == WebSocketMessageType.Close) break;
            }
        }
        catch { /* socket dropped */ }
        cts.Cancel();
    }

    private async Task SendStateAsync(WebSocket ws, bool spanish, CancellationToken ct)
    {
        var json = JsonSerializer.SerializeToUtf8Bytes(new { type = "state", state = _composer.BuildState(spanish) }, JsonOptions.Compact);
        await ws.SendAsync(json, WebSocketMessageType.Text, true, ct);
    }

    public void Dispose()
    {
        _audio.Changed -= OnChanged;
        foreach (var c in _clients.Keys) CancelQuietly(c);
    }

    // The handler may dispose its CTS at the same moment (socket already closed).
    private static void CancelQuietly(Client c)
    {
        try { c.Cts.Cancel(); } catch (ObjectDisposedException) { }
    }
}

public static class JsonOptions
{
    public static readonly JsonSerializerOptions Compact = new(JsonSerializerDefaults.Web);
}
