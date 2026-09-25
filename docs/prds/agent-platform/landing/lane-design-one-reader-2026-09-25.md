---
type: landing
status: committed; awaiting root adoption (JVM restart first, for the edamame change) and root test runs
created: 2026-09-25
design: docs/research/agent-platform/one-reader-collapse-2026-09-25.md (option A, reviewed 9b78e96c4 REVISE → 5407f1d65 ACCEPT-AFTER-FIXES)
---
# Lane design-one-reader-opus: option A slices S1–S3 (2026-09-25)

The one reader (`seon.sci.reader`) already parses through edamame. This cut makes three changes:
- it deletes outputs that nothing reads;
- it fixes the end-of-text number bug in edamame, where it is produced;
- it lets evaluation reads resolve syntax-quote and `::alias/k` through the evaluating SCI context, instead of our own
  resolver.

Reads without a context (reply planning, the JVM MCP stage) keep the SCI wrapper and the resolver built from the
aliases and refers their callers supply.

## Broken or still open (first)

- **Adoption order.** Commit 3c4b247a7 changes `reference-code/edamame`. **Restart the JVM (`bin/seon down && bin/seon start`)
  before adopting fa6c0977a.** Without the patched edamame loaded, fa6c0977a (S2b) regresses a number that ends the text:
  `"1 2"` becomes one error "Range [2, 1) out of bounds". The scratch run of `a-number-ending-the-text-keeps-its-source-and-span`
  shows exactly that against today's loaded edamame (8 failures, all this class). The adoption guard ("JVM RESTART NEEDED:
  loaded dependencies changed") should refuse it anyway.
- **Recovery cost is superlinear. This is a new defect, not fixed here: recovery is unchanged in A.**
  - Measurements: `k` unclosed lines `"(x\n"` in one read took, for k = 4, 8, 16, 32 and 64 (12–192 characters),
    0.65, 1.2, 5.1, 22.6 and **124.6 ms**.
  - It is worse than O(k·n). One suspect, unproven: `:edamame/containers` grows with nesting depth on every failure.
  - The `max-source` dial is **1,048,576**, so a hostile reply can cost minutes.
  - A first probe at k = 128–512 ran past the 60 s MCP timeout inside default. It was bounded, and it finished.
  - Root: please file `docs/seon/issues/reader-recovery-cost-is-superlinear-in-broken-spans.md`. This lane's files do
    not include issues.
- **SCI renamed refers.** `(:require [foo.bar :refer [x] :rename {x renamed-x}])` followed by `` `renamed-x `` gives
  `probe.a/renamed-x`, from both SCI and the resolver it replaces. Clojure gives `foo.bar/x`. This is an SCI resolver gap in
  `seantempesta/sci`, not a regression; the regression leaves it out and says so.
- **Not installed.** Nothing here was adopted. Hook publication shows `:on`, but the loaded `seon.sci.reader` still had
  `declaration-occurrence` after these commits, so adoption is root's checkpoint. No test request was run (LOAD RULE).

## Changes (commits)

| Commit | Slice | Change |
|---|---|---|
| 47fe167f1 | design | Five Astra fixes folded into the design, in place |
| 4d387bca3 | S1 | `declaration-occurrence` (45), `nested-executable-declarations` (22) and the `::refer-all-targets` bookkeeping deleted, along with their merge and dissoc. The recovery docstring now calls its anchor a heuristic. The test assertion on the deleted `::nested-declarations` is dropped |
| 196ea5e (edamame), 3c4b247a7 (gitlink) | S2a | `read-number`: `(when ch (r/unread rdr ch))`, matching `read-token`'s guard |
| fa6c0977a | S2b | The `peek-char` exhausted-reader workaround is deleted; an event's end is the reader cursor. The end-of-text branch that ends the last event at the text's end stays |
| 7b6c63e3c | S3 | `one-event` binds `sci/ns` and passes `:seon.sci.reader/ctx`. With a context, `parse-options` omits `:auto-resolve`/`:syntax-quote`, so SCI resolves. `read` threads `::ctx`, and so does `read-events` (no per-read `sci/init` when a context is given). Recovery resumes as `(merge reading-context ::reading-state)`, which fixes a separate bug: after one unreadable span, deferral, features and tags were dropped. Tests below |

**Net lines** (from 47fe167f1 to HEAD):

| File | Added | Removed | Now |
|---|---:|---:|---|
| `reader.cljc` | 65 | 132 | 919 → 852 |
| `eval.clj` | 8 | 4 | |
| `reader_test.clj` | 83 | 28 | |
| edamame | 2 | 1 | |

Source net −63.

**Deleted paths:**
- `declaration-occurrence` (45 lines);
- `nested-executable-declarations` and its call (29);
- `::refer-all-targets` (6);
- the EOF `peek-char` branch (5);
- `sci/init` per read on the context path;
- the `with-redefs` over `sci/init` in `explicit-reader-policy-overrides-a-hostile-sci-context`.

## Evidence: default pid 39484 (fresh store), JVM mode, scratch namespaces removed afterwards

**R1, installed and fresh.** Through the MCP SCI entrance, the *read form* of `` (quote `(inc String join)) `` is
`(clojure.core/sequence … (quote user/inc) (quote user/String) (quote user/join))` (101 ms, 257 MB allocated: the
existing allocation issue).

