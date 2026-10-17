using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;

namespace AudioPc.Server.Discovery;

/// <summary>
/// mDNS / DNS-SD registration through the Windows DNS client service (dnsapi.dll, Win10 1809+).
/// Windows owns the multicast sockets, so our process opens no extra listening port (no
/// firewall prompt). P/Invoke declarations are at the bottom of this file.
/// </summary>
public sealed class DnsSd : IDisposable
{
    private const int DnsRequestPending = 9506;

    private readonly ILogger _log;
    private IntPtr _instance;
    private IntPtr _request; // unmanaged DNS_SERVICE_REGISTER_REQUEST, reused for deregistration
    private readonly RegisterComplete _callback; // kept in a field so the GC cannot collect it
    private readonly List<IntPtr> _strings = new();

    private DnsSd(ILogger log) { _log = log; _callback = OnComplete; }

    public static DnsSd? TryRegister(string instanceName, ushort port, IReadOnlyDictionary<string, string> txt, ILogger log)
    {
        var d = new DnsSd(log);
        try
        {
            if (d.Register(instanceName, port, txt)) return d;
        }
        catch (Exception ex) { log.LogWarning("mDNS registration failed: {Message}", ex.Message); }
        d.Dispose();
        return null;
    }

    private bool Register(string instanceName, ushort port, IReadOnlyDictionary<string, string> txt)
    {
        var host = Environment.MachineName + ".local";
        var service = instanceName + "._audiopc._tcp.local";
        var keys = txt.Keys.ToArray();
        var kp = Marshal.AllocHGlobal(IntPtr.Size * keys.Length);
        var vp = Marshal.AllocHGlobal(IntPtr.Size * keys.Length);
        _strings.Add(kp); _strings.Add(vp);
        for (var i = 0; i < keys.Length; i++)
        {
            var k = Marshal.StringToHGlobalUni(keys[i]);
            var v = Marshal.StringToHGlobalUni(txt[keys[i]]);
            _strings.Add(k); _strings.Add(v);
            Marshal.WriteIntPtr(kp, i * IntPtr.Size, k);
            Marshal.WriteIntPtr(vp, i * IntPtr.Size, v);
        }

        _instance = DnsServiceConstructInstance(service, host, IntPtr.Zero, IntPtr.Zero, port, 0, 0, (uint)keys.Length, kp, vp);
        if (_instance == IntPtr.Zero) { _log.LogWarning("mDNS: DnsServiceConstructInstance failed"); return false; }

        var req = new DnsServiceRegisterRequest
        {
            Version = 1,
            InterfaceIndex = 0, // all interfaces
            ServiceInstance = _instance,
            RegisterCompletionCallback = Marshal.GetFunctionPointerForDelegate(_callback),
        };
        _request = Marshal.AllocHGlobal(Marshal.SizeOf<DnsServiceRegisterRequest>());
        Marshal.StructureToPtr(req, _request, false);

        var status = DnsServiceRegister(_request, IntPtr.Zero);
        if (status != DnsRequestPending)
        {
            _log.LogWarning("mDNS: DnsServiceRegister returned {Status}", status);
            return false;
        }
        _log.LogInformation("mDNS: registering {Service} on port {Port}", service, port);
        return true;
    }

    private void OnComplete(uint status, IntPtr context, IntPtr instance)
    {
        if (status != 0) _log.LogWarning("mDNS registration completed with status {Status}", status);
        else _log.LogInformation("mDNS registration active");
    }

    public void Dispose()
    {
        try
        {
            if (_request != IntPtr.Zero && _instance != IntPtr.Zero)
            {
                var status = DnsServiceDeRegister(_request, IntPtr.Zero);
                _log.LogInformation("mDNS: deregistered (status {Status})", status);
            }
        }
        catch (Exception ex) { _log.LogDebug(ex, "mDNS deregistration"); }
        if (_instance != IntPtr.Zero) { DnsServiceFreeInstance(_instance); _instance = IntPtr.Zero; }
        if (_request != IntPtr.Zero) { Marshal.FreeHGlobal(_request); _request = IntPtr.Zero; }
        foreach (var p in _strings) Marshal.FreeHGlobal(p);
        _strings.Clear();
    }

    // ---- P/Invoke (dnsapi.dll) ----------------------------------------------------------------

    private delegate void RegisterComplete(uint status, IntPtr queryContext, IntPtr instance);

    [StructLayout(LayoutKind.Sequential)]
    private struct DnsServiceRegisterRequest
    {
        public uint Version;
        public uint InterfaceIndex;
        public IntPtr ServiceInstance;
        public IntPtr RegisterCompletionCallback;
        public IntPtr QueryContext;
        public IntPtr Credentials;
        public int UnicastEnabled; // BOOL
    }

    [DllImport("dnsapi.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr DnsServiceConstructInstance(string serviceName, string hostName, IntPtr ip4, IntPtr ip6,
        ushort port, ushort priority, ushort weight, uint propertyCount, IntPtr keys, IntPtr values);

    [DllImport("dnsapi.dll")]
    private static extern void DnsServiceFreeInstance(IntPtr instance);

    [DllImport("dnsapi.dll")]
    private static extern int DnsServiceRegister(IntPtr request, IntPtr cancel);

    [DllImport("dnsapi.dll")]
    private static extern int DnsServiceDeRegister(IntPtr request, IntPtr cancel);
}
