using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;

namespace AudioPc.Server.Audio;

/// <summary>
/// Volume/mute commands coming from the API. The audio is always changed exactly through Core
/// Audio; <see cref="VolumeOsd"/> additionally makes Windows show its native flyout.
/// </summary>
public interface IVolumeControl
{
    VolumeState SetVolume(int level);
    VolumeState ChangeVolume(int delta);
    VolumeState SetMute(bool? muted);

    /// <summary>True while the temporary +/-2% key blip may be visible in the level; the event hub
    /// holds back state pushes meanwhile so clients never see the transient value.</summary>
    bool IsBlip { get; }

    /// <summary>Raised when a blip window ends, so listeners can push the final value.</summary>
    event EventHandler? Settled;
}

/// <summary>Silent variant (fake audio, tests): straight Core Audio, no keys.</summary>
public sealed class PlainVolumeControl(IAudioService audio) : IVolumeControl
{
    public VolumeState SetVolume(int level) => audio.SetVolume(level);
    public VolumeState ChangeVolume(int delta) => audio.ChangeVolume(delta);
    public VolumeState SetMute(bool? muted) => audio.SetMute(muted);
    public bool IsBlip => false;
    public event EventHandler? Settled { add { } remove { } }
}

/// <summary>Injects media keys; abstracted so the ordering/verify logic can be tested without real keys.</summary>
public interface IKeySender
{
    /// <summary>Presses (down+up) each virtual key in order, in one batch. False if Windows refused any.</summary>
    bool Press(IReadOnlyList<ushort> virtualKeys);
}

/// <summary>SendInput-based sender. Fails (returns false) when blocked, e.g. by UIPI.</summary>
public sealed class SendInputKeys : IKeySender
{
    private const uint InputKeyboard = 1;
    private const uint KeyUp = 0x0002;
    private const uint KeyExtended = 0x0001;

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput { public int dx, dy; public uint mouseData, dwFlags, time; public nint dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    private struct KeybdInput { public ushort wVk, wScan; public uint dwFlags, time; public nint dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion { [FieldOffset(0)] public MouseInput mi; [FieldOffset(0)] public KeybdInput ki; }
    [StructLayout(LayoutKind.Sequential)]
    private struct Input { public uint type; public InputUnion u; }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, Input[] inputs, int size);

    public bool Press(IReadOnlyList<ushort> virtualKeys)
    {
        var inputs = new Input[virtualKeys.Count * 2];
        for (var i = 0; i < virtualKeys.Count; i++)
        {
            // Media keys are "extended" keys; the shell expects that flag.
            inputs[2 * i] = Key(virtualKeys[i], KeyExtended);
            inputs[2 * i + 1] = Key(virtualKeys[i], KeyExtended | KeyUp);
        }
        // SendInput returns how many events were inserted; 0 means blocked (UIPI) or failed.
        return SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>()) == inputs.Length;
    }

    private static Input Key(ushort vk, uint flags) => new()
    {
        type = InputKeyboard,
        u = new InputUnion { ki = new KeybdInput { wVk = vk, dwFlags = flags } },
    };
}

/// <summary>
/// Makes Windows show its native volume flyout for API-originated changes. Windows has no public
/// API for it, but synthesized volume keys make the shell show it. Volume keys step 2% and clamp,
/// so a net-zero UP/DOWN pair (order chosen so it never hits the clamp) pops the flyout at the
/// exact level we already set; a later read-back repairs the level if a key was dropped.
/// </summary>
public sealed class VolumeOsd : IVolumeControl
{
    public const ushort VkVolumeMute = 0xAD, VkVolumeDown = 0xAE, VkVolumeUp = 0xAF;
    private static readonly IReadOnlyList<ushort> UpDown = [VkVolumeUp, VkVolumeDown];
    private static readonly IReadOnlyList<ushort> DownUp = [VkVolumeDown, VkVolumeUp];
    private static readonly IReadOnlyList<ushort> MuteKey = [VkVolumeMute];

    private readonly IAudioService _audio;
    private readonly Func<bool> _enabled;
    private readonly IKeySender _keys;
    private readonly ILogger _log;
    private readonly TimeSpan _interval, _settle, _muteSettle;

    private readonly object _gate = new();
    private int _wanted;          // last level requested through the API
    private long _gen;            // bumped per API volume change; a verify only repairs if nothing newer arrived
    private bool _dirty, _running;
    private long _lastStart = -1; // Stopwatch timestamp of the last key pair
    private bool? _muteExpect;    // mute state a pressed mute key will produce, until verified
    private long _muteUntil;
    private volatile int _blip;
    private bool _warned;

