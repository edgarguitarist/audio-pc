using System.Text.RegularExpressions;
using AudioPc.Server.Audio;
using AudioPc.Server.Data;

namespace AudioPc.Server.Api;

public sealed record OutputDto(string Id, string RawName, string Name, string Detail, string Kind, string? Image, bool IsDefault, bool Hidden);
public sealed record StateDto(string? CurrentId, int Volume, bool Muted, IReadOnlyList<OutputDto> Outputs);

/// <summary>Builds the API state: devices.json display config (API.md §6.1) or the automatic
/// description derived from the Windows name (§6.2).</summary>
public sealed class DisplayComposer
{
    private static readonly string[] Kinds = { "speaker", "headphones", "headset", "earbuds", "tv", "virtual" };
    private static readonly string[] LabelsEs = { "Altavoces", "Audífonos", "Auriculares", "Audífonos", "Televisor", "Virtual" };
    private static readonly string[] LabelsEn = { "Speakers", "Headphones", "Headset", "Earbuds", "TV", "Virtual" };

    private const RegexOptions Ci = RegexOptions.IgnoreCase | RegexOptions.CultureInvariant;
    private static readonly Regex Split = new(@"^(?<c>[^(]*?)\s*\((?<d>.*)\)\s*$", RegexOptions.Compiled | RegexOptions.CultureInvariant);
    private static readonly Regex Generic = new(@"^\s*(altavoces|altavoz|speakers?|auriculares|aud[ií]fonos?|headphones?|cascos|salida|output|digital|realtek)", Ci | RegexOptions.Compiled);
    private static readonly Regex Duplicate = new(@"^\d+-\s*", RegexOptions.Compiled);
    private static readonly Regex Suffix = new(@"\s+(Gaming\s+Headset|Headset)$", Ci | RegexOptions.Compiled);

    // First rule that matches wins, in this order.
    private static readonly (string Kind, Regex Rx)[] KindRules =
    {
        ("virtual", new(@"broadcast|virtual|vb-audio|voicemeeter|cable (input|output)|steam streaming|sonar|wave link", Ci | RegexOptions.Compiled)),
        ("tv", new(@"\btv\b|hdmi|high definition audio|display audio|tcl|bravia|samsung|lg tv|monitor", Ci | RegexOptions.Compiled)),
        ("headset", new(@"headset|micr[oó]fono|hs\d|arctis|cloud|blackshark", Ci | RegexOptions.Compiled)),
        ("earbuds", new(@"buds|airpods|tour pro|wf-|earbuds|tws|live pro|jbl", Ci | RegexOptions.Compiled)),
        ("headphones", new(@"auricular|aud[ií]fono|headphone|cascos|wh-", Ci | RegexOptions.Compiled)),
    };

    private readonly DataStore _data;
    private readonly IAudioService _audio;

    public DisplayComposer(DataStore data, IAudioService audio) { _data = data; _audio = audio; }

    public static bool IsSpanish(string? acceptLanguage)
    {
        if (string.IsNullOrWhiteSpace(acceptLanguage)) return false;
        var first = acceptLanguage.Split(',')[0].Split(';')[0].Trim();
        return first.StartsWith("es", StringComparison.OrdinalIgnoreCase);
    }

    public StateDto BuildState(bool spanish)
    {
        var outputs = _audio.GetOutputs();
        var current = _audio.GetDefaultOutputId();
        var vol = _audio.GetVolume();
        var list = outputs.Select(o => Compose(o, spanish)).ToList();
        return new StateDto(current, vol.Volume, vol.Muted, list);
    }

    public OutputDto Compose(AudioEndpoint ep, bool spanish)
    {
        var cfg = _data.Devices;
        var raw = ep.RawName;
        var hidden = cfg.Hide.Any(h => !string.IsNullOrWhiteSpace(h) && Contains(raw, h));
        var entry = cfg.Devices.FirstOrDefault(d => d != null && !string.IsNullOrWhiteSpace(d.Match) && Contains(raw, d.Match!));

        var (autoName, autoDetail, autoKind) = Auto(raw, spanish);
        var kind = entry?.Kind is { } k && Kinds.Contains(k) ? k : autoKind;
        var detail = entry?.Detail ?? autoDetail;
        return new OutputDto(ep.Id, raw, entry?.Name ?? autoName, detail, kind, ImageUrl(entry?.Image), ep.IsDefault, hidden);
    }

    private static bool Contains(string raw, string needle) => raw.Contains(needle, StringComparison.OrdinalIgnoreCase);

    private string? ImageUrl(string? file)
    {
        if (string.IsNullOrEmpty(file) || !ImageFile.IsValid(file)) return null;
        var path = Path.Combine(_data.ImagesDir, file);
        if (!File.Exists(path)) return null;
        var v = new DateTimeOffset(File.GetLastWriteTimeUtc(path)).ToUnixTimeMilliseconds();
        return $"/api/images/{file}?v={v}";
    }

    private static string Label(string kind, bool es) => (es ? LabelsEs : LabelsEn)[Array.IndexOf(Kinds, kind)];

    public static (string Name, string Detail, string Kind) Auto(string raw, bool es)
    {
        var kind = KindRules.FirstOrDefault(r => r.Rx.IsMatch(raw)).Kind ?? "speaker";
        var m = Split.Match(raw);
        if (!m.Success) return (raw, Label(kind, es), kind);
        var connector = m.Groups["c"].Value.Trim();
        // Windows prefixes duplicate device names with "2- ", which is noise for the user.
        var device = Duplicate.Replace(m.Groups["d"].Value.Trim(), "");
        if (Generic.IsMatch(connector))
            return (Suffix.Replace(device, ""), Label(kind, es), kind);
        return (connector, device, kind);
    }
}

public static class ImageFile
{
    private static readonly Regex Rx = new(@"^[a-z0-9-]+\.(webp|png|jpg)\z", RegexOptions.Compiled | RegexOptions.CultureInvariant);
    public static bool IsValid(string file) => Rx.IsMatch(file);
}