The scratch matrix used a real SCI context with `probe.a` (`:refer-clojure :exclude [inc]`, alias `fb`, `:refer [join]`,
`:import [java.util UUID]`, intern `local`, a `defn inc`) and `probe.b` (alias `pa`). S3 reads equal SCI's own
`parse-next+string` on 10 of 10 cases. In probe.a:
- `inc` → `probe.a/inc`, `dec` → `clojure.core/dec`;
- `String` → `java.lang.String`, `UUID` → `java.util.UUID`;
- `join` → `clojure.string/join`, `fb/y` → `foo.bar/y`, `local` → `probe.a/local`.

In probe.b, `inc` → `clojure.core/inc`, `UUID` → `probe.b/UUID` (not imported there), and `pa/local` → `probe.a/local`.
Both refuse `::pa/k` in probe.a and `::fb/k` in probe.b. The old resolver gave `probe.a/dec`, `probe.a/String` and
`probe.a/UUID`.

**R2, the edamame fix.** A renamed scratch copy of the patched `edamame.impl.parser` was compared with the loaded one on
15 inputs, reading source-logging cursors:
- A number at EOF now ends exactly: `1` ends at 1:2, `12` at 1:3, `-1.5` at 1:5, `1N` at 1:3, `0x1F` at 1:5, and the
  `3` in `…\r\n3` at 3:2.
- The loaded parser is one column short on each of these.
- Numbers before a delimiter or whitespace (`1)`, `1 ;c`, `(1)`, `[1 2]`) are unchanged, and so is the logged source
  text.

**R3, equivalence of the context-free path.** Corpus of 28:
- the probe inputs;
- `reply.clj` and `reader_test.clj` whole;
- the 1 stored reply and 8 stored eval sources on the fresh store.

The relation: `pr-str` equality after removing `:seon.error/at` and the deleted keys. Results:
- S1 against installed: 28 of 28 equal.
- S3 without a context against installed: 25 of 28. The 3 that differ are exactly the numbers at EOF (`1`, `1 2`, the
  trailing `3`), which is S2b waiting for the restart.

Not checked: gensym numbering (both equal as printed) and regex flags (no flagged regex in the corpus).

**R4, recovery scaling.** The numbers are in "Broken" above. A clean 7.6 KB text of 400 forms reads in 6.1 ms. One broken
span in it costs 6.7 ms, eight cost 12.5 ms.

**R5, comment-led admission.** On disposable branch `design-one-reader-opus` (now retired),
`; a comment-led declaration…\n(defn r5-comment-led {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))` evaluated, and
`(r5-comment-led "not-an-int")` was refused by its armed contract. That is the baseline. S3 leaves `::line/::column`
and `::source` unchanged, so root re-runs this after adoption.

**Contracts** (scratch copies are unarmed, so this was checked explicitly). With the carried projection's registry, 37
of 37 S3 outputs are valid against `read`'s declared output `[:or [:vector :map] :seon.sci.reader/failure]`: the corpus,
refused tags and `#=`, the oversize bound, and the context-path results for `` `…``, `::fb/k`, `::nope/k`, `(+ 1` and
`#foo/x`. The input map carrying an SCI context validates as `[:cat :map]`. The context never enters stored error data:
`reader-error` copies only selected facts, and the thrown `::reading-state` comes from read-events' own state, which has
no context.

**Tests, run against a scratch copy of the edited reader** (keywords kept as `:seon.sci.reader/…`): 10 reader tests, 141
assertions pass. The one failure is `a-number-ending-the-text-keeps-its-source-and-span`, the predicted S2b-before-restart
failure. The passing tests:
- the new `syntax-quote-and-auto-resolution-follow-the-evaluating-context`;
- the new `recovery-keeps-the-callers-reading-policy`;
- the converted `explicit-reader-policy-overrides-a-hostile-sci-context`;
- `declaration-and-namespace-semantics-use-resolved-operator-identity`, `read-failures-are-isolated` and
  `structured-error-kind-classification`;
- `namespace-tracking-has-repl-semantics-and-fails-closed`, `renamed-and-refer-all-bindings-resolve-to-exact-targets`
  and `fences-are-whitespace-between-reader-forms`.

**Timing, parent (S1 scratch) against change (S3 scratch)**, both unarmed. Each figure is µs and bytes per read, the mean
of 2000 calls after 300 warm-up calls, two rounds:

| Input | Parent | Change |
|---|---|---|
| `(+ 1 2)` | 16.2 / 15.6 µs, 47.7 KB | 14.1 / 11.7 µs, 31.7 KB |
| `defn` with a contract | 27.1 / 20.0 µs, 69.7 KB | 18.3 / 17.0 µs, 54.0 KB |
| `` `(inc String join) `` | 19.1 / 16.3 µs, 60.6 KB | 20.6 / 12.7 µs, 45.0 KB |

There is no regression: about 16 KB less per read, because `sci/init` is gone. The armed comparison is root's to run
after adoption.

## Root runs (serially, after `bin/seon down && bin/seon start`, then adoption)

- `bin/test-check default --ns seon.sci.reader-test`
- `bin/test-check default --test seon.sci.eval-test/a-refusal-keeps-its-declared-declared-schema-at-both-entrances`
- `bin/test-check default --ns seon.sci.entry-gate-test`
- `bin/test-check default --ns seon.cluster.reply-test`
- `bin/test-check default --ns seon.run4-reader-test`
- `bin/test-check default --ns seon.repl-parity-test` (it exercises `evaluate-for-install`'s supplied event and the no-forms error handoff)
- `bin/test-check default --ns seon.cluster.turn-test`
- Afterwards, at the MCP SCI entrance: `` (quote `(inc String join)) `` must read
  `(quote clojure.core/inc) (quote java.lang.String) (quote user/join)`, and R5's comment-led `defn` must still be refused
  when called with a string.
