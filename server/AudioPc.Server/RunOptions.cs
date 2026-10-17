namespace AudioPc.Server;

/// <summary>Command-line options (see Program.cs for the flag documentation).</summary>
internal sealed class RunOptions
{
    public bool Dev { get; init; }
    public bool NoTray { get; init; }
    public bool FakeAudio { get; init; }
    /// <summary>Dev-only escape hatch to test just the DNS-SD registration (UDP stays off).</summary>
    public bool DevMdns { get; init; }
    public string DataDir { get; init; } = "";
    public int? Port { get; init; }

    public static RunOptions Parse(string[] args)
    {
        bool dev = false, noTray = false, fake = false, mdns = false;
        string? dataDir = null;
        int? port = null;
        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i].ToLowerInvariant())
            {
                case "--dev": dev = true; break;
                case "--no-tray": noTray = true; break;
                case "--fake-audio": fake = true; break;
                case "--dev-mdns": mdns = true; break;
                case "--data-dir" when i + 1 < args.Length: dataDir = args[++i]; break;
                case "--port" when i + 1 < args.Length && int.TryParse(args[i + 1], out var p) && p is > 0 and < 65536:
                    port = p; i++; break;
            }
        }
        var appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        dataDir ??= Path.Combine(appData, dev ? "AudioPC-dev" : "AudioPC");
        return new RunOptions { Dev = dev, NoTray = noTray, FakeAudio = fake, DevMdns = mdns, DataDir = Path.GetFullPath(dataDir), Port = port };
    }
}
