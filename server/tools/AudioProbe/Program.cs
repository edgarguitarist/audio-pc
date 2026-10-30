using AudioPc.Server.Audio;

// Usage: AudioProbe [seconds=5] [--nudge] | AudioProbe --battery
// Prints outputs/default/volume and counts Changed events. --nudge does one SetVolume(current-1)
// and restores the exact original value (the only mutation this probe ever makes).
// --battery checks the headset-battery parser against captured receiver replies, then reads it live.
Console.OutputEncoding = System.Text.Encoding.UTF8;
if (args.Contains("--battery"))
{
    static byte[] H(string hex) => Convert.FromHexString(hex.Replace(" ", ""));
    const byte L = HeadsetBattery.LevelProp, S = HeadsetBattery.StatusProp;
    Check(HeadsetBattery.Parse(H("01 01 02 00 e8 03"), L) == 1000, "level 100.0 %");
    Check(HeadsetBattery.Parse(H("01 01 02 00 02 00"), S) == 2, "status discharging");
    Check(HeadsetBattery.Parse(H("01 00 12 00 00 00"), L) == null, "iCUE heartbeat ignored");
    Check(HeadsetBattery.Parse(H("01 01 02 05 e8 03"), L) == null, "device error ignored");
    Check(HeadsetBattery.Parse(H("01 01 02 00 02 00"), L) == null, "stray tiny level ignored");
    Check(HeadsetBattery.Parse(H("01 01 02 00 e8 03"), S) == null, "status out of range ignored");
    Console.WriteLine($"parser OK; live: {HeadsetBattery.Read()?.ToString() ?? "(no receiver / headset off)"}");
    return;
}
static void Check(bool ok, string what) { if (!ok) throw new Exception("battery parser: " + what); }
int seconds = args.Length > 0 && int.TryParse(args[0], out var s) ? s : 5;
bool nudge = args.Contains("--nudge");

using var audio = new CoreAudioService();
var defaultId = audio.GetDefaultOutputId();
Console.WriteLine($"Default: {defaultId ?? "(none)"}");
foreach (var o in audio.GetOutputs())
    Console.WriteLine($"  {(o.IsDefault ? "*" : " ")} {o.RawName}  [{o.Id}]");
var vol = audio.GetVolume();
Console.WriteLine($"Volume: {vol.Volume}  Muted: {vol.Muted}");

int events = 0;
audio.Changed += (_, _) =>
{
    Interlocked.Increment(ref events);
    Console.WriteLine($"[{DateTime.Now:HH:mm:ss.fff}] Changed -> {audio.GetVolume()}");
};

if (defaultId != null) audio.SetDefaultOutput(defaultId); // no-op path check
if (nudge && vol.Volume > 0)
{
    Console.WriteLine($"Nudge: {vol.Volume} -> {vol.Volume - 1}");
    audio.SetVolume(vol.Volume - 1);
    Thread.Sleep(300);
    audio.SetVolume(vol.Volume);
    if (vol.Muted) audio.SetMute(true);
    Thread.Sleep(300);
    var back = audio.GetVolume();
    Console.WriteLine($"Restored: {back.Volume} muted={back.Muted} (original {vol.Volume}/{vol.Muted})");
}

Console.WriteLine($"Listening {seconds}s...");
Thread.Sleep(seconds * 1000);
Console.WriteLine($"Changed events: {events}");
