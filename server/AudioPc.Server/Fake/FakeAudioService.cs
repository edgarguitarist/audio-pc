using AudioPc.Server.Audio;

namespace AudioPc.Server.Fake;

/// <summary>In-memory audio for tests (--fake-audio): never touches Windows audio.</summary>
public sealed class FakeAudioService : IAudioService
{
    private readonly object _gate = new();
    private readonly List<(string Id, string Name)> _outputs = new()
    {
        ("{0.0.0.00000000}.{fake-0001}", "Altavoces (Realtek High Definition Audio)"),
        ("{0.0.0.00000000}.{fake-0002}", "Auriculares (2- JBL Tour Pro 3)"),
        ("{0.0.0.00000000}.{fake-0003}", "TCL QLED C655 (NVIDIA High Definition Audio)"),
        ("{0.0.0.00000000}.{fake-0004}", "Speakers (NVIDIA Broadcast)"),
    };
    private string _default = "{0.0.0.00000000}.{fake-0002}";
    private int _volume = 45;
    private bool _muted;

    public event EventHandler? Changed;

    // Raised off-thread like the real COM callbacks, so subscribers are tested under the same conditions.
    private void Raise() => Task.Run(() => Changed?.Invoke(this, EventArgs.Empty));

    public IReadOnlyList<AudioEndpoint> GetOutputs()
    {
        lock (_gate) return _outputs.Select(o => new AudioEndpoint(o.Id, o.Name, o.Id == _default)).ToList();
    }

    public string? GetDefaultOutputId() { lock (_gate) return _default; }

    public void SetDefaultOutput(string id)
    {
        lock (_gate)
        {
            if (!_outputs.Any(o => o.Id == id)) throw new KeyNotFoundException(id);
            _default = id;
        }
        Raise();
    }

    public VolumeState GetVolume() { lock (_gate) return new VolumeState(_volume, _muted); }

    public VolumeState SetVolume(int level)
    {
        VolumeState s;
        lock (_gate) { _volume = Math.Clamp(level, 0, 100); _muted = false; s = new(_volume, _muted); }
        Raise();
        return s;
    }

    public VolumeState ChangeVolume(int delta)
    {
        VolumeState s;
        lock (_gate)
        {
            _volume = Math.Clamp(_volume + delta, 0, 100);
            if (delta > 0) _muted = false;
            s = new(_volume, _muted);
        }
        Raise();
        return s;
    }

    public VolumeState SetMute(bool? muted)
    {
        VolumeState s;
        lock (_gate) { _muted = muted ?? !_muted; s = new(_volume, _muted); }
        Raise();
        return s;
    }

    public void Dispose() { }
}
