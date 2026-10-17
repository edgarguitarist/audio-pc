using System.Runtime.InteropServices;

namespace AudioPc.Server.Audio;

// Windows has no public API to change the default playback device. Every tool that does it
// (SoundSwitch, AudioDeviceCmdlets, EarTrumpet) calls the undocumented but stable
// IPolicyConfig COM interface that the Sound control panel itself uses. Only SetDefaultEndpoint
// is used; the other vtable slots are declared as placeholders purely to keep the layout right.

internal enum ERole { Console = 0, Multimedia = 1, Communications = 2 }

[ComImport, Guid("f8679f50-850a-41cf-9c72-430f290290c8")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IPolicyConfig
{
    [PreserveSig] int GetMixFormat(IntPtr deviceName, IntPtr format);
    [PreserveSig] int GetDeviceFormat(IntPtr deviceName, int isDefault, IntPtr format);
    [PreserveSig] int ResetDeviceFormat(IntPtr deviceName);
    [PreserveSig] int SetDeviceFormat(IntPtr deviceName, IntPtr endpointFormat, IntPtr mixFormat);
    [PreserveSig] int GetProcessingPeriod(IntPtr deviceName, int isDefault, IntPtr defaultPeriod, IntPtr minimumPeriod);
    [PreserveSig] int SetProcessingPeriod(IntPtr deviceName, IntPtr period);
    [PreserveSig] int GetShareMode(IntPtr deviceName, IntPtr mode);
    [PreserveSig] int SetShareMode(IntPtr deviceName, IntPtr mode);
    [PreserveSig] int GetPropertyValue(IntPtr deviceName, int bFxStore, IntPtr key, IntPtr value);
    [PreserveSig] int SetPropertyValue(IntPtr deviceName, int bFxStore, IntPtr key, IntPtr value);
    [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string deviceId, ERole role);
    [PreserveSig] int SetEndpointVisibility(IntPtr deviceName, int visible);
}

internal static class PolicyConfig
{
    private static readonly Guid ClsidPolicyConfigClient = new("870af99c-171d-4f9e-af0d-e63df40c2bc9");

    public static void SetDefaultEndpoint(string deviceId)
    {
        var type = Type.GetTypeFromCLSID(ClsidPolicyConfigClient, throwOnError: true)!;
        var policy = (IPolicyConfig)Activator.CreateInstance(type)!;
        try
        {
            foreach (var role in new[] { ERole.Console, ERole.Multimedia, ERole.Communications })
                Marshal.ThrowExceptionForHR(policy.SetDefaultEndpoint(deviceId, role));
        }
        finally
        {
            Marshal.ReleaseComObject(policy);
        }
    }
}
