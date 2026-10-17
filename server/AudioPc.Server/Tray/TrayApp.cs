using System.Diagnostics;
using System.Globalization;
using AudioPc.Server.Api;
using AudioPc.Server.Audio;
using AudioPc.Server.Data;
using Microsoft.Win32;

namespace AudioPc.Server.Tray;

/// <summary>
/// Tray icon + menu. Deliberately never opens a window by itself (the user may be gaming
/// fullscreen): pairing codes arrive as a balloon tip and in the menu; the panel opens only when
/// the user clicks "Open panel".
/// </summary>
internal sealed class TrayApp : ApplicationContext
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValue = "AudioPC";

    private readonly NotifyIcon _icon;
    private readonly Control _ui = new();
    private readonly System.Threading.Timer _tooltipTimer;
    private readonly int _port;
    private readonly DataStore _data;
    private readonly PairedStore _paired;
    private readonly PairingService _pairing;
    private readonly DisplayComposer _composer;
    private readonly IAudioService _audio;
    private readonly bool _es = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "es";

    public TrayApp(int port, DataStore data, PairedStore paired, PairingService pairing, DisplayComposer composer, IAudioService audio)
    {
        _port = port; _data = data; _paired = paired; _pairing = pairing; _composer = composer; _audio = audio;
        _ = _ui.Handle; // create the handle now so BeginInvoke works from any thread

        _icon = new NotifyIcon { Icon = LoadIcon(), Text = "Audio PC", Visible = true, ContextMenuStrip = new ContextMenuStrip() };
        _icon.ContextMenuStrip.Opening += (_, _) => BuildMenu(_icon.ContextMenuStrip);
        _icon.DoubleClick += (_, _) => OpenPanel();

        _pairing.CodeIssued += OnCodeIssued;
        _tooltipTimer = new System.Threading.Timer(_ => RefreshTooltip(), null, 0, Timeout.Infinite);
        _audio.Changed += OnAudioChanged;
    }

    private string T(string es, string en) => _es ? es : en;

    private static Icon LoadIcon()
    {
        try
        {
            using var s = typeof(TrayApp).Assembly.GetManifestResourceStream("Assets/audiopc.ico");
            if (s != null) return new Icon(s);
        }
        catch { /* fall through to the stock icon */ }
        return SystemIcons.Application;
    }

    private void OnAudioChanged(object? sender, EventArgs e) => _tooltipTimer.Change(300, Timeout.Infinite);

    private void RefreshTooltip()
    {
        try
        {
            var id = _audio.GetDefaultOutputId();
            var cur = _audio.GetOutputs().FirstOrDefault(o => o.Id == id);
            var name = cur == null ? "" : " — " + _composer.Compose(cur, _es).Name;
            var text = "Audio PC" + name;
            if (text.Length > 127) text = text[..127]; // NotifyIcon.Text hard limit
            _ui.BeginInvoke(() => _icon.Text = text);
        }
        catch { /* audio not ready; keep the old tooltip */ }
    }

    private void OnCodeIssued(PendingPairing p)
    {
        // ShowBalloonTip does not take focus, unlike a window.
        var msg = T($"Código para {p.DeviceName}: {PairingService.Format(p.Code)}", $"Code for {p.DeviceName}: {PairingService.Format(p.Code)}");
        _ui.BeginInvoke(() => _icon.ShowBalloonTip(15000, "Audio PC", msg, ToolTipIcon.Info));
    }

    private void BuildMenu(ContextMenuStrip menu)
    {
        menu.Items.Clear();
        menu.Items.Add(T("Abrir panel", "Open panel"), null, (_, _) => OpenPanel());

        var pending = _pairing.Pending();
        if (pending.Count > 0)
        {
            menu.Items.Add(new ToolStripSeparator());
            foreach (var p in pending)
            {
                var secs = Math.Max(0, (int)(p.ExpiresAt - DateTimeOffset.UtcNow).TotalSeconds);
                menu.Items.Add(new ToolStripMenuItem(T($"Código para {p.DeviceName}: {PairingService.Format(p.Code)} ({secs} s)",
                    $"Code for {p.DeviceName}: {PairingService.Format(p.Code)} ({secs} s)")) { Enabled = false });
            }
        }
        menu.Items.Add(new ToolStripSeparator());

        var devices = new ToolStripMenuItem(T("Dispositivos emparejados", "Paired devices"));
        var list = _paired.List();
        if (list.Count == 0) devices.DropDownItems.Add(new ToolStripMenuItem(T("(ninguno)", "(none)")) { Enabled = false });
        foreach (var d in list)
        {
            var item = new ToolStripMenuItem(d.DeviceName);
            var id = d.DeviceId;
            item.DropDownItems.Add(T("Revocar", "Revoke"), null, (_, _) => _paired.Revoke(id));
            devices.DropDownItems.Add(item);
        }
        menu.Items.Add(devices);

        menu.Items.Add(new ToolStripMenuItem(T("Iniciar con Windows", "Start with Windows"), null, (_, _) => SetAutostart(!IsAutostart()))
        { Checked = IsAutostart(), CheckOnClick = false });
        menu.Items.Add(T("Abrir carpeta de datos", "Open data folder"), null, (_, _) => Launch(_data.Dir));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(T("Salir", "Exit"), null, (_, _) => ExitThread());
    }

    private void OpenPanel() => Launch($"http://localhost:{_port}/");

    private static void Launch(string target)
    {
        try { Process.Start(new ProcessStartInfo(target) { UseShellExecute = true }); }
        catch { /* no default handler: nothing sensible to do from a tray click */ }
    }

    private static bool IsAutostart()
    {
        using var k = Registry.CurrentUser.OpenSubKey(RunKey);
        return k?.GetValue(RunValue) != null;
    }

    private static void SetAutostart(bool on)
    {
        using var k = Registry.CurrentUser.CreateSubKey(RunKey);
        if (on && Environment.ProcessPath is { } exe) k.SetValue(RunValue, $"\"{exe}\"");
        else k.DeleteValue(RunValue, false);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _audio.Changed -= OnAudioChanged;
            _pairing.CodeIssued -= OnCodeIssued;
            _tooltipTimer.Dispose();
            _icon.Visible = false; // otherwise the ghost icon lingers until the mouse passes over it
            _icon.Dispose();
            _ui.Dispose();
        }
        base.Dispose(disposing);
    }
}
