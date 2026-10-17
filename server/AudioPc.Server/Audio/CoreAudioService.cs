using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;

namespace AudioPc.Server.Audio;

/// <summary>
/// Core Audio implementation. Threading: every COM object (enumerator, devices, endpoint
/// volume) lives on ONE dedicated MTA thread and is only touched there; public methods
/// marshal onto it and block for the result. This avoids cross-apartment proxies when called
/// from ASP.NET (MTA) and WinForms (STA) threads. COM notification callbacks arrive on
/// arbitrary RPC threads, so they never call COM or take locks: they only enqueue work or
/// arm the debounce timer.
/// </summary>
public sealed class CoreAudioService : IAudioService
{
    private static readonly TimeSpan Debounce = TimeSpan.FromMilliseconds(50);
    // Upper bound for one marshalled call, so a wedged COM call cannot hang HTTP requests forever.
    private static readonly TimeSpan CallTimeout = TimeSpan.FromSeconds(15);

    /// <summary>A unit of work for the COM thread; <c>Fail</c> completes its caller if it can never run.</summary>
    private readonly record struct WorkItem(Action Work, Action<Exception>? Fail);

    private readonly BlockingCollection<WorkItem> _queue = new();
    private readonly Thread _thread;
    private readonly System.Threading.Timer _timer;
    private readonly NotificationClient _client;
    private volatile bool _disposed;
    private volatile bool _workerDead;

    // Worker-thread state.
    private MMDeviceEnumerator _enumerator = null!;
    private MMDevice? _volDevice;
    private AudioEndpointVolume? _volume;

    public event EventHandler? Changed;

    public CoreAudioService()
    {
        _timer = new System.Threading.Timer(_ =>
        {
            try { Raise(); } catch { /* timer callbacks must never throw: it would kill the process */ }
        }, null, Timeout.Infinite, Timeout.Infinite);
        _client = new NotificationClient(this);
        using var ready = new ManualResetEventSlim();
        Exception? failure = null;
        _thread = new Thread(() =>
        {
            try
            {
                _enumerator = new MMDeviceEnumerator();
                _enumerator.RegisterEndpointNotificationCallback(_client);
                AttachVolume();
            }
            catch (Exception ex) { failure = ex; }
            ready.Set();
            if (failure != null) return;
            try
            {
                foreach (var item in _queue.GetConsumingEnumerable())
                {
                    // No exception type may end this loop: every caller would then block forever.
                    try { item.Work(); }
                    catch (Exception ex) { try { item.Fail?.Invoke(ex); } catch { } }
                }
                Teardown();
            }
            finally
            {
                // Normally reached after Dispose. If the thread dies for any other reason, make
                // later calls fail fast and release the ones already queued.
                _workerDead = true;
                try { _queue.CompleteAdding(); } catch (ObjectDisposedException) { }
                var gone = new InvalidOperationException("Core Audio worker thread stopped");
                while (_queue.TryTake(out var left)) { try { left.Fail?.Invoke(gone); } catch { } }
            }
        }) { IsBackground = true, Name = "AudioPC CoreAudio" };
        _thread.SetApartmentState(ApartmentState.MTA);
        _thread.Start();
        ready.Wait();
        if (failure != null) throw new InvalidOperationException("Core Audio init failed", failure);
    }

