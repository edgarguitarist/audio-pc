using System.Net;
using System.Text.Json;
using AudioPc.Server.Audio;
using AudioPc.Server.Data;
using Microsoft.Extensions.Logging;

namespace AudioPc.Server.Api;

/// <summary>Builds the ASP.NET Core app: API.md endpoints, auth, legacy routes, static panel.</summary>
public sealed class ApiHost
{
    private const string DeviceKey = "audiopc.device";
    private static readonly string Version = typeof(ApiHost).Assembly.GetName().Version is { } v ? $"{v.Major}.{v.Minor}.{v.Build}" : "1.0.0";

    private readonly IAudioService _audio;
    private readonly DataStore _data;
    private readonly PairedStore _paired;
    private readonly PairingService _pairing;
    private readonly DisplayComposer _composer;
    private readonly EventHub _hub;
    private readonly ILogger _log;

    public ApiHost(IAudioService audio, DataStore data, PairedStore paired, PairingService pairing,
        DisplayComposer composer, EventHub hub, ILogger log)
    {
        _audio = audio; _data = data; _paired = paired; _pairing = pairing; _composer = composer; _hub = hub; _log = log;
    }

    public static string AppVersion => Version;
    public string ServerName => string.IsNullOrWhiteSpace(_data.Config.Name) ? Environment.MachineName : _data.Config.Name!;

    public object Info() => new { service = "audio-pc", apiVersion = 1, id = _data.Config.ServerId, name = ServerName, version = Version };

