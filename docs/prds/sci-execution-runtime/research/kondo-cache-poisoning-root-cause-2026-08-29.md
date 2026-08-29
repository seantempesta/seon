---
type: research
status: complete
tags: [research, tooling, edit-hook]
---

# Clj-kondo cache poisoning root cause

## Answer

`bin/seon-hook` writes the shared `.clj-kondo/.cache`. Both of its clj-kondo
commands omit `--cache` and `--cache-dir`, so clj-kondo discovers the repository
`.clj-kondo` directory and reads and writes its `.cache/v1` directory. The
PreToolUse command is the dangerous one: it lints reconstructed, not-yet-written
content on stdin while assigning the real file path with `--filename`
(`bin/seon-hook:165-180,329-358`). That filename makes the stdin analysis
eligible for persistence. Clj-kondo flushes the definitions it managed to
analyze even when the buffer has later syntax errors and the hook rejects the
edit. The rejected edit can therefore leave the cache describing only a prefix
of a namespace while the source file remains complete.

This is a recurrence of the already-proven runtime-reply failure class. The
reply analyzer used to persist synthesized partial namespaces into the build
cache; it was repaired by adding `:cache false`
(`docs/seon/issues/archive/clj-kondo-vswap-arity-blocks-program-publication.md:18-50,93-114`;
`src/seon/fn/analyzer.clj:357-361`). The edit hook is another per-buffer analyzer
that never received that constraint.

The exact 2026-08-17 initiating edit is not recoverable from Git. Git records
none of the three target files changing in the cache timestamp window, and a
blocked PreToolUse edit leaves no tree change by definition. The writer is
proven; attribution of the old bytes to a particular tool event would be a
guess.

## Dependency ledger

- Seon pins clj-kondo commit
  `57252e07975710aa579b24f0d1b2b1e04195caa2` (`deps.edn:16-20`). The installed
  binary used by the probe reports `clj-kondo v2026.07.24`.
- The CLI resolves an unspecified cache from the nearest `.clj-kondo`
  directory; `--cache false` disables both cache reading and writing
  (`reference-code/clj-kondo/README.md:171-180,264-271`).
- Stdin receives `--filename` as its analyzer filename
  (`reference-code/clj-kondo/src/clj_kondo/impl/core.clj:521-527`).
- Cache synchronization runs after input processing regardless of findings
  (`reference-code/clj-kondo/src/clj_kondo/core.clj:233-245`). It writes every
  newly analyzed, non-empty namespace definition map
  (`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:146-166`).
- The disk filename is derived from language and declared namespace, not source
  path (`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:16-21`). Moving a
  file without changing its `ns` cannot create an entry for another namespace.
- The write exclusion recognizes literal `<stdin>`, but a supplied filename is
  no longer `<stdin>` (`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:38-53`).

## Shared-cache writer inventory

### Edit hook: proven unsafe writer

Both Codex and Claude invoke `bin/seon-hook` before and after edits
(`.codex/hooks.json:2-23`; `.claude/settings.json:2-23`). Linting is enabled
(`.claude/seon-hook.edn:10-11`).

The two commands are:

```text
PreToolUse:
clj-kondo --lint - --filename ABSOLUTE_FILE --config '{:output {:format :edn}}'

PostToolUse:
clj-kondo --lint ABSOLUTE_FILE... --config '{:output {:format :edn}}'
```

Neither branch supplies `--cache false` or a private `--cache-dir`
(`bin/seon-hook:165-180`). PreToolUse reconstructs a prospective Write or Edit
and passes it on stdin (`bin/seon-hook:305-323,329-358`). PostToolUse lints the
resulting files (`bin/seon-hook:434-451`). Both run with the repository as their
working directory, so both write the shared cache. PreToolUse can persist a
partial definition set before returning error findings; the hook then blocks
the edit but does not roll back the cache write.

There was also an unplanned exact confirmation during this research. Creating
the scratch source `tmp/kondo-poison-probe/source/clojure/test.clj` through an
edit tool triggered PostToolUse, which replaced the real
`clojure.test.transit.json` with a 221-byte entry. The orchestrator restored only
that entry by linting the true Clojure source (5,387 bytes), but a delayed hook
event rewrote it again to 217 bytes. The orchestrator owns the final targeted
restoration after this lane returns. No deliberate real-cache probe was run.
This incident proves the configured hook, not merely a hand-reconstructed
command, is a shared-cache writer; it also shows that remediation can race an
already-admitted hook invocation.

### Source publication: intended shared writer

`seon.fn.analyzer/invoke-kondo` calls the library API with `:lang :clj`,
`:config-dir ".clj-kondo"`, and `:cache-dir ".clj-kondo/.cache"`
(`src/seon/fn/analyzer.clj:123-131`). Complete program analysis passes source
paths with `{:lint paths}` (`src/seon/fn/analyzer.clj:133-153`) and intentionally
writes the shared cache. It analyzes complete selected files, not stdin.

