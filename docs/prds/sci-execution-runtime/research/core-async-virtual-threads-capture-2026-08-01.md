---
type: research
status: active
tags: [research, flow, reference]
---

# core.async virtual threads — vendor announcement capture

Captured 2026-08-01 from the owner's paste of the official Clojure news
post (2025-10-01, Alex Miller):
<https://clojure.org/news/2025/10/01/async_virtual_threads>. Seon pins
core.async `1.10.874-alpha3`, which POSTDATES this (`1.9.829-alpha2`),
so everything below is in our dependency already.

- **go blocks are reimplemented on virtual threads when available
  (Java 21+)** with unchanged semantics; no analyzer/IOC load, faster
  load with Clojure ≥ 1.12.3, faster go compilation. No code changes
  required.
- **`io-thread` and the `:io` thread pool also run on virtual
  threads** (since alpha2) when available.
- **System property `clojure.core.async.vthreads`**:
  - unset (default) — opportunistic: vthreads when ≥ Java 21;
    platform/IOC otherwise. AOT go blocks always IOC.
  - `target` — commit to vthreads: go throws at runtime if vthreads
    unavailable; AOT compiles go blocks for vthreads.
  - `avoid` — never use vthreads; `:io` pool and `io-thread` stay on
    platform threads.
- IOC-compiled go blocks from older versions keep working and
  interoperate on the same channels.

## Seon implications

- The JVM-tuning finding "agent graph `:io` execution uses a cached
  platform-thread pool" must be diagnosed AGAINST this: the dependency
  already owns vthread `:io` — if ours is platform, either our flow
  executor wiring bypasses core.async's own `:io` context, or the
  property/detection path is off. The fix should USE the dependency's
  mechanism, never build a second executor.
- `-Dclojure.core.async.vthreads=target` is the honest dial for Seon
  (CLJ-only, source-run, JDK 26): commit to vthreads and make absence
  LOUD instead of a silent platform fallback — consistent with the
  fail-loud law. Belongs in the jvm-opts block pending the tuning
  lane's review.