    public IReadOnlyList<AudioEndpoint> GetOutputs() => Run<IReadOnlyList<AudioEndpoint>>(() =>
    {
        var defaultId = DefaultId();
        var list = new List<AudioEndpoint>();
        foreach (var d in _enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
        {
            using (d) list.Add(new AudioEndpoint(d.ID, d.FriendlyName, d.ID == defaultId));
        }
        return list;
    });

    public string? GetDefaultOutputId() => Run(DefaultId);

    public void SetDefaultOutput(string id) => Run(() =>
    {
        bool found = false;
        foreach (var d in _enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
        {
            using (d) found |= d.ID == id;
        }
        if (!found) throw new KeyNotFoundException($"Unknown or inactive output: {id}");
        if (DefaultId() != id) PolicyConfig.SetDefaultEndpoint(id);
        return 0;
    });

    public VolumeState GetVolume() => Run(Read);

    public VolumeState SetVolume(int level) => Run(() =>
    {
        var v = RequireVolume();
        v.MasterVolumeLevelScalar = Math.Clamp(level, 0, 100) / 100f;
        v.Mute = false;
        return Read();
    });

    public VolumeState ChangeVolume(int delta) => Run(() =>
    {
        var v = RequireVolume();
        var now = (int)Math.Round(v.MasterVolumeLevelScalar * 100);
        v.MasterVolumeLevelScalar = Math.Clamp(now + delta, 0, 100) / 100f;
        if (delta > 0) v.Mute = false;
        return Read();
    });

    public VolumeState SetMute(bool? muted) => Run(() =>
    {
        var v = RequireVolume();
        v.Mute = muted ?? !v.Mute;
        return Read();
    });

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _timer.Dispose();
        _queue.CompleteAdding(); // worker drains, then tears down COM on its own thread
        if (Thread.CurrentThread != _thread) _thread.Join(TimeSpan.FromSeconds(3));
    }

    // ---- worker-thread helpers ----

    private string? DefaultId()
    {
        try
        {
            using var d = _enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            return d.ID;
        }
        catch (COMException) { return null; } // no active output
    }

    private AudioEndpointVolume RequireVolume()
    {
        // Re-attach lazily in case the earlier attempt raced with a device change.
        if (_volume == null || _volDevice?.ID != DefaultId()) AttachVolume();
        return _volume ?? throw new InvalidOperationException("No default playback device");
    }

    private VolumeState Read()
    {
        var v = RequireVolume();
        return new VolumeState((int)Math.Round(v.MasterVolumeLevelScalar * 100), v.Mute);
    }

    /// <summary>Points the volume subscription at the current default endpoint.</summary>
    private void AttachVolume()
    {
        DetachVolume();
        try
        {
            var d = _enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            _volDevice = d;
            _volume = d.AudioEndpointVolume;
            _volume.OnVolumeNotification += OnVolume;
        }
        catch (COMException)
        {
            DetachVolume();
        }
    }

    private void DetachVolume()
    {
        try { if (_volume != null) _volume.OnVolumeNotification -= OnVolume; } catch { }
        try { _volume?.Dispose(); } catch { }
        try { _volDevice?.Dispose(); } catch { }
        _volume = null;
        _volDevice = null;
    }

    private void Teardown()
    {
        DetachVolume();
        try { _enumerator.UnregisterEndpointNotificationCallback(_client); } catch { }
        try { _enumerator.Dispose(); } catch { }
    }

    private void OnVolume(AudioVolumeNotificationData data) => Signal();

    // ---- plumbing ----

    private T Run<T>(Func<T> func)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_workerDead) throw new InvalidOperationException("Core Audio worker thread stopped");
        if (Thread.CurrentThread == _thread) return func();
        var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        try
        {
            _queue.Add(new WorkItem(
                () =>
                {
                    try { tcs.TrySetResult(func()); }
                    catch (Exception ex) { tcs.TrySetException(ex); }
                },
                ex => tcs.TrySetException(ex)));
        }
        catch (InvalidOperationException) { throw new ObjectDisposedException(nameof(CoreAudioService)); }
        bool done;
        try { done = tcs.Task.Wait(CallTimeout); }
        catch (AggregateException) { done = true; } // faulted: GetResult rethrows the original exception
        if (!done) throw new TimeoutException("Core Audio call timed out");
        return tcs.Task.GetAwaiter().GetResult();
    }

    /// <summary>Coalesces bursts of notifications into one Changed event.</summary>
    private void Signal()
    {
        if (_disposed) return;
        try { _timer.Change(Debounce, Timeout.InfiniteTimeSpan); } catch (ObjectDisposedException) { }
    }

    private void Raise()
    {
        if (_disposed) return;
        var handlers = Changed;
        if (handlers == null) return;
        foreach (var h in handlers.GetInvocationList().Cast<EventHandler>())
        {
            try { h(this, EventArgs.Empty); } catch { /* a bad subscriber must not break the others */ }
        }
    }

    private void OnDefaultChanged()
    {
        // Re-subscribe volume on the worker; never block the COM callback thread.
        try { _queue.Add(new WorkItem(AttachVolume, null)); } catch (InvalidOperationException) { }
        Signal();
    }

    private sealed class NotificationClient(CoreAudioService owner) : IMMNotificationClient
    {
        public void OnDefaultDeviceChanged(DataFlow flow, Role role, string defaultDeviceId)
        {
            if (flow == DataFlow.Render && role == Role.Multimedia) owner.OnDefaultChanged();
        }
        public void OnDeviceAdded(string pwstrDeviceId) => owner.Signal();
        public void OnDeviceRemoved(string deviceId) => owner.Signal();
        public void OnDeviceStateChanged(string deviceId, DeviceState newState) => owner.Signal();
        public void OnPropertyValueChanged(string pwstrDeviceId, PropertyKey key) { }
    }
}