    public event EventHandler? Settled;
    public bool IsBlip => _blip != 0;

    public VolumeOsd(IAudioService audio, Func<bool> enabled, IKeySender keys, ILogger log,
        TimeSpan? interval = null, TimeSpan? settle = null, TimeSpan? muteSettle = null)
    {
        _audio = audio; _enabled = enabled; _keys = keys; _log = log;
        _interval = interval ?? TimeSpan.FromMilliseconds(250);
        _settle = settle ?? TimeSpan.FromMilliseconds(200);
        _muteSettle = muteSettle ?? TimeSpan.FromMilliseconds(300);
    }

    public VolumeState SetVolume(int level) { var s = _audio.SetVolume(level); Request(s); return s; }
    public VolumeState ChangeVolume(int delta) { var s = _audio.ChangeVolume(delta); Request(s); return s; }

    public VolumeState SetMute(bool? muted)
    {
        if (!_enabled()) return _audio.SetMute(muted);
        var cur = _audio.GetVolume();
        bool current;
        lock (_gate) current = _muteExpect is { } e && Stopwatch.GetTimestamp() < _muteUntil ? e : cur.Muted;
        var target = muted ?? !current;
        if (target == current) return cur with { Muted = current };

        // Pressing the real key is what makes Windows show the flyout (setting mute via Core Audio is silent).
        if (!_keys.Press(MuteKey)) return _audio.SetMute(target);
        lock (_gate) { _muteExpect = target; _muteUntil = Stopwatch.GetTimestamp() + (long)(_muteSettle.TotalSeconds * Stopwatch.Frequency * 2); }
        _ = VerifyMute(target);
        return cur with { Muted = target };
    }

    private async Task VerifyMute(bool target)
    {
        try
        {
            await Task.Delay(_muteSettle);
            bool stale;
            lock (_gate) stale = _muteExpect != target;
            if (stale) return; // a newer press owns the outcome
            // Key dropped or blocked: set it exactly through Core Audio so behaviour stays correct.
            if (_audio.GetVolume().Muted != target) _audio.SetMute(target);
            lock (_gate) _muteExpect = null;
        }
        catch (Exception ex) { _log.LogWarning(ex, "mute verify failed"); }
    }

    /// <summary>Called after the exact level is applied; coalesces into at most one key pair per interval.</summary>
    private void Request(VolumeState applied)
    {
        // Volume keys unmute, so with the endpoint muted we must not send them.
        if (!_enabled() || applied.Muted) return;
        lock (_gate)
        {
            _wanted = applied.Volume; _gen++; _dirty = true;
            if (_running) return;
            _running = true;
        }
        _ = Task.Run(Loop);
    }

    private async Task Loop()
    {
        try
        {
            while (true)
            {
                lock (_gate) { if (!_dirty) { _running = false; return; } }
                // Trailing edge: wait out the interval, then read the LATEST wanted level.
                long last; lock (_gate) last = _lastStart;
                if (last >= 0)
                {
                    var wait = _interval - Stopwatch.GetElapsedTime(last);
                    if (wait > TimeSpan.Zero) await Task.Delay(wait);
                }
                int wanted; long gen;
                lock (_gate) { _dirty = false; wanted = _wanted; gen = _gen; _lastStart = Stopwatch.GetTimestamp(); }
                await Cycle(wanted, gen);
            }
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "volume OSD failed");
            lock (_gate) _running = false;
        }
    }

    private async Task Cycle(int wanted, long gen)
    {
        if (!_enabled()) return;
        var s = _audio.GetVolume();
        // Muted, or Windows/the user moved the level themselves: don't fight it.
        if (s.Muted || Math.Abs(s.Volume - wanted) > 2) return;
        if (s.Volume != wanted) s = _audio.SetVolume(wanted); // leftover from a previous blip

        _blip = 1;
        try
        {
            // Net zero without touching the 0/100 clamps: up-then-down at <=50, down-then-up above.
            if (!_keys.Press(wanted <= 50 ? UpDown : DownUp))
            {
                if (!_warned) { _warned = true; _log.LogWarning("volume keys were blocked (elevated foreground app?); the Windows volume indicator will not show"); }
                return;
            }
            await Task.Delay(_settle);
            bool newer; lock (_gate) newer = _gen != gen;
            if (newer) return; // a newer request set its own level; the loop runs again for it
            var after = _audio.GetVolume();
            // A key was dropped (or one got through twice): put the exact level back.
            if (!after.Muted && after.Volume != wanted) _audio.SetVolume(wanted);
        }
        finally
        {
            _blip = 0;
            try { Settled?.Invoke(this, EventArgs.Empty); } catch { /* listeners must not break the loop */ }
        }
    }
}
