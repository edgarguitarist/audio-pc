using System.Globalization;
using System.Text;

namespace AudioPc.Server.Api;

/// <summary>Cleans client-supplied labels before they reach logs, the tray menu or balloons.</summary>
public static class TextSafety
{
    /// <summary>
    /// Removes control and format characters (Cc, Cf — this includes CR/LF, which could forge log
    /// lines, and the bidi overrides U+202A–202E / U+2066–2069, which could make a balloon read
    /// differently from what was sent) plus line/paragraph separators, then trims.
    /// </summary>
    public static string Clean(string? s)
    {
        if (string.IsNullOrEmpty(s)) return "";
        var sb = new StringBuilder(s.Length);
        foreach (var ch in s)
        {
            var cat = CharUnicodeInfo.GetUnicodeCategory(ch);
            if (cat is UnicodeCategory.Control or UnicodeCategory.Format
                or UnicodeCategory.LineSeparator or UnicodeCategory.ParagraphSeparator) continue;
            sb.Append(ch);
        }
        return sb.ToString().Trim();
    }
}
