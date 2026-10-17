using System.Text.Json;
using System.Text.Json.Serialization;

namespace AudioPc.Server.Data;

public sealed class ServerConfig
{
    public string ServerId { get; set; } = "";
    public int Port { get; set; } = 7799;
    public string? Name { get; set; }
    public string? LegacyToken { get; set; }
    public Dictionary<string, string> Presets { get; set; } = new();
    /// <summary>Extra Host header names accepted besides IPs, localhost and the PC name (DNS-rebinding guard).</summary>
    public List<string>? AllowedHosts { get; set; }
    /// <summary>
    /// Off (default): finding the server is enough to pair. On: a 6-digit code shown on the PC
    /// must be typed on the client — an optional second factor. Toggled from the tray menu.
    /// </summary>
    public bool RequirePairingCode { get; set; }
    /// <summary>
    /// On (default, also when missing): volume/mute changes made through the API also show the
    /// native Windows volume flyout. Toggled from the tray menu.
    /// </summary>
    public bool ShowVolumeOsd { get; set; } = true;
}

public sealed class PairedDevice
{
    public string DeviceId { get; set; } = "";
    public string DeviceName { get; set; } = "";
    /// <summary>Hex SHA-256 of the token; the token itself is never stored.</summary>
    public string TokenHash { get; set; } = "";
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset LastSeen { get; set; }
}

public sealed class DeviceEntry
{
    public string? Match { get; set; }
    public string? Name { get; set; }
    public string? Detail { get; set; }
    public string? Kind { get; set; }
    public string? Image { get; set; }
}

public sealed class DevicesConfig
{
    public List<string?> Hide { get; set; } = new();
    public List<DeviceEntry?> Devices { get; set; } = new();
}

public static class Json
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    /// <summary>Atomic write: temp file in the same directory, then replace, so a crash or a
    /// concurrent reader never sees a half-written file.</summary>
    public static void WriteAtomic(string path, object value)
    {
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(value, Options));
        if (File.Exists(path)) File.Replace(tmp, path, null);
        else File.Move(tmp, path);
    }
}
