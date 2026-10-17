using System.Security.Cryptography;
using System.Text.Json;
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
        _reloadTimer = new System.Threading.Timer(_ => ReloadDevices(), null, Timeout.Infinite, Timeout.Infinite);
        ReloadDevices();
        try
        {
            _watcher = new FileSystemWatcher(dir, "devices.json") { NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName | NotifyFilters.Size };
            // Editors fire several events per save; debounce so we read the final content.
            void Kick(object? s, EventArgs e) => _reloadTimer.Change(300, Timeout.Infinite);
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
            try
            {
                var cfg = JsonSerializer.Deserialize<ServerConfig>(File.ReadAllText(path), Json.Options) ?? new ServerConfig();
                var changed = false;
                if (string.IsNullOrWhiteSpace(cfg.ServerId)) { cfg.ServerId = NewServerId(); changed = true; }
                if (cfg.Port is < 1 or > 65535) cfg.Port = 7799;
                cfg.Presets ??= new();
                if (changed) Json.WriteAtomic(path, cfg);
                return cfg;
            }
            catch (Exception ex)
            {
                // Never overwrite a file the user may be hand-editing: keep it aside.
                _log.LogError(ex, "config.json is invalid; moved to config.json.bad and recreated");
                File.Move(path, path + ".bad", true);
            }
        }
        var fresh = new ServerConfig { ServerId = NewServerId() };
        Json.WriteAtomic(path, fresh);
        return fresh;
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
        }
        _log.LogWarning("devices.json could not be read (locked); keeping the previous version");
    }

    public void Dispose() { _watcher?.Dispose(); _reloadTimer.Dispose(); }
}
