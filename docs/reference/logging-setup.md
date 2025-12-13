# Logging Setup Guide

## Overview

The ML Options Trading system uses **Logback** for structured, environment-specific logging that's optimized for both human readability and AI agent analysis.

## Key Features

- **Environment-specific configuration** - Dev, test, and production have different log levels and outputs
- **Automatic log rotation** - Files are rotated daily and by size, with configurable retention
- **Separate error logs** - Errors written to dedicated file for quick problem identification
- **AI agent friendly** - Clear timestamps, structured format, REPL functions for log analysis
- **XTDB-specific logging** - Database operations logged separately to reduce noise

## Log Files

All logs are written to the `logs/` directory (gitignored):

| File | Purpose | Retention |
|------|---------|-----------|
| `logs/app.log` | Main application log (all levels) | 30 days (dev), 60 days (prod) |
| `logs/error.log` | Errors only (ERROR level) | 90 days |
| `logs/xtdb.log` | XTDB database operations | 14 days (dev), 30 days (prod) |

### Log Rotation

- **Size-based**: Files split when reaching 100MB (dev) or 200MB (prod)
- **Time-based**: Daily rollover with date in filename
- **Compression**: Old logs compressed with gzip (prod only)
- **Total cap**: 3GB (app.log), 1GB (error.log), 1GB-5GB (xtdb.log)

## Log Format

```
2025-12-02 11:39:25,396 [main] INFO  ml-options.core - System running
^                       ^      ^     ^                  ^
ISO 8601 timestamp      Thread Level Logger name       Message
```

**AI/LLM friendly features:**
- ISO 8601 timestamps for easy parsing
- Fixed-width log levels (5 chars)
- Fully qualified logger names
- Newline-separated entries (one entry per line)

## Viewing Logs

### From Terminal

```bash
# Tail active logs
tail -f logs/app.log
tail -f logs/error.log

# View last N lines
tail -50 logs/app.log

# Search for errors
grep ERROR logs/app.log

# Search with context
grep -A 5 -B 5 "Exception" logs/app.log
```

### From REPL

Connect to nREPL (port 7888) and use helper functions:

```clojure
;; View last 50 lines from app.log
(user/logs)

;; View last 100 lines
(user/logs :lines 100)

;; View error log
(user/logs :file :error)

;; Filter by log level
(user/logs :level :error)
(user/logs :level :warn)

;; Search for specific text
(user/logs :grep "XTDB")
(user/logs :grep "compaction")

;; Combine filters
(user/logs :file :error :lines 20)

;; Get overall log summary
(user/log-summary)
```

**Output example:**
```clojure
(user/log-summary)
; === Log File Summary ===
;
; logs/app.log : 351 logs/app.log lines
; logs/error.log : 334 logs/error.log lines
; logs/xtdb.log : 1172 logs/xtdb.log lines
;
; === Recent ERROR Count (last hour) ===
; Recent errors: 6
;
; === Last 10 Log Entries ===
; ...
```

## Configuration Details

### Development (env/dev/resources/logback.xml)

- **Console output**: Yes (verbose, DEBUG level)
- **File output**: Yes (app.log, error.log, xtdb.log)
- **Application logs**: DEBUG level
- **XTDB logs**: INFO level (compactor at DEBUG)
- **Third-party libs**: WARN level
- **Rotation**: 100MB files, 30 day history, 3GB cap

### Test (env/test/resources/logback.xml)

- **Console output**: Yes (minimal, INFO level)
- **File output**: No
- **Application logs**: INFO level
- **XTDB logs**: WARN level
- **Third-party libs**: ERROR level (quiet during tests)

### Production (env/prod/resources/logback.xml)

- **Console output**: Yes (minimal, no thread names)
- **File output**: Yes (app.log, error.log, xtdb.log with gzip)
- **Application logs**: INFO level
- **XTDB logs**: INFO level
- **Third-party libs**: WARN/ERROR level
- **Rotation**: 200MB files, 60 day history, 10GB cap

## Suppressed Loggers

The following noisy third-party libraries are set to WARN or ERROR:

- `org.eclipse.aether` - Maven dependency resolution
- `io.methvin.watcher` - File watching
- `org.eclipse.jgit` - Git operations
- `com.zaxxer.hikari` - Connection pooling
- `org.apache.http` - HTTP client
- `org.xnio.nio` - NIO operations
- `io.undertow` - HTTP server
- `org.apache.arrow` - Arrow data format
- `io.netty` - Network operations

