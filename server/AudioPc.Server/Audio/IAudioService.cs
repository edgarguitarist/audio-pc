namespace AudioPc.Server.Audio;

/// <summary>An active playback endpoint as Windows reports it.</summary>
/// <param name="Id">Endpoint id, e.g. "{0.0.0.00000000}.{c524959f-...}".</param>
/// <param name="RawName">Friendly name, e.g. "Altavoces (JBL Tour Pro 3)".</param>
public sealed record AudioEndpoint(string Id, string RawName, bool IsDefault);

/// <summary>Volume (0–100) and mute of the current default playback endpoint.</summary>
public sealed record VolumeState(int Volume, bool Muted);

/// <summary>
/// Windows Core Audio for playback endpoints. Implementations must be thread-safe: they are
/// called from ASP.NET Core request threads (MTA) and from the WinForms UI thread (STA).
/// </summary>
public interface IAudioService : IDisposable
{
    /// <summary>Active playback endpoints, in Windows order.</summary>
    IReadOnlyList<AudioEndpoint> GetOutputs();

    /// <summary>Id of the default playback endpoint (multimedia role), or null if none.</summary>
    string? GetDefaultOutputId();

    /// <summary>Makes <paramref name="id"/> the default for the console, multimedia and
    /// communications roles. Throws <see cref="KeyNotFoundException"/> if it is not an active
    /// playback endpoint.</summary>
    void SetDefaultOutput(string id);

    /// <summary>Volume and mute of the default playback endpoint.</summary>
    VolumeState GetVolume();

    /// <summary>Sets the default endpoint volume (clamped to 0–100) and unmutes it.</summary>
    VolumeState SetVolume(int level);

    /// <summary>Adds <paramref name="delta"/> to the volume (clamped); a positive delta unmutes.</summary>
    VolumeState ChangeVolume(int delta);

    /// <summary>Mutes (true), unmutes (false) or toggles (null) the default endpoint.</summary>
    VolumeState SetMute(bool? muted);

    /// <summary>Raised from any thread when the default endpoint changes, an endpoint is
    /// added/removed/changes state, or the default endpoint's volume or mute changes (whether
    /// from this app or from Windows). Subscribers should debounce.</summary>
    event EventHandler? Changed;
}
