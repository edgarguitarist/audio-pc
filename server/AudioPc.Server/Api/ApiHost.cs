using System.Net;
using System.Text.Json;
using AudioPc.Server.Audio;
using AudioPc.Server.Data;
using Microsoft.Extensions.Logging;
using Microsoft.Net.Http.Headers;

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
    private readonly HostGuard _hosts;
    private readonly AuthThrottle _authThrottle = new();

    // The panel is a single file with inline script/style; everything else must come from us.
    private const string PanelCsp = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
        + "script-src 'self' 'unsafe-inline'; connect-src 'self' ws: wss:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    public ApiHost(IAudioService audio, DataStore data, PairedStore paired, PairingService pairing,
        DisplayComposer composer, EventHub hub, ILogger log)
    {
        _audio = audio; _data = data; _paired = paired; _pairing = pairing; _composer = composer; _hub = hub; _log = log;
        _hosts = new HostGuard(() => ((IEnumerable<string?>?)_data.Config.AllowedHosts ?? []).Append(ServerName), log);
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
        app.Use(Guardrails);
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

    private static bool IsApiOrLegacy(string path) =>
        path.StartsWith("/api/", StringComparison.OrdinalIgnoreCase) || path.StartsWith("/audio/", StringComparison.OrdinalIgnoreCase)
        || path.Equals("/health", StringComparison.OrdinalIgnoreCase);

    /// <summary>Host check, response headers and Content-Type check — before auth and routing.</summary>
    private async Task Guardrails(HttpContext ctx, RequestDelegate next)
    {
        var path = ctx.Request.Path.Value ?? "";
        var h = ctx.Response.Headers;
        h.XContentTypeOptions = "nosniff";
        if (!_hosts.IsAllowed(ctx.Request.Host.Host))
        {
            h.CacheControl = "no-store";
            await Err(421, "misdirected_request", "Unknown Host header; add it to \"allowedHosts\" in config.json if it is a name of this PC.").ExecuteAsync(ctx);
            return;
        }
        if (IsApiOrLegacy(path))
        {
            // Tokens and device state must not linger in browser or proxy caches; the image
            // endpoint overrides this with its own immutable policy.
            h.CacheControl = "no-store";
        }
        else
        {
            h.ContentSecurityPolicy = PanelCsp;
            h.XFrameOptions = "DENY";
            h["Referrer-Policy"] = "no-referrer";
        }
        // Every JSON-body endpoint requires application/json. That is not a CORS "simple" content
        // type, so a cross-origin page cannot POST without a preflight, and we never answer one.
        // Legacy /audio/* POSTs take query parameters and keep working without the header.
        if (HttpMethods.IsPost(ctx.Request.Method) && path.StartsWith("/api/", StringComparison.OrdinalIgnoreCase) && !IsJson(ctx.Request.ContentType))
        {
            await Err(415, "unsupported_media_type", "Content-Type must be application/json.").ExecuteAsync(ctx);
            return;
        }
        await next(ctx);
    }

    private static bool IsJson(string? contentType) =>
        MediaTypeHeaderValue.TryParse(contentType, out var mt) && mt.MediaType.Equals("application/json", StringComparison.OrdinalIgnoreCase);

    private static string RemoteIp(HttpContext ctx) => ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown";

    private async Task Auth(HttpContext ctx, RequestDelegate next)
    {
        if (NeedsAuth(ctx.Request.Path.Value ?? ""))
        {
            var ip = RemoteIp(ctx);
            if (_authThrottle.IsBlocked(ip))
            {
                await Err(429, "rate_limited", "Too many failed authentications; try again in a minute.").ExecuteAsync(ctx);
                return;
            }
            var device = _paired.Authenticate(ExtractToken(ctx.Request));
            if (device == null)
            {
                if (_authThrottle.RecordFailure(ip))
                    await Err(429, "rate_limited", "Too many clients failing authentication; try again in a minute.").ExecuteAsync(ctx);
                else
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
        var h = r.Headers.Authorization.ToString().Trim();
        if (h.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)) return h[7..].Trim();
        // The old Node server accepted the bare token ("Authorization: <token>"); Home Assistant
        // setups still send it that way.
        if (h.Length > 0) return h;
        return r.Query["token"].FirstOrDefault();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private const string PairingLockedMessage =
        "Pairing is disabled after too many wrong codes. On the PC, choose \"Allow pairing again\" in the Audio PC tray menu.";

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

    /// <summary>No Origin (native clients) is fine; otherwise its host:port must be the request's Host.</summary>
    private static bool OriginMatchesHost(HttpRequest r)
    {
        var origin = r.Headers.Origin.ToString();
        if (string.IsNullOrEmpty(origin)) return true;
        if (!Uri.TryCreate(origin, UriKind.Absolute, out var o) || (o.Scheme != "http" && o.Scheme != "https")) return false;
        var host = r.Host;
        if (!host.HasValue) return false;
        var hostPort = host.Port ?? (r.IsHttps ? 443 : 80);
        return string.Equals(o.Host.Trim('[', ']'), host.Host.Trim('[', ']'), StringComparison.OrdinalIgnoreCase) && o.Port == hostPort;
    }

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
            var rawId = body is { } b ? Str(b, "deviceId") : null;
            var rawName = body is { } b2 ? Str(b2, "deviceName") : null;
            // Names end up in the log, the tray menu and a balloon: strip control/bidi characters
            // so a client cannot forge log lines or make the balloon read differently.
            var deviceId = rawId is { Length: <= 512 } ? TextSafety.Clean(rawId) : "";
            var deviceName = rawName is { Length: <= 512 } ? TextSafety.Clean(rawName) : "";
            if (deviceId.Length == 0 || deviceName.Length == 0 || deviceId.Length > 128 || deviceName.Length > 64)
                return Err(400, "bad_request", "deviceName (max 64) and deviceId (max 128) are required.");
            var ip = RemoteIp(ctx);
            switch (_pairing.Request(ip, deviceId, deviceName, out var pending))
            {
                case RequestStatus.Ok:
                    _log.LogInformation("pairing requested by {Device} ({Ip})", deviceName, ip);
                    return Results.Json(new { requestId = pending!.RequestId, expiresIn = PairingService.ExpiresInSeconds });
                case RequestStatus.GloballyLocked:
                    return Err(423, "pairing_locked", PairingLockedMessage);
                case RequestStatus.TooManyPendingForIp:
                    return Err(429, "rate_limited", "Too many pending pairing requests from this address; finish one or wait for it to expire.");
                case RequestStatus.TooManyPending:
                    return Err(429, "rate_limited", "Too many pending pairing requests; try again in a couple of minutes.");
                case RequestStatus.TooManyClients:
                    return Err(429, "rate_limited", "Too many clients are pairing; try again in a minute.");
                default:
                    return Err(429, "rate_limited", "Too many pairing requests; try again in a minute.");
            }
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
                case ConfirmResult.GloballyLocked: return Err(423, "pairing_locked", PairingLockedMessage);
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
            // Browsers do not apply CORS to WebSockets; the Origin header is the only sign that
            // another site opened this socket (cross-site WebSocket hijacking).
            if (!OriginMatchesHost(ctx.Request)) { await Err(403, "forbidden", "Cross-origin WebSocket refused.").ExecuteAsync(ctx); return; }
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