The per-form path is correctly isolated: it passes `:cache false`, `:lint ["-"]`,
and a synthetic filename (`src/seon/fn/analyzer.clj:352-361`). It cannot write
the shared cache. That is the precedent the edit hook should follow.

One other runtime path is not isolated. `seon.fn/runtime-analysis` constructs a
namespace prelude from database program rows, appends one planned form, and
calls cache-enabled `analyzer/analyze` with `{:paths ["-"]}` under
`with-in-str`; `seon.fn/analyze-form` calls it for each planned form
(`src/seon/fn.clj:503-525,629-655`). Because `analyzer/analyze` retains the
shared `:cache-dir`, this synthesized stdin namespace can also replace a shared
entry. It normally analyzes an agent's current namespace and is not evidence
for any of the three 08-17 targets, but it is a second violation of the same
boundary. Any direct `analyzer/analyze` call from a JVM whose working directory
is the repository is likewise a shared writer; `bin/test` contains those calls
inside copied roots as described below.

### Dependency warmer: intended shared writer

`seon.dev.clj-kondo/ensure-dependency-cache!` runs this command in its selected
root:

```text
clj-kondo --lint CLASSPATH --dependencies --parallel --copy-configs
```

It has no `--cache` or `--cache-dir`, so for the normal repository root it
writes the shared cache (`script/seon/dev/clj_kondo.clj:49-60,62-90`). It is
called before `bin/seon init` (`script/seon/fresh_operator.clj:2180-2192`) and
by the retained changed-test surface
(`script/seon/dev/changed_test.clj:282-303`). It skips the command when its
input digest matches and a cache is present
(`script/seon/dev/clj_kondo.clj:49-56`).

This writer consumes complete classpath sources rather than stdin or partial
content. The exact command, redirected to private config and cache directories,
produced a 5,105-byte `clojure.test` entry containing `deftest`, `is`, and
`testing`. This rules out the command's normal current input as a reproducer;
it remains capable of shared writes by design.

### Retained changed-test host analysis: shared writer when called

`seon.dev.changed-test/analyze-host` runs:

```text
clj-kondo --lint HOST_FILE... --config
'{:output {:format :edn}
  :analysis {:var-usages false :var-definitions {:shallow true}}}'
```

It omits cache flags and runs in the selected root
(`script/seon/dev/changed_test.clj:31-32,130-145`). Its full-file corpus is
`src`, `script/seon/dev`, and the operator and writer tests
(`script/seon/dev/changed_test.clj:71-86`). Thus a direct
`seon.dev.changed-test/run-changed!` can write the shared cache and can refresh
`seon.dev.state`, but it does not include `resources/seon/operator/state.clj` or
`resources/seon/operator/runtime.clj`. It has no stdin or prospective-content
path. The current edit hook and `bin/test` do not call this retained surface
(`docs/seon/reference/linting-setup.md:42-60`).

### `bin/test`: analysis writes only isolated cache copies

`bin/test` contains no clj-kondo CLI invocation. It copies `.clj-kondo`, rather
than symlinking it, into its isolated run root and each worker checkout
(`bin/test:281-325`). It changes directory to the run root before launching the
suite (`bin/test:427-464`). The coordinator builds the program manifest over
`src` and `test`, which reaches the cached `seon.fn.analyzer` path
(`src/seon/test/runner.clj:1556-1570`), but that path resolves
`.clj-kondo/.cache` inside the isolated run root. It cannot write the repository
shared cache. The same isolation was already present at commits `3e013dfba` and
`4cd362154`.

### Non-writers and absent CI

- `bin/lint` runs `clj-kondo --cache false --lint PATH...`
  (`bin/lint:117-126`). It cannot read or write the shared cache.
- The source tree has no `.github` workflow or other CI command invoking
  clj-kondo.
- `.lsp/config.edn:20-22` disables clj-kondo diagnostics, so the LSP is not a
  hidden writer here.
- The `deps.edn:144-147` clj-kondo line is a usage comment, not an invocation.

## Private-cache reproduction

All deliberate commands used explicit cache directories below
`tmp/kondo-poison-probe/`. The real cache was not a probe target.

### Minimal reproducer

With a new private cache, linting the consumer below resolves the packaged
builtin `clojure.test/deftest` and reports zero findings:

```clojure
(ns probe.consumer
  (:require [clojure.test :refer [deftest]]))

(deftest example)
```

Then lint this two-form buffer under a matching source filename:

```clojure
(ns clojure.test)
(def placeholder true)
```

```sh
clj-kondo \
  --cache-dir tmp/kondo-poison-probe/cache-clojure-test \
  --lint - \
  --filename tmp/kondo-poison-probe/source/clojure/test.clj
```

The command creates a 186-byte
`cache-clojure-test/v1/clj/clojure.test.transit.json` whose Transit value is:

```clojure
{placeholder {:row 3, :col 1, :name placeholder,
              :ns clojure.test, :top-ns clojure.test, :type :boolean}
 :filename "tmp/kondo-poison-probe/source/clojure/test.clj"}
```

Linting the unchanged consumer against that cache then reports exactly
`Unresolved var: deftest`. The disk entry shadows the complete packaged builtin
entry.

