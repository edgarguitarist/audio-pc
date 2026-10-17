using System.Reflection;

namespace AudioPc.Server.Api;

/// <summary>Serves the web panel from embedded resources (csproj embeds wwwroot/** with
/// LogicalName "wwwroot/..."), so it works inside a single-file exe.</summary>
public static class StaticFiles
{
    private static readonly Assembly Asm = typeof(StaticFiles).Assembly;
    private static readonly Dictionary<string, string> Resources = Asm.GetManifestResourceNames()
        .Where(n => n.StartsWith("wwwroot/", StringComparison.OrdinalIgnoreCase) || n.StartsWith("wwwroot\\", StringComparison.OrdinalIgnoreCase))
        .ToDictionary(n => n.Replace('\\', '/')["wwwroot/".Length..], n => n, StringComparer.OrdinalIgnoreCase);

    private const string Placeholder = """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Audio PC</title></head><body style="font-family:system-ui;background:#111;color:#eee;display:grid;place-items:center;height:100vh;margin:0">
        <main><h1>Audio PC</h1><p>The server is running, but this build has no web panel.</p></main></body></html>
        """;

    public static string ContentType(string file) => Path.GetExtension(file).ToLowerInvariant() switch
    {
        ".html" => "text/html; charset=utf-8",
        ".js" or ".mjs" => "text/javascript; charset=utf-8",
        ".css" => "text/css; charset=utf-8",
        ".svg" => "image/svg+xml",
        ".png" => "image/png",
        ".jpg" or ".jpeg" => "image/jpeg",
        ".webp" => "image/webp",
        ".ico" => "image/x-icon",
        ".webmanifest" => "application/manifest+json",
        ".json" => "application/json; charset=utf-8",
        ".txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    };

    public static IResult Serve(string requestPath)
    {
        var rel = requestPath.TrimStart('/');
        if (rel.Length == 0) rel = "index.html";
        if (!Resources.TryGetValue(rel, out var name))
        {
            if (rel == "index.html" && Resources.Count == 0) return Results.Content(Placeholder, "text/html; charset=utf-8");
            return Results.Json(new { error = "not_found", message = "No such file." }, statusCode: 404);
        }
        return new ResourceResult(name, ContentType(rel), rel == "index.html");
    }

    private sealed class ResourceResult(string name, string contentType, bool noCache) : IResult
    {
        public async Task ExecuteAsync(HttpContext ctx)
        {
            await using var s = Asm.GetManifestResourceStream(name)!;
            ctx.Response.ContentType = contentType;
            ctx.Response.Headers.CacheControl = noCache ? "no-cache" : "public, max-age=300";
            ctx.Response.ContentLength = s.Length;
            if (HttpMethods.IsHead(ctx.Request.Method)) return;
            await s.CopyToAsync(ctx.Response.Body, ctx.RequestAborted);
        }
    }
}
