---
type: research
status: blocked
tags: [research, runtime]
---

# U1 fuel calibration — 2026-07-23

## Status

Calibration did not run. Recording numbers before the production entry and
both claimed tier installations exist would measure a probe rather than the
governing mechanism and could not justify Ruling 27 defaults.

## Shortest falsifiers

- Bun production source falsifier: `src/seon/eval.cljs:1185-1292` calls
  `cljs.js/eval-str`, not SCI. It has no `:interrupt-fn` installation.
- Synchronous-loop falsifier: `src/seon/eval.cljs:1294-1355` uses a Promise
  timer and explicitly reports that JavaScript has no in-thread preemption.
- Config-fact falsifier: `src/seon/config.cljs:32-36` delegates new manifest
  schemas to `seon.config.resolve`, while the U1 grant omits
  `src/seon/config/resolve.cljc`.
- Session-cell falsifier: `src/seon/host.clj:100-122` reuses retained contexts
  across wire sessions, so a generated SCI function must not retain a closure
  over the session that originally defined it.

## Raw data

No step or millisecond samples were collected. No defaults were chosen.

The representative corpus, before/after full-suite timings, and guard
microbenchmark remain pending until the issue
[[guarded-eval-door-lacks-a-bun-installation-and-config-owner]] is resolved.

## Required resumed measurement

Run counting-only mode through the exact landed entry over the existing eval
fixtures and authored renders on JVM SCI and the explicitly named CLJS SCI
tier. Record every sample's invocation class, tier, guarded steps, and elapsed
milliseconds; compute P99.9 from the raw samples; choose each default at no less
than 100 times that measured legitimate P99.9; then rerun the same corpus with
enforcement enabled.