A bare `(ns clojure.test)` did **not** create or replace an entry; one analyzed
definition is the minimal flush trigger. The same result held for an ordinary
namespace: a complete `probe.provider` produced a 308-byte entry; stdin with the
same namespace and only `(def placeholder true)` replaced it with a 190-byte
entry, after which a consumer reported `Unresolved var: provider/retained`.

### Error findings do not prevent the write

This malformed buffer reported four error-level syntax findings:

```clojure
(ns probe.broken)
(def preserved true)
(defn incomplete [
```

Nevertheless clj-kondo wrote a 287-byte cache entry containing only the two
definitions it had reached. The control reran the partial `clojure.test` lint
with `--cache false`; the private cache file's SHA-256 was identical before and
after. Thus the write is controlled by cache configuration, not command success
or finding severity.

As a forensic size cross-check, linting prefixes of the true Clojure 1.12.5
`clojure/test.clj` produced steadily growing partial entries. A prefix through
line 341 produced 1,524 bytes and omitted the later `deftest`; the observed old
entry was 1,516 bytes. Filename and config serialization account for small size
differences. This is consistent with a prefix analysis but does not identify
the old initiating edit.

## 2026-08-17 timeline

The cache timestamps correlate with active linting, but the Markdown moves do
not explain the namespace identities:

- `3e013dfba`, 08:54:49 -0600, moved `seon.dev.markdown` from `script` to `src`
  without changing its bytes or declared namespace.
- `4cd362154`, 09:03:40 -0600, moved the same namespace and its test back under
  `script`, again without changing namespace identity. Its commit message says
  an isolated `bin/seon init` was green.
- The poisoned `clojure.test` entry was stamped about 09:06, close to that init,
  while the other suspect entries were stamped in the wider 08:21-09:06
  interval.

Clj-kondo keys cache files by declared namespace, so either move could only
write `seon.dev.markdown`, never `clojure.test`, `seon.operator.runtime`, or
`seon.dev.state`. Neither `resources/seon/operator/runtime.clj` nor
`script/seon/dev/state.clj` changed in the window. `bin/test` was already
isolated. The normal dependency-warm command reproduced a complete
`clojure.test`, not the partial entry.

The evidence therefore rules the move out as the cause. The 09:06 proximity is
only evidence that lint/init activity was occurring. A transient or rejected
edit to one of the target namespaces remains invisible to Git and is sufficient
to explain the bytes. It is not possible to identify that 2026-08-17 event more
precisely from surviving evidence.

## One constraint

**Only complete canonical-source or dependency analysis may write the shared
cache; every prospective or synthesized-buffer lint must run without a
cache.** Concretely, add `--cache false` to both argv branches in
`bin/seon-hook/run-clj-kondo`, and pass `:cache false` through
`seon.fn/runtime-analysis` just as `analyze-forms` already does. Do not give the
hook a stable private cache: the same rejected-buffer write would poison that
cache and preserve the edit wedge, merely under another directory. A fresh
cache copy per edit would add lifecycle machinery to recover a property
`--cache false` already supplies.

This keeps the responsibilities separate:

- the hook analyzes one prospective/resulting buffer and can never persist it;
- runtime form analysis checks one synthesized namespace and can never persist
  it;
- complete source publication owns first-party cache refresh;
- dependency warming owns dependency cache refresh;
- `bin/lint` and the existing per-form analyzer demonstrate the same no-cache
  rule already works for ephemeral analysis.

Clj-kondo's packaged builtin definitions remain available with disk caching
disabled, as the clean `clojure.test/deftest` control demonstrated. Some
cross-project resolution becomes less complete on the hook's single-file pass;
that is honest. The later complete source-publication analyzer remains the
cached cross-namespace admission authority. A cache-dependent edit veto is not
stronger validation when the cache can contradict source.

## Detection proposal

Make cache corruption name itself only when an unresolved-var finding would
otherwise block an edit:

1. Request structured `:var-usages` in the hook's clj-kondo output and use the
   finding location to obtain the target namespace and var as data.
2. If `.clj-kondo/.cache/v1/<lang>/<namespace>.transit.json` exists, read it as
   Transit and test whether that symbol key is absent. Babashka already reads
   the probe entry with `cognitect.transit`; no text scan is needed.
3. Use the entry's recorded `:filename`, when it is a readable source file, for
   one `--cache false` analysis. If that source or the packaged builtin defines
   the missing var, return a diagnostic such as
   `:seon.hook/cache-entry-missing-var` naming the cache path, target symbol,
   recorded source, byte size, and modification time. Do not report the
   ordinary unresolved-var as if the source were wrong.

The normal path pays no extra process. The diagnostic path is targeted, avoids
a hand-maintained namespace roster, distinguishes a genuinely nonexistent var
from a missing cache definition, and reports absence of the required signal as
the defect rather than as mysterious edit failure.

## Scratch artifacts

The private inputs, cache directories, Transit entries, and prefix probes are
under `tmp/kondo-poison-probe/`. They are disposable and no production source
uses them.
