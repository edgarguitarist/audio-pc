using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging;

namespace AudioPc.Server.Data;

/// <summary>Owns the data directory: config.json, devices.json (hot reloaded) and images/.</summary>
public sealed class DataStore : IDisposable
{
    private readonly ILogger _log;
    private readonly FileSystemWatcher? _watcher;
    private readonly System.Threading.Timer _reloadTimer;
    private volatile DevicesConfig _devices = new();

    public string Dir { get; }
    public string ImagesDir { get; }
    public string DevicesPath { get; }
    public ServerConfig Config { get; }
    public DevicesConfig Devices => _devices;

    /// <summary>Raised after devices.json is (re)loaded successfully.</summary>
    public event EventHandler? DevicesReloaded;

    public DataStore(string dir, ILogger log)
    {
        _log = log;
        Dir = dir;
        ImagesDir = Path.Combine(dir, "images");
        DevicesPath = Path.Combine(dir, "devices.json");
        Directory.CreateDirectory(ImagesDir);
        Config = LoadConfig();
        // Timer callbacks run on the thread pool: an exception escaping one kills the process.
        _reloadTimer = new System.Threading.Timer(_ =>
        {
            try { ReloadDevices(); }
            catch (Exception ex) { _log.LogError(ex, "devices.json reload failed; keeping the previous version"); }
        }, null, Timeout.Infinite, Timeout.Infinite);
        ReloadDevices();
        try
        {
            _watcher = new FileSystemWatcher(dir, "devices.json") { NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName | NotifyFilters.Size };
            // Editors fire several events per save; debounce so we read the final content.
            // Watcher events also arrive on pool threads, possibly after Dispose.
            void Kick(object? s, EventArgs e)
            {
                try { _reloadTimer.Change(300, Timeout.Infinite); } catch (ObjectDisposedException) { }
            }
            _watcher.Changed += Kick; _watcher.Created += Kick; _watcher.Renamed += Kick;
            _watcher.EnableRaisingEvents = true;
        }
        catch (Exception ex) { _log.LogWarning(ex, "devices.json watcher unavailable; restart to apply changes"); }
    }

    private ServerConfig LoadConfig()
    {
        var path = Path.Combine(Dir, "config.json");
        if (File.Exists(path))
        {
            string text;
            JsonObject root;
            ServerConfig cfg;
            try
            {
                text = File.ReadAllText(path);
                root = JsonNode.Parse(text, documentOptions: new JsonDocumentOptions { CommentHandling = JsonCommentHandling.Skip, AllowTrailingCommas = true }) as JsonObject
                    ?? throw new JsonException("config.json must contain a JSON object");
                cfg = root.Deserialize<ServerConfig>(Json.Options) ?? new ServerConfig();
            }
            catch (JsonException ex)
            {
                // Only an unparseable file is moved aside (never overwrite what the user may be hand-editing).
                _log.LogError(ex, "config.json is invalid; moved to config.json.bad and recreated");
                try { File.Move(path, path + ".bad", true); }
                catch (Exception mv) { _log.LogError(mv, "could not move config.json aside; using defaults for this run"); return new ServerConfig { ServerId = NewServerId() }; }
                return WriteFresh(path);
            }
            // IOException / UnauthorizedAccessException while reading propagate: refusing to start
            // is better than silently replacing a config we could not read.

            if (cfg.Port is < 1 or > 65535) cfg.Port = 7799;
            cfg.Presets ??= new();
            if (string.IsNullOrWhiteSpace(cfg.ServerId))
            {
                // Hand-written configs (e.g. migrated from the old Node server) may lack serverId:
                // add just that field to the user's JSON so every other field, known or not, survives.
                cfg.ServerId = NewServerId();
                root["serverId"] = cfg.ServerId;
                try { WriteTextAtomic(path, root.ToJsonString(Json.Options)); }
                catch (Exception ex)
                {
                    // The config is fine; only persisting the new id failed (read-only file, ...).
                    // Keep running with it in memory rather than discarding the user's settings.
                    _log.LogError(ex, "could not add serverId to config.json; using a temporary id for this run (clients will see a new server id after a restart)");
                }
            }
            return cfg;
        }
        return WriteFresh(path);
    }

    private ServerConfig WriteFresh(string path)
    {
        var fresh = new ServerConfig { ServerId = NewServerId() };
        try { Json.WriteAtomic(path, fresh); }
        catch (Exception ex) { _log.LogError(ex, "could not write config.json; using defaults for this run"); }
        return fresh;
    }

    private static void WriteTextAtomic(string path, string text)
    {
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, text);
        try { File.Replace(tmp, path, null); }
        catch
        {
            try { File.Delete(tmp); } catch { /* best effort */ }
            throw;
        }
    }

    private static string NewServerId() => Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();

    private void ReloadDevices()
    {
        if (!File.Exists(DevicesPath)) { _devices = new DevicesConfig(); return; }
        for (var attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                using var fs = new FileStream(DevicesPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                var cfg = JsonSerializer.Deserialize<DevicesConfig>(fs, Json.Options) ?? new DevicesConfig();
                cfg.Hide ??= new(); cfg.Devices ??= new();
                _devices = cfg;
                _log.LogInformation("devices.json loaded ({Count} entries, {Hide} hidden patterns)", cfg.Devices.Count, cfg.Hide.Count);
                DevicesReloaded?.Invoke(this, EventArgs.Empty);
                return;
            }
            catch (JsonException ex)
            {
                _log.LogError("devices.json is invalid, keeping the previous version: {Message}", ex.Message);
                return;
            }
            catch (IOException) { Thread.Sleep(100); } // editor still holds the file
            catch (UnauthorizedAccessException ex)
            {
                _log.LogWarning("devices.json cannot be read ({Message}); keeping the previous version", ex.Message);
                return;
            }
        }
        _log.LogWarning("devices.json could not be read (locked); keeping the previous version");
    }

    public void Dispose() { _watcher?.Dispose(); _reloadTimer.Dispose(); }
}