## AI Agent Integration

This logging setup is optimized for AI agents and LLM-based analysis:

### Structured Format
- Every log line is machine-parseable
- Consistent timestamp format (ISO 8601)
- Fixed field positions for easy extraction
- One entry per line (no multi-line log entries)

### Error Isolation
- Dedicated `error.log` file for quick problem identification
- AI agents can check this file first when diagnosing issues
- Includes full stack traces

### REPL Functions
- `(user/logs)` - Quick log access without leaving REPL
- `(user/log-summary)` - Overview of log activity
- Filtering by level, file, and text content
- No need to shell out or switch contexts

### Log Rotation
- Automatic cleanup prevents unbounded growth
- AI agents don't need to handle massive log files
- Recent logs always available at fixed paths

## Best Practices

### For Developers

1. **Use appropriate log levels**:
   - `ERROR` - Something failed, needs human attention
   - `WARN` - Unexpected but handled, may need attention
   - `INFO` - Normal operation, key milestones
   - `DEBUG` - Detailed diagnostic information

2. **Log contextual information**:
   ```clojure
   (log/info "Processing ticker" ticker "for date" date)
   (log/error ex "Failed to ingest data for" ticker)
   ```

3. **Don't log sensitive data**:
   - No API keys, passwords, tokens
   - Mask PII if necessary

### For AI Agents

1. **Start with log-summary**:
   ```clojure
   (user/log-summary)  ; Get overview first
   ```

2. **Check error.log for problems**:
   ```clojure
   (user/logs :file :error :lines 50)
   ```

3. **Search for specific issues**:
   ```clojure
   (user/logs :grep "Exception")
   (user/logs :grep "Failed")
   ```

4. **Filter by severity**:
   ```clojure
   (user/logs :level :error)
   (user/logs :level :warn)
   ```

## Troubleshooting

### Logs not appearing

1. Check system is using correct profile:
   ```bash
   ./bin/run  # Uses dev profile (env/dev/resources/logback.xml)
   ```

2. Verify logback.xml is on classpath:
   ```bash
   ls env/dev/resources/logback.xml
   ```

3. Check log directory exists:
   ```bash
   ls -la logs/
   ```

### Too much noise in logs

Increase log level for noisy namespaces in `logback.xml`:

```xml
<logger name="noisy.namespace" level="WARN" />
```

### Not enough detail

Lower log level in `logback.xml`:

```xml
<logger name="ml-options" level="DEBUG" />
```

Or use DEBUG level for specific namespace:

```xml
<logger name="ml-options.data.ingest" level="DEBUG" />
```

## References

- [Logback Documentation](https://logback.qos.ch/manual/)
- [SLF4J API](https://www.slf4j.org/api/)
- [Structured Logging Best Practices](https://blog.valerauko.net/2022/12/09/structured-logging-in-clojure/)
- [AI Agent Log Analysis](https://www.adopt.ai/glossary/agent-logs)

## Implementation Notes

### Why Logback?

1. **Standard in Clojure ecosystem** - Works with clojure.tools.logging and SLF4J
2. **Powerful configuration** - XML-based, supports profiles, rotation, filtering
3. **Battle-tested** - Used in production by many Clojure applications
4. **Good performance** - Async appenders, efficient buffering
5. **Kit framework standard** - Consistent with Kit template patterns

### Why separate environment configs?

- **Dev**: Verbose logging to files + console, helps during development
- **Test**: Minimal logging, only console, reduces test output noise
- **Prod**: File-only logging with compression, optimized for production

### Why separate error.log?

AI agents and humans need quick access to errors without grepping through all logs. The `error.log` file contains ONLY ERROR level entries, making problem diagnosis faster.

### Log file locations

All logs go to `logs/` directory in project root:
- Consistent location regardless of environment
- Easy to find and access
- Gitignored to avoid committing logs
- `.gitkeep` ensures directory exists in repo

## Future Enhancements

Consider for future iterations:

1. **JSON structured logging** - For advanced log aggregation (ELK stack, CloudWatch)
2. **Async appenders** - For high-throughput scenarios
3. **MDC (Mapped Diagnostic Context)** - For request tracing
4. **Log aggregation** - Send to central logging service
5. **Metrics integration** - Emit metrics from log events

For now, the current setup provides a solid foundation that's both human and AI-agent friendly.
