---
type: issue
status: open
severity: cleanup
tags: [issue, deletion]
---

# Make fresh CLJC namespaces portable or name them CLJ

## Problem

The initial audit found four fresh `.cljc` namespaces with unconditional JVM
code. The current 21-file audit found a broader false-claim class: eleven more
namespaces unconditionally loaded JVM-only `.clj` owners, usually
`seon.schema.edn`, even when their own function bodies were portable. The CLJS
build is off, but `.cljc` still claims that the complete namespace is portable.

## Evidence

Line references below name the pre-rename source audited on 2026-08-02.

| File | Verdict | Deciding evidence |
|---|---|---|
| `src/my/message.cljc` | convert | Unconditional JVM schema loader require/call at `:60-67`. |
| `src/my/run.cljc` | convert | Unconditional JVM schema loader require/call at `:27-34`. |
| `src/seon/ai.cljc` | convert | JDK HTTP/IO imports at `:67-71`, JVM exception taxonomy at `:607-611`, and the JVM HTTP body/stream path at `:689-817`. |
| `src/seon/ai/tokens.cljc` | leave | Portable arithmetic and string operations only at `:28-56`; no platform dependencies or conditionals. |
| `src/seon/cluster/instruction.cljc` | convert | Unconditional JVM schema loader require/call at `:5-7`. |
| `src/seon/cluster/message.cljc` | convert | Unconditional JVM schema loader require/call at `:59-65`. |
| `src/seon/cluster/prompt.cljc` | convert | Unconditional JVM `seon.context`, `seon.render`, and schema loader dependencies at `:11-19`. |
| `src/seon/cluster/reply.cljc` | convert | Unconditional JVM schema loader require/call at `:55-62`. |
| `src/seon/cluster/run.cljc` | convert | Unconditional JVM schema loader require/call at `:61-71`; its sole conditional was a catch class at `:979`. |
| `src/seon/cluster/wake.cljc` | convert | Unconditional JVM `clojure.core.async` and schema loader dependencies at `:64-72`. |
| `src/seon/cluster/work.cljc` | convert | Unconditional JVM schema loader require/call at `:59-65`. |
| `src/seon/config.cljc` | convert | `clojure.java.io`, `PushbackReader`, `Runtime`, and JVM byte encoding at `:15-21,107-119,183,221-223`. |
| `src/seon/print.cljc` | leave | JVM schema loading and numeric classes are confined to CLJ reader branches at `:6,29-40,539`; shared printing code is portable. |
| `src/seon/program.cljc` | leave | The EDN reader require/call is selected by reader conditionals at `:5-6,82`; remaining code is portable data processing. |
| `src/seon/reconcile.cljc` | leave | Portable Datahike/data transformations only; zero reader conditionals and no host API use. |
| `src/seon/render/value.cljc` | convert | Unconditional JVM `seon.sci.admit` dependency at `:9` and calls at `:138-148,259-260`. |
| `src/seon/schema.cljc` | convert | Unconditional `bytes?`, `MessageDigest`, `format`, and byte-array methods at `:356-372`; the retired CLJS registration branch was at `:1083-1106`. |
| `src/seon/schema/datahike.cljc` | convert | Unconditional dependency on the JVM-only `seon.schema` owner and its live registry across the namespace. |
| `src/seon/schema/form.cljc` | leave | Pure form inspection with core collection operations at `:1-90`; no platform dependencies or conditionals. |
| `src/seon/schema/internal.cljc` | leave | Portable Malli/core operations; its only platform distinction is the conditional catch at `:277`. |
| `src/seon/sci/reader.cljc` | leave | Default readers and catch classes are correctly isolated in reader conditionals at `:32-34,144,624`. |

`clj-kondo --lang cljs` found the direct JVM symbol errors in `ai`, `config`,
and `schema`, but did not reject unavailable `.clj` requires in the other
eleven files. Source availability plus unconditional namespace loading is the
deciding falsifier for those files; zero lint errors alone was not portability
proof.

## Owner

Each capability family: portable pure core plus one thin JVM leaf, or a truthful
`.clj` namespace when no portable consumer exists.

## Acceptance

Every remaining `.cljc` namespace passes CLJ and CLJS load/lint for its
unconditional forms. JVM-only owners use `.clj`; reader conditionals occur only
at platform entry functions, and retired compatibility branches are deleted.

## Resolution evidence

The earlier `cluster.loop` conversion landed as `3b324afcc`. The current wave
renamed every other false claimant and collapsed every reader conditional in a
renamed file to its CLJ branch:

- `290416d38` — `seon.ai`; focused gate 33 tests / 128 assertions.
- `4d59e0b5c` — `seon.config`; focused gate 14 / 59.
- `2b6d84dc2` — `seon.schema`; focused gate 18 / 173.
- `55b77469a` — `seon.schema.datahike`; focused gate 4 / 9.
- `0413f07e7` — `seon.render.value`; focused gate 10 / 28.
- `02537f7e6` — `my.message`; focused gate 5 / 44.
- `7f3f0fce7` — `my.run`; focused gate 4 / 24.
- `3e364aee3` — `seon.cluster.instruction`; focused gate 4 / 16.
- `d393def00` — `seon.cluster.message`; focused gate 12 / 29.
- `a83f39dce` — `seon.cluster.prompt` plus its stale `seon.error` docstring;
  focused prompt/error gate 27 / 84.
- `64ddadd6c` — `seon.cluster.reply`; focused reply/reader gate 24 / 184.
- `1be7cd198` — `seon.cluster.run`; focused run/reader gate 26 / 219.
- `147a38319` — `seon.cluster.wake`; focused gate 10 / 29.
- `8d53e1c74` — `seon.cluster.work`; focused gate 9 / 60.

Exactly seven `.cljc` files remain. A CLJS clj-kondo pass over all seven
reports 0 errors / 32 warnings. Current owned source, tests, conventions,
architecture, reference docs, and `AGENTS.md` contain no stale path for a
converted file. `resources/seon/schema.edn:2443` still names
`seon.schema.cljc`; that file was explicitly protected from this wave.
