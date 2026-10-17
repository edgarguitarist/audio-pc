using System.Text;
using Microsoft.Extensions.Logging;

namespace AudioPc.Server;

/// <summary>Single log file in the data dir, truncated when it passes 1 MB (kept deliberately simple).</summary>
public sealed class FileLoggerProvider : ILoggerProvider
{
    private const long MaxBytes = 1_000_000;
    private readonly object _gate = new();
    private readonly string _path;

    public FileLoggerProvider(string path) { _path = path; }

    public ILogger CreateLogger(string categoryName) => new FileLogger(this, categoryName);

    private void Write(string line)
    {
        try
        {
            lock (_gate)
            {
                if (File.Exists(_path) && new FileInfo(_path).Length > MaxBytes) File.WriteAllText(_path, "");
                File.AppendAllText(_path, line + Environment.NewLine, Encoding.UTF8);
            }
        }
        catch { /* logging must never take the server down */ }
    }

    public void Dispose() { }

    private sealed class FileLogger(FileLoggerProvider owner, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Information;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel)) return;
            var short_ = category.Contains('.') ? category[(category.LastIndexOf('.') + 1)..] : category;
            var text = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{logLevel.ToString()[..3].ToUpperInvariant()}] {short_}: {formatter(state, exception)}";
            if (exception != null) text += Environment.NewLine + exception;
            owner.Write(text);
        }
    }
}
