---
type: landing
status: landed; tests owed to root
lane: impl-library-replacements-opus
created: 2026-09-25
---

# Lane impl-library-replacements-opus — landing (2026-09-25)

Library-replacement audit (`docs/research/agent-platform/library-replacement-audit-2026-09-25.md`,
116976633) rows taken in rank order, LOW-risk CONFIRMED rows in free files only. What exists: small
local copies of `clojure.stacktrace/root-cause` and hasch's raw SHA-256; the approach is to call the
library at every caller and delete the copy.

## Broken / not proven

- Default is `:faulted` with 21 open foreign faults (status at 21:24Z); unrelated to these slices.
- No namespace was reloaded into default (loading `seon.error` / `seon.test.cache` there would
  redefine default's Vars; hook publication is root's). Equivalence is proven at the REPL on the
  retired Vars versus the library calls; installed load and tests are root-run (commands below).
- Only two slices exist: every other row is MED/HIGH or sits in a held file (table below).

## Slice 1 — D2: `seon.error/root-cause` → `clojure.stacktrace/root-cause`

- Library: `reference-code/clojure/src/clj/clojure/stacktrace.clj:20-26` (follows `.getCause`,
  returns the last Throwable). Not `clojure.main/root-cause` (stops at CompilerException).
- Retired: `seon.error/root-cause` (12 lines + 2 blank). Caller converted: `seon.error/message`
  (the only caller). Require `[clojure.stacktrace :as stacktrace]` added.
- Equivalence (JVM REPL, scratch ns, 0.6 ms): `identical?` results for a lone ex-info, a
  three-deep ex-info → ex-info → RuntimeException chain, a RuntimeException with an
  IllegalStateException cause, and a causeless Exception: `[true true true true]`. Declared input
  is `:seon.error/throwable`, so non-Throwable behaviour is outside the contract.
- Lines: −15 / +1 → net −14.

## Slice 2 — D7 (test/cache half): `seon.test.cache/sha-256` → hasch

- Library: hasch 0.4.100 (JAR only, no reference-code checkout): `hasch/core.cljc:10,17` alias
  `platform/hash->str` and `platform/sha256`; `hasch/platform.clj:20-28` (byte → two lowercase hex
  chars) and `:40-43` (`MessageDigest/getInstance "SHA-256"` `.digest`).
- Retired: `seon.test.cache/sha-256` (6 lines) and the `MessageDigest` import. Callers converted
  inline to `(hasch/hash->str (hasch/sha256 bytes))`: file digests (:59, :213), gitlink digest
  (:198), `inventory-digest` (:238).
- Equivalence: byte arrays empty, `[-1 -128 0 127 1]`, UTF-8 multibyte text, all 256 byte values:
  `[true true true true]`; empty digest `e3b0c442…b855`. Real files `src/seon/error.clj` (129100 B),
  `deps.edn`, `seon.error.edn`: all equal. Timing on the 129 KB file, 200 calls: old 0.045 ms/call,
  hasch 0.044 ms/call (no regression).
- `fn.clj`'s identical `sha-256` (:113) is NOT converted: fn.clj is held by
  impl-shape-mirror-malli-opus / impl-world-injection-opus / impl-repl-s1-revise-2-opus. Follow-up:
  same three-line conversion at `fn/text-context` and `fn/manifest-data` when fn.clj frees.
- Lines: −7 / +1 → net −6.

## Net

Source: −22 / +2 = **net −20 lines**. No test changes (no test referenced either retired Var;
`test_runner_test.clj`'s `#'dev-cache/sha-256` is a different namespace).

## Rows skipped

| Row | Reason |
|---|---|
| D1 destructuring → Malli parse | MED; `program.cljc` held (impl-shape-mirror-malli-opus); `fn/signature.cljc` half needs the same design |
| D3 `web.jvm/split-all` → `str/split` | LOW, but needs a production regex (`#";"`); AGENTS.md requires owner permission, which the audit states it does not grant. No non-regex library splitter preserves `rest` of an all-parts split on a leading `;`. Owner decision |
| D5 tree-seq enumerators | MED; `cluster.clj` held |
| D6 `instrument/var-symbol` → `symbol` | LOW, but `instrument.clj` held (impl-shape-mirror-malli-opus) |
| D7 fn.clj half | `fn.clj` held (see slice 2) |
| D8 reference registry → composite-registry | MED; precedence decision needed |
| D9 cache mechanics → core.cache | MED; exception-retry policy unresolved |
| D10 earliest assertion → Datalog `min` | MED |
| D11 primitive fn predicate | MED; private Malli seam |
| P13 Medley, P12 schema traversal | PLAUSIBLE, MED/HIGH |

## Root runs

- `bin/test-check default --ns seon.error.refusal-test` (includes `the-frame-comes-from-the-root-cause`)
- `bin/test-check default --ns seon.error-test`
- `bin/test-check default --ns seon.test-cache-test`
- `bin/test-check default --ns seon.test.gitlink-digest-test`
- `bin/test-check default --changed seon.error/message`

Commits: see git log for this landing path.