    public WebApplication Build(int port, bool loopbackOnly, ILoggerFactory loggers)
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions { ContentRootPath = AppContext.BaseDirectory });
        // One shared logger factory (console + file) for our code and for ASP.NET Core.
        builder.Logging.ClearProviders();
        builder.Services.AddSingleton(loggers);
        builder.WebHost.ConfigureKestrel(k =>
        {
            k.AddServerHeader = false;
            k.Limits.MaxRequestBodySize = 16 * 1024;
            if (loopbackOnly) k.Listen(IPAddress.Loopback, port);
            else k.ListenAnyIP(port);
        });
        builder.Services.ConfigureHttpJsonOptions(o => o.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase);

        var app = builder.Build();
        app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.Zero });
        app.Use(Auth);
        MapRoutes(app);
        return app;
    }

    // ---- auth ---------------------------------------------------------------------------------

    private static bool NeedsAuth(string path)
    {
        if (path.StartsWith("/audio/", StringComparison.OrdinalIgnoreCase)) return true;
        if (!path.StartsWith("/api/", StringComparison.OrdinalIgnoreCase)) return false;
        return !(path.Equals("/api/info", StringComparison.OrdinalIgnoreCase)
              || path.StartsWith("/api/pair/", StringComparison.OrdinalIgnoreCase)
              || path.StartsWith("/api/images/", StringComparison.OrdinalIgnoreCase));
    }

    private async Task Auth(HttpContext ctx, RequestDelegate next)
    {
        ctx.Response.Headers.XContentTypeOptions = "nosniff";
        if (NeedsAuth(ctx.Request.Path.Value ?? ""))
        {
            var device = _paired.Authenticate(ExtractToken(ctx.Request));
            if (device == null)
            {
                await Err(401, "unauthorized", "Missing or invalid token.").ExecuteAsync(ctx);
                return;
            }
            ctx.Items[DeviceKey] = device;
        }
        try { await next(ctx); }
        catch (BadHttpRequestException ex) when (!ctx.Response.HasStarted)
        {
            // Oversized body (16 KB cap) and malformed requests: answer in our error format, no stack trace in the log.
            await Err(ex.StatusCode, "bad_request", ex.Message).ExecuteAsync(ctx);
        }
    }

    private static string? ExtractToken(HttpRequest r)
    {
        var h = r.Headers.Authorization.ToString();
        if (h.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)) return h[7..].Trim();
        return r.Query["token"].FirstOrDefault();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static IResult Err(int status, string code, string message) =>
        Results.Json(new { error = code, message }, statusCode: status);

    private static IResult LegacyErr(int status, string error) => Results.Json(new { error }, statusCode: status);

    private IResult Guard(Func<IResult> action, bool legacy = false)
    {
        try { return action(); }
        catch (KeyNotFoundException) { return legacy ? LegacyErr(500, "ERR:notfound") : Err(404, "not_found", "No such output."); }
        catch (Exception ex)
        {
            _log.LogError(ex, "audio operation failed");
            return legacy ? LegacyErr(500, ex.Message) : Err(500, "audio_error", ex.Message);
        }
    }

    private static async Task<JsonElement?> ReadBody(HttpRequest r)
    {
        try
        {
            using var doc = await JsonDocument.ParseAsync(r.Body);
            return doc.RootElement.ValueKind == JsonValueKind.Object ? doc.RootElement.Clone() : null;
        }
        catch (JsonException) { return null; }
    }

    private static string? Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.String ? p.GetString() : null;

    private static int? Num(JsonElement e, string name) =>
        e.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.Number && p.TryGetDouble(out var d) && !double.IsNaN(d)
            ? (int)Math.Round(Math.Clamp(d, -1000, 1000)) : null;

    private static bool? ParseMute(string? s) => s?.ToLowerInvariant() switch { "on" => true, "off" => false, _ => null };

    private object Vol(VolumeState v) => new { volume = v.Volume, muted = v.Muted };
    private object LegacyVol(VolumeState v) => new { ok = true, volume = v.Volume, muted = v.Muted };

    private IResult State(HttpContext ctx) =>
        Guard(() => Results.Json(_composer.BuildState(DisplayComposer.IsSpanish(ctx.Request.Headers.AcceptLanguage))));

    private string? FindByName(string name) =>
        _audio.GetOutputs().FirstOrDefault(o => o.RawName.Contains(name, StringComparison.OrdinalIgnoreCase))?.Id;

    // ---- routes -------------------------------------------------------------------------------

    private void MapRoutes(WebApplication app)
    {
        app.MapGet("/api/info", () => Results.Json(Info()));
        app.MapGet("/health", () => Results.Json(new { ok = true }));

        // Pairing
        app.MapPost("/api/pair/request", async (HttpContext ctx) =>
        {
            var body = await ReadBody(ctx.Request);
            var deviceId = body is { } b ? Str(b, "deviceId")?.Trim() : null;
            var deviceName = body is { } b2 ? Str(b2, "deviceName")?.Trim() : null;
            if (string.IsNullOrEmpty(deviceId) || string.IsNullOrEmpty(deviceName) || deviceId.Length > 128 || deviceName.Length > 64)
                return Err(400, "bad_request", "deviceName (max 64) and deviceId (max 128) are required.");
            var ip = ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown";
            var pending = _pairing.Request(ip, deviceId, deviceName);
            if (pending == null) return Err(429, "forbidden", "Too many pairing requests; try again in a minute.");
            _log.LogInformation("pairing requested by {Device} ({Ip})", deviceName, ip);
            return Results.Json(new { requestId = pending.RequestId, expiresIn = PairingService.ExpiresInSeconds });
        });

        app.MapPost("/api/pair/confirm", async (HttpContext ctx) =>
        {
            var body = await ReadBody(ctx.Request);
            var requestId = body is { } b ? Str(b, "requestId") : null;
            var code = body is { } b2 ? Str(b2, "code") : null;
            if (string.IsNullOrEmpty(requestId) || string.IsNullOrEmpty(code) || code.Length > 16 || requestId.Length > 64)
                return Err(400, "bad_request", "requestId and code are required.");
            switch (_pairing.Confirm(requestId, code, out var token, out var pairing))
            {
                case ConfirmResult.Ok:
                    _log.LogInformation("paired {Device}", pairing!.DeviceName);
                    return Results.Json(new { token, serverId = _data.Config.ServerId, serverName = ServerName });
                case ConfirmResult.BadCode: return Err(403, "pairing_bad_code", "Wrong code.");
                case ConfirmResult.Locked: return Err(423, "pairing_locked", "Too many wrong codes; request a new one.");
                default: return Err(410, "pairing_expired", "Unknown or expired pairing request.");
            }
        });

        // State and commands
        app.MapGet("/api/state", (HttpContext ctx) => State(ctx));

        app.MapPost("/api/output", async (HttpContext ctx) =>
        {
            var body = await ReadBody(ctx.Request);
            if (body is not { } b) return Err(400, "bad_request", "JSON object body required.");
            var id = Str(b, "id");
            var match = Str(b, "match");
            if (string.IsNullOrEmpty(id) && string.IsNullOrEmpty(match))
                return Err(400, "bad_request", "Provide \"id\" or \"match\".");
            return Guard(() =>
            {
                var target = !string.IsNullOrEmpty(id) ? id : FindByName(match!);
                if (target == null) return Err(404, "not_found", "No output matches.");
                _audio.SetDefaultOutput(target);
                return State(ctx);
            });
        });

        app.MapPost("/api/volume", async (HttpContext ctx) =>
        {
            var body = await ReadBody(ctx.Request);
            if (body is not { } b) return Err(400, "bad_request", "JSON object body required.");
            var level = Num(b, "level");
            var delta = Num(b, "delta");
            if (level == null && delta == null) return Err(400, "bad_request", "Provide numeric \"level\" or \"delta\".");
            return Guard(() => Results.Json(Vol(level != null
                ? _audio.SetVolume(Math.Clamp(level.Value, 0, 100))
                : _audio.ChangeVolume(delta!.Value))));
        });

        app.MapPost("/api/mute", async (HttpContext ctx) =>
        {
            var body = await ReadBody(ctx.Request);
            var state = body is { } b ? Str(b, "state")?.ToLowerInvariant() : null;
            if (state is not ("on" or "off" or "toggle")) return Err(400, "bad_request", "\"state\" must be on, off or toggle.");
            return Guard(() => Results.Json(Vol(_audio.SetMute(ParseMute(state)))));
        });

        app.MapGet("/api/images/{file}", (HttpContext ctx, string file) =>
        {
            if (!ImageFile.IsValid(file)) return Err(404, "not_found", "No such image.");
            var path = Path.GetFullPath(Path.Combine(_data.ImagesDir, file));
            if (!path.StartsWith(Path.GetFullPath(_data.ImagesDir) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) || !File.Exists(path))
                return Err(404, "not_found", "No such image.");
            ctx.Response.Headers.CacheControl = "public, max-age=31536000, immutable";
            return Results.File(path, StaticFiles.ContentType(file));
        });

        app.MapGet("/api/events", async (HttpContext ctx) =>
        {
            if (!ctx.WebSockets.IsWebSocketRequest) { await Err(400, "bad_request", "WebSocket upgrade required.").ExecuteAsync(ctx); return; }
            var deviceId = ((PairedDevice)ctx.Items[DeviceKey]!).DeviceId;
            await _hub.HandleAsync(ctx, deviceId, ctx.RequestServices.GetRequiredService<IHostApplicationLifetime>().ApplicationStopping);
        });

        MapLegacy(app);

        // Static panel; anything else under /api or /audio is a JSON 404.
        // An explicit catch-all (not MapFallback): MapFallback skips paths with a file extension such as /app.js.
        app.MapMethods("/{**path}", new[] { "GET", "HEAD", "POST", "PUT", "DELETE" }, (HttpContext ctx) =>
        {
            var path = ctx.Request.Path.Value ?? "/";
            if (path.StartsWith("/api/", StringComparison.OrdinalIgnoreCase) || path.StartsWith("/audio/", StringComparison.OrdinalIgnoreCase))
                return Err(404, "not_found", "No such endpoint.");
            if (!HttpMethods.IsGet(ctx.Request.Method) && !HttpMethods.IsHead(ctx.Request.Method))
                return Err(404, "not_found", "No such endpoint.");
            return StaticFiles.Serve(path);
        });
    }

    private void MapLegacy(WebApplication app)
    {
        app.MapGet("/audio/list", () => Guard(() =>
        {
            var devices = _audio.GetOutputs().Select((o, i) => new Dictionary<string, object>
            {
                // PascalCase keys are what the old PowerShell-based server emitted.
                ["Index"] = i + 1, ["Default"] = o.IsDefault, ["Name"] = o.RawName, ["ID"] = o.Id,
            }).ToList();
            var v = _audio.GetVolume();
            return Results.Json(new Dictionary<string, object> { ["devices"] = devices, ["volume"] = v.Volume, ["muted"] = v.Muted });
        }, legacy: true));

        app.MapGet("/audio/volume", () => Guard(() => Results.Json(LegacyVol(_audio.GetVolume())), legacy: true));

        app.MapPost("/audio/volume", (HttpContext ctx) =>
        {
            var q = ctx.Request.Query;
            var isLevel = q.ContainsKey("level");
            var raw = isLevel ? q["level"].ToString() : q["delta"].ToString();
            if ((!isLevel && !q.ContainsKey("delta")) || !double.TryParse(raw, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var n) || double.IsNaN(n) || double.IsInfinity(n))
                return LegacyErr(400, "falta level o delta numérico");
            var i = (int)Math.Round(Math.Clamp(n, -1000, 1000));
            return Guard(() => Results.Json(LegacyVol(isLevel ? _audio.SetVolume(Math.Clamp(i, 0, 100)) : _audio.ChangeVolume(i))), legacy: true);
        });

        app.MapPost("/audio/mute", (HttpContext ctx) =>
        {
            var state = ctx.Request.Query["state"].FirstOrDefault();
            if (string.IsNullOrEmpty(state)) state = "toggle";
            state = state.ToLowerInvariant();
            if (state is not ("on" or "off" or "toggle")) return LegacyErr(400, "state: on, off o toggle");
            return Guard(() => Results.Json(LegacyVol(_audio.SetMute(ParseMute(state)))), legacy: true);
        });

        app.MapPost("/audio/set", (HttpContext ctx) =>
        {
            var name = ctx.Request.Query["name"].FirstOrDefault();
            var id = ctx.Request.Query["id"].FirstOrDefault();
            if (string.IsNullOrEmpty(name) && string.IsNullOrEmpty(id)) return LegacyErr(400, "preset/nombre desconocido: set");
            return SetLegacy(id, name);
        });

        app.MapPost("/audio/{preset}", (string preset) =>
        {
            var name = _data.Config.Presets.FirstOrDefault(kv => kv.Key.Equals(preset, StringComparison.OrdinalIgnoreCase)).Value;
            if (string.IsNullOrEmpty(name)) return LegacyErr(400, $"preset/nombre desconocido: {preset}");
            return SetLegacy(null, name);
        });
    }

    private IResult SetLegacy(string? id, string? name) => Guard(() =>
    {
        var outputs = _audio.GetOutputs();
        var target = !string.IsNullOrEmpty(id)
            ? outputs.FirstOrDefault(o => o.Id == id)
            : outputs.FirstOrDefault(o => o.RawName.Contains(name!, StringComparison.OrdinalIgnoreCase));
        if (target == null) return LegacyErr(500, "ERR:notfound");
        _audio.SetDefaultOutput(target.Id);
        return Results.Json(new { ok = true, device = target.RawName });
    }, legacy: true);
}
