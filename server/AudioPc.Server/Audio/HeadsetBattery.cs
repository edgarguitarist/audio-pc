using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace AudioPc.Server.Audio;

/// <summary>Battery of a wireless headset: percent (0–100) and whether it is charging or full.</summary>
public sealed record BatteryState(int Percent, bool Charging, bool Full);

/// <summary>
/// Reads the battery of a Corsair HS80 MAX WIRELESS straight from its USB receiver (Corsair's
/// "Bragi" HID protocol, as in OpenLinkHub). iCUE's SDK is not used: its value goes stale (it
/// said 88 % with the headset at 100 %) and it reports no charging state. Only read requests are
/// sent, so it coexists with iCUE.
/// </summary>
public static class HeadsetBattery
{
    // Receiver VID 1B1C / PID 0A97, interface 4, first vendor collection (usage page FF42).
    private const string PathMarker = "vid_1b1c&pid_0a97&mi_04&col01";
    private const byte Headset = 0x09; // destination: the paired headset (0x08 is the receiver)
    public const byte LevelProp = 0x0F, StatusProp = 0x10;
    private static readonly Guid HidInterface = new("4D1E55B2-F16F-11CF-88CB-001111000030");

    /// <summary>True for the Windows output whose battery this class can read.</summary>
    public static bool Matches(string rawName) => rawName.Contains("HS80 MAX", StringComparison.OrdinalIgnoreCase);

    /// <summary>Asks the headset for its battery. Null when the receiver is unplugged or the
    /// headset is off. Blocks up to ~2 s; call it off the UI thread.</summary>
    public static BatteryState? Read()
    {
        var path = FindReceiver();
        if (path == null) return null;
        using var handle = CreateFileW(path, 0xC0000000 /* read|write */, 3 /* share read|write */, IntPtr.Zero, 3 /* open existing */, 0x40000000 /* overlapped */, IntPtr.Zero);
        if (handle.IsInvalid) return null;
        using var hid = new FileStream(handle, FileAccess.ReadWrite, 0, isAsync: true);
        var level = Get(hid, LevelProp);
        if (level == null) return null;
        var status = Get(hid, StatusProp);
        return new BatteryState((int)Math.Round(level.Value / 10.0), status == 1, status == 3);
    }

    private static int? Get(FileStream hid, byte prop)
    {
        // Output report 0x02, 63 data bytes (from the receiver's HID report descriptor).
        var req = new byte[64];
        req[0] = 0x02; req[1] = Headset; req[2] = 0x02 /* GET */; req[3] = prop;
        try
        {
            hid.Write(req);
            using var timeout = new CancellationTokenSource(1000);
            var resp = new byte[64];
            while (true)
            {
                var n = hid.ReadAsync(resp, timeout.Token).AsTask().GetAwaiter().GetResult();
                if (Parse(resp.AsSpan(0, n), prop) is { } v) return v;
            }
        }
        catch (Exception e) when (e is OperationCanceledException or IOException) { return null; }
    }

    /// <summary>Value of a GET response, or null if this is not one for <paramref name="prop"/>.
    /// Response (input report 0x01): 01 &lt;source&gt; 02 &lt;error&gt; &lt;value, 2 bytes LE&gt;. iCUE's
    /// own requests answer on the same interface and every open handle gets a copy, so the
    /// value range is checked too: level is in tenths of a percent (10–1000; tiny values are
    /// stray answers to other properties), status is 1 charging, 2 discharging, 3 full.</summary>
    public static int? Parse(ReadOnlySpan<byte> r, byte prop)
    {
        if (r.Length < 6 || r[0] != 0x01 || r[2] != 0x02 || r[3] != 0) return null;
        int v = r[4] | r[5] << 8;
        bool ok = prop == LevelProp ? v is >= 10 and <= 1000 : v is >= 1 and <= 3;
        return ok ? v : null;
    }

    private static string? FindReceiver()
    {
        var guid = HidInterface;
        if (CM_Get_Device_Interface_List_SizeW(out var len, ref guid, null, 0) != 0 || len <= 1) return null;
        var buf = new char[len];
        if (CM_Get_Device_Interface_ListW(ref guid, null, buf, len, 0) != 0) return null;
        return new string(buf).Split('\0').FirstOrDefault(p => p.Contains(PathMarker, StringComparison.OrdinalIgnoreCase));
    }

    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    private static extern int CM_Get_Device_Interface_List_SizeW(out int len, ref Guid cls, string? deviceId, int flags);

    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    private static extern int CM_Get_Device_Interface_ListW(ref Guid cls, string? deviceId, char[] buffer, int len, int flags);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
}
