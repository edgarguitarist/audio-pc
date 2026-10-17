using System.Runtime.InteropServices;
using AudioPc.Server.Api;
using AudioPc.Server.Audio;
using AudioPc.Server.Data;
using AudioPc.Server.Discovery;
using AudioPc.Server.Fake;
using AudioPc.Server.Tray;
using Microsoft.Extensions.Logging;

namespace AudioPc.Server;

// Audio PC server host: HTTP API + web panel (Kestrel, background threads) and a tray icon
// (WinForms, main thread).
//
// Flags:
//   --dev            data dir %APPDATA%\AudioPC-dev, port 7800, binds 127.0.0.1 ONLY, UDP discovery and
//                    mDNS off (non-loopback binds trigger a Windows Firewall prompt), pairing code
//                    printed to the console and log, separate single-instance mutex.
//   --no-tray        no NotifyIcon (headless, for tests/CI); Ctrl+C stops it.
//   --fake-audio     in-memory FakeAudioService instead of Windows Core Audio.
//   --data-dir PATH  override the data directory.
//   --port N         override the HTTP port (default: config.json "port", 7799).
//   --dev-mdns       only with --dev: also register the mDNS service (nothing else leaves loopback).
internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        var o = RunOptions.Parse(args);
        if (o.NoTray || o.Dev) AttachParentConsole();

        using var mutex = new Mutex(true, o.Dev ? @"Local\AudioPC-Server-dev" : @"Local\AudioPC-Server", out var first);
        if (!first) return 0;

        Directory.CreateDirectory(o.DataDir);
        using var loggers = LoggerFactory.Create(b =>
        {
            b.SetMinimumLevel(LogLevel.Information).AddFilter("Microsoft", LogLevel.Warning);
            if (o.NoTray || o.Dev) b.AddSimpleConsole(c => c.SingleLine = true);
            b.AddProvider(new FileLoggerProvider(Path.Combine(o.DataDir, "audiopc.log")));
        });
        var log = loggers.CreateLogger("AudioPC");

        try { return Run(o, loggers, log); }
        catch (Exception ex)
        {
            log.LogCritical(ex, "fatal error");
            return 1;
        }
    }

    private static int Run(RunOptions o, ILoggerFactory loggers, ILogger log)
    {
        using var data = new DataStore(o.DataDir, loggers.CreateLogger("Data"));
        var paired = new PairedStore(o.DataDir, data.Config, loggers.CreateLogger("Paired"));
        var pairing = new PairingService(paired);
        using IAudioService audio = o.FakeAudio ? new FakeAudioService() : new CoreAudioService();
        var composer = new DisplayComposer(data, audio);
        using var hub = new EventHub(audio, composer, data, paired, loggers.CreateLogger("Events"));
        var api = new ApiHost(audio, data, paired, pairing, composer, hub, loggers.CreateLogger("Api"));

        if (o.Dev)
            pairing.CodeIssued += p => log.LogInformation("PAIRING CODE for {Device}: {Code}", p.DeviceName, p.Code);

        var port = o.Port ?? (o.Dev ? 7800 : data.Config.Port);
        var app = api.Build(port, loopbackOnly: o.Dev, loggers);
        try
        {
            // Kestrel runs on its own threads; keep the (STA) main thread free for the tray.
            Task.Run(() => app.StartAsync()).GetAwaiter().GetResult();
        }
        catch (Exception ex)
        {
            log.LogCritical("cannot listen on port {Port}: {Message}", port, ex.GetBaseException().Message);
            return 2;
        }
        log.LogInformation("Audio PC {Version} listening on {Bind}:{Port}, data dir {Dir}{Fake}", ApiHost.AppVersion,
            o.Dev ? "127.0.0.1" : "0.0.0.0", port, o.DataDir, o.FakeAudio ? " (fake audio)" : "");

        UdpDiscovery? udp = null;
        DnsSd? dns = null;
        if (!o.Dev)
            udp = UdpDiscovery.TryStart(p => UdpDiscovery.BuildReply(api.Info(), p), port, log);
        if (!o.Dev || o.DevMdns)
            dns = DnsSd.TryRegister(api.ServerName, (ushort)port, new Dictionary<string, string>
            {
                ["id"] = data.Config.ServerId, ["name"] = api.ServerName, ["api"] = "1", ["ver"] = ApiHost.AppVersion,
            }, log);

        try
        {
            if (o.NoTray) WaitForCtrlC();
            else
            {
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                using var tray = new TrayApp(port, data, paired, pairing, composer, audio);
                Application.Run(tray);
            }
        }
        finally
        {
            dns?.Dispose(); // deregister so the phone does not keep a stale entry
            udp?.Dispose();
            hub.Dispose();
            using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(3));
            try { Task.Run(() => app.StopAsync(stop.Token)).GetAwaiter().GetResult(); } catch { /* shutting down anyway */ }
            log.LogInformation("stopped");
        }
        return 0;
    }

    private static void WaitForCtrlC()
    {
        using var done = new ManualResetEventSlim();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; done.Set(); };
        AppDomain.CurrentDomain.ProcessExit += (_, _) => done.Set();
        done.Wait();
    }

    // A WinExe has no console; when started from a terminal we borrow the parent's so --dev
    // output is visible. Skipped when stdout is already redirected (e.g. to a file).
    private static void AttachParentConsole()
    {
        var h = GetStdHandle(-11);
        if (h != IntPtr.Zero && h != new IntPtr(-1)) return;
        AttachConsole(-1);
    }

    [DllImport("kernel32.dll")] private static extern IntPtr GetStdHandle(int which);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool AttachConsole(int pid);
}
