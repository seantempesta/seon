---
type: research
status: active
tags: [research, sci, testing]
---

# REPL-parity test mining (2026-08-01)

Owner goal (rulings #24/#26): agents live in a REPL that is as close to
stock Clojure as possible. This document mines the **test suites of real
REPL implementations** for the behavior classes they actually pin, so our
parity checklist is discovered rather than invented, and names the
verification route for each class.

It cross-references the two existing inventories:

- `research/sci-repl-realism-audit-2026-08-01.md` — 21 divergences (D1–D21),
  five smoothings (S1–S6), five honesty questions, five warts;
- `plan/print-path-design-2026-08-01.md` — the SEALED dispatch table, the
  closed admitted-node grammar, and nine acceptance rows.

## Submodule ledger

**Every named candidate was already vendored.** The inventory
(`ls reference-code/`) was checked first; nothing was duplicated.

| Submodule | SHA / tag | Status | Justification |
|---|---|---|---|
| `reference-code/sci` | `937d392a008e` (`v0.2.4-1070-g937d392`, our fork `seantempesta/sci`) | already present | sci IS our engine; `test/sci/` is the single most relevant corpus and the only place sci's own promises are pinned |
| `reference-code/clojure` | `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d` (master, shallow) | already present | the ground truth for print faces, `doc`/`source`/`apropos`/`dir`, `ex-triage`/`ex-str`, and reader tags. The shallow clone **does** carry `test/`, which the jar never does |
| `reference-code/babashka` | `0fb349c414e717800be775ba9cb77c95a9eb700d` (`v1.12.218`) | already present | the largest sci-based REPL-parity effort in existence; `test/babashka/impl/repl_test.clj` is a runnable REPL-LOOP corpus (`*1`/`*2`/`*3`, `*e`, `pst`, stderr routing) that no other suite provides |
| `reference-code/edamame` | `38e627467daa3f6f1e5a8eb6421f702d2a940b7f` (`v1.6.42`) | **ADDED** | sci's reader is edamame, not tools.reader (`reference-code/sci/deps.edn:2` pins `borkdude/edamame 1.6.42`; our tree resolves the identical 1.6.42). `seon.sci.reader` wraps it, so D6/S4 is an edamame `:readers` semantics question and we had **no source** for it. Reading it falsified S4's prescription in one line (see G1 below) |

Rejected, with reasons:

- **`clojure/tools.reader` — rejected.** It is not on our path and not
  sci's reader. edamame is (`reference-code/sci/deps.edn:2`). Vendoring
  tools.reader would ground our reader work in the wrong source.
- **`nrepl/nrepl` — rejected for this purpose** (and already vendored
  anyway, `0e75a27e`). Its tests pin the bencode/op protocol and session
  middleware, not REPL *semantics*. Nothing in it constrains a print face,
  an error face, or a var binding.
- **`orchard` / `cider-nrepl` — rejected** (both already vendored). Their
  `info`/`apropos` tests pin tooling metadata lookup, not the REPL surface
  an agent sees.

Probe: `tmp/edamame_tag_probe.clj` (run 2026-08-01, `clojure -M:dev -i`).

## 1. What sci's own suite covers

`reference-code/sci/test/sci/` — 6,565 lines, 25 files. REPL-relevant
namespaces, by what they pin:

| Namespace | Lines | REPL-relevant content |
|---|---|---|
| `repl_test.cljc` | 91 | `doc` (fn/macro/ns/local), `find-doc`, `dir` (+ its failure message), `apropos`, `pst` |
| `io_test.cljc` | 108 | `println`/`print`/`prn`/`pr`/`newline` capture, `*print-length*`, `*print-level*` (`[:a #]`), `*print-meta*`, `*print-namespace-maps*`, `*print-readably*`, `*print-dup*`, `*flush-on-newline*` |
| `error_test.cljc` | 250 | `sci/stacktrace` + `sci/format-stacktrace` frame shapes, analysis-vs-execution location, ex-data encapsulation, arity messages, destructuring locations |
| `namespaces_test.cljc` | 499 | `in-ns`, `ns-publics`, `ns-map`, `ns-unmap`, `find-var`, `find-ns`, `remove-ns`, `ns-aliases`, `as-alias`, docstrings, `loaded-libs` |
| `vars_test.cljc` | 298 | `def` returns the var, dynamic binding, `alter-var-root`, `with-redefs`, `var-get`/`var-set`, `thread-bound?`, `add-watch` |
| `read_test.cljc` | 135 | `read`, reader conditionals, `*read-eval*`, tag fallback, reader resolver, EOF value |
| `pprint_test.clj` | 69 | records via `simple-dispatch`, `*print-namespace-maps*`, `#'clojure.core/inc` |
| `interrupt_fn_test.cljc` | 241 | the time limit: uncatchable, not maskable by `finally`, forge-resistant, host seq producers/materializers |
| `defrecords_and_deftype_test.cljc` | 714 | `repr-test`, `type-test`, `to-string-test` — the record/type print faces |
| `core_test.cljc` | 2,104 | `def-test`, `source-fn-test`, `resolve`, `macroexpand`, `syntax-errors`, `meta-test`, `type-test`, `var-name-test` |

**What sci explicitly marks as divergent from Clojure.** Only ONE
in-suite marker exists — `core_test.cljc:2013`: *":file is not conform
Clojure JVM, maybe fix this another day"*. Everything else is divergence
by *omission*, and two omissions matter to us:

- **`clojure.pprint` is not shipped by sci at all.** `rg "'clojure.pprint"
  src/sci/impl/namespaces.cljc` → no hits. `sci/pprint.cljc` only *extends*
  the host's `simple-dispatch` for `sci.impl.records.SciRecord` and
  `sci.lang.Var`; the namespace itself must be supplied by the host
  (babashka does this: `babashka.impl.pprint/pprint-namespace`, used in
  `repl_test.clj:22-23`, and sci's own `pprint_test.clj:14-16` shows the
  minimal three-entry conf).
- **`clojure.repl` is shipped but incomplete.**
  `namespaces.cljc:2412-2424` provides `dir-fn`, `dir`, `doc`, `find-doc`,
  `apropos`, `source`, `source-fn`, and (CLJ only) `pst`,
  `stack-element-str`, `demunge`. It does **not** provide `root-cause`,
  and `print-doc` is private.
- **`interrupt_fn_test.cljc` is our fork's contribution** (commits
  `6629a49`, `9fa8e46` — upstreamed as #1043/#1044). It is the one place
  our genuine difference is already pinned by an upstream-shaped suite.

## 2. Behavior-class checklist

**59 rows across nine families.** Each row: behavior → the upstream test
that pins it → our current state per the two inventories → verification
route.

Route legend — **(a)** run the upstream case nearly unchanged against our
door behind a harness shim; **(b)** adapt into a `test/seon/` regression;
**(c)** N/A with reason.

### Family A — scalar print faces

| # | Behavior | Upstream test | Our state | Route |
|---|---|---|---|---|
| A1 | `#'ns/name` for a var | `clojure/test/clojure/test_clojure/printer.clj:108-112`; `sci/test/sci/pprint_test.clj:60-69` | D2 open; print-path `::var` row + acceptance D2 | (b) |
| A2 | `##Inf` / `##-Inf` / `##NaN` for Double **and Float** | `printer.clj:188-195` | print-path names the suffixes in the number row; **no acceptance row** | (b) — new |
| A3 | `1N` / `1M` bigint/bigdec suffixes | `printer.clj:87-94` | print-path number row | (b) |
| A4 | char faces `\a`, `\newline` | `test_clojure/reader.cljc:293-331` | print-path char row | (b) |
| A5 | string escaping (`char-escape-string`) | print-path ledger cites `core_print.clj:200-221` | print-path string row | (b) |
| A6 | `*print-readably*` nil → unquoted string | `sci/test/sci/io_test.cljc:102-105` | **absent from both inventories** | (b) — new |
| A7 | `*print-dup*` faces (`#=(java.math.BigInteger. "1")`) | `printer.clj:87-103`; `io_test.cljc:107` | **absent from both** | (c) — `#=` is refused by `seon.sci.reader` by construction; record the refusal as intentional |
| A8 | `#inst` / `#uuid` output faces | `reader.cljc:502-517, 576-584` | print-path rows (yes) | (b) |
| A9 | `#error {…}` face for a Throwable VALUE, round-tripping to `Throwable->map` | `printer.clj:124-138` | **MISSING from the print-path dispatch table entirely** | (b) — new; see gap 2 |
| A10 | `*print-meta*` prefix `^{…}` | `printer.clj:114-122`; `io_test.cljc:57-67` | audit lists `*print-meta*` as already-matching | (b) |

### Family B — collection faces and elision

| # | Behavior | Upstream test | Our state | Route |
|---|---|---|---|---|
| B1 | seqs print with parens, vectors with brackets | `sci/test/sci/io_test.cljc:40-49`; babashka `repl_test.clj:43-44` | **D1**, the highest-severity divergence; print-path list node | (b) |
| B2 | `*print-length*` elision table for seqs — `(...)`, `(0 ...)`, … | `printer.clj:26-36` | D5; print-path `::elided`→`...` | (a) — the whole table is data |
| B3 | `*print-length*` elision table for vectors | `printer.clj:43-53` | same | (a) |
| B4 | **empty coll at length 0/1 prints `()` / `[]`, never `(...)`** | `printer.clj:21-24, 38-41` | **absent from both inventories** | (b) — new; the naive `...` emitter gets this wrong |
| B5 | `*print-level*` table, incl. **level 0 pruning the whole value to `#`** | `printer.clj:55-65` | print-path `::pruned`→`#`; top-level case not in acceptance | (a) — new sub-case |
| B6 | combined level×length matrix (12 rows) | `printer.clj:67-85` | neither inventory pins interaction | (a) — new; strongest single falsifier of the emitter |
| B7 | `#:ns{…}` namespaced-map lifting, 12 cases incl. sorted maps and CLJ-2469/2537 | `printer.clj:140-186`; `io_test.cljc:69-73`; `sci/pprint_test.clj:48-58` | print-path map row cites `core_print.clj:247-268` | (a) |
| B8 | `MapEntry` prints `[:a 1]` | audit D1 last case | print-path vector classification | (b) |
| B9 | record face `#user.R{:a 1, :b 2}` and type face `user.R` | `sci/defrecords_and_deftype_test.cljc:170-184` | D9; print-path `::record`/`::type` | (b) |
| B10 | `#object[cls 0xaddr rep]` shape | print-path ledger `core_print.clj:104-115` | D8/D15/D19; print-path `::object` | (b) |
| B11 | munged fn names are **demunged** for display | `clojure/test_clojure/main.clj:72-79` (`java-loc->source`) | **absent from both**; D15 currently shows `sci.impl.fns$fun$arity_0__75394` | (b) — new; sci ships `clojure.repl/demunge` |

### Family C — REPL session vars

| # | Behavior | Upstream test | Our state | Route |
|---|---|---|---|---|
| C1 | `*1` holds the last value | babashka `repl_test.clj:48, 152` | **D7** — always nil; S3 | (a) |
| C2 | `*2`/`*3` shift correctly across forms | babashka `repl_test.clj:49-50, 153` | D7 | (a) |
| C3 | `*e` holds the last exception | babashka `repl_test.clj:58` | D7/D13 | (a) |
| C4 | **`(ex-data *e)` returns the USER's map, not sci's wrapper data** | babashka `repl_test.clj:58-59` vs sci `error_test.cljc:128-145` | **absent from both inventories** | (b) — new; see gap 4 |
| C5 | `(pst)` with no arg reads `*e` | sci `repl_test.clj:79-91`; babashka `repl_test.clj:55` | D13 | (a) |
| C6 | errors go to `*err*`, values to `*out*` | babashka `repl_test.clj:31-39` | neither inventory separates the streams | (b) — new |
| C7 | multiple forms on one line each print | babashka `repl_test.clj:141-142` | our fold does this; untested | (b) |
| C8 | defs/atoms persist across forms in one session | audit "honesty question 1" (verified live) | genuine, untested | (b) |

### Family D — doc / source / dir / apropos / find-doc

| # | Behavior | Upstream test | Our state | Route |
|---|---|---|---|---|
| D1 | `doc` layout: rule line, `ns/name`, arglists, `Macro`, indented doc | sci `repl_test.cljc:12-41`; babashka `repl_test.clj:181-215` | audit: `doc` already right | (a) |
| D2 | `(doc ns-name)` prints the namespace docstring | sci `repl_test.cljc:38-40`; `clojure/repl.clj:9-11` | untested | (a) |
| D3 | **`(doc catch)` ≡ `(doc try)`** — special-form docs | `clojure/repl.clj:12-13` | untested; sci routes via `special-doc` (`namespaces.cljc:2241`) | (b) — new |
| D4 | `doc` on a non-var local prints nothing | sci `repl_test.cljc:41` | untested | (b) |
| D5 | `find-doc` regex sweep, multi-entry output | sci `repl_test.cljc:43-64` | audit D11 — unresolvable bare | (a) |
| D6 | `apropos` by string / regex / symbol; `[]` when nothing matches | `clojure/repl.clj:33-47`; sci `repl_test.cljc:74-77` | D11 | (a) |
| D7 | `dir` prints sorted publics; `dir-fn` resolves through an alias | `clojure/repl.clj:26-31` | audit: `dir` already right | (a) |
| D8 | `(dir bad-ns)` throws `No namespace … found` | sci `repl_test.cljc:70-72` | untested | (b) |
| D9 | `source-fn` returns exact bytes; `nil` for an unknown name | `clojure/repl.clj:15-18`; sci `core_test.cljc:301-303` | **D12** — corpus fns print `Source not found` | (b) — over `:seon.fn/source` |
| D10 | `source` under `*read-eval* false` still works | `clojure/repl.clj:20-24` | (c) — we never `read-eval` | (c) |
| D11 | `print-table` pipe/dash bytes | print-path §"Line breaking and print-table" (probed) | print-path table face; **not resolvable inside an eval** (D11) | (b) |

### Family E — error faces and triage

| # | Behavior | Upstream test | Our state | Route |
|---|---|---|---|---|
| E1 | **`ex-triage` → `#:clojure.error{:phase :execution, :class …, :cause …}`** | `clojure/test_clojure/main.clj:54-59` | S2 proposes mirroring it; no acceptance row | (a) — the single best S2 falsifier |
| E2 | **`ex-str` → `Execution error (Error) at (REPL:1).\nxyz\n`** | `main.clj:59` | **D3/D20**; S2 | (a) |
| E3 | `Syntax error compiling at (REPL:L:C)` for analysis phase | audit D10; sci `error_test.cljc:166-170` | D10 | (b) |
| E4 | `Throwable->map` shape: `:cause` = ROOT message, `:via` ordered outer→inner, `:data` | `test_clojure/errors.clj:76-108` | **absent from both** | (b) — new; the root-cause walk S2 needs |
| E5 | nil/empty stack trace does not break printing | `errors.clj:100-108`; `main.clj:54-59` | untested | (b) |
| E6 | `Wrong number of args (N) passed to: ns/name`, demunged | `errors.clj:28-50`; sci `error_test.cljc:92-108` | audit: already byte-identical | (b) |
| E7 | HOF arity: `passed to: function of arity 0` | sci `error_test.cljc:112-126` | untested | (b) |
| E8 | user ex-data survives on the CAUSE, sci's wrapper carries `:type :sci/error :line :column` | sci `error_test.cljc:128-145` | **D4** — we leak the wrapper's data to the agent | (b) |
| E9 | `sci/stacktrace` frame maps `{:ns :name :line :column}` | sci `error_test.cljc:26-56` | **absent from both inventories** | (a) — see gap 3 |
| E10 | `sci/format-stacktrace` → `"user/g - NO_SOURCE_PATH:1:27"` | sci `error_test.cljc:77-81` | **absent from both** | (a) — new; this is the ready-made location line |
| E11 | error location is the LOOP form, never nil | sci `error_test.cljc:9-24` | untested | (a) |
| E12 | destructuring errors carry `[line col]` (10 forms) | sci `error_test.cljc:210-226` | untested | (a) |
| E13 | `if-let`/`when-let`/`if-some`/`when-some` binding-arity messages | sci `error_test.cljc:228-242` | untested | (a) |
| E14 | `ex-info` with nil data → `{}`, never a stored nil | `errors.clj:110-112` | matches our "absent is absent"; W3 is the inverse defect | (b) |
| E15 | uncaught-exception report block | babashka `error_test.clj:193-231` | babashka **deliberately diverges** from `clojure.main` | (c) — record as a rejected alternative; see §3 |
| E16 | time-limit face `Ran out of time after Nms.` | sci `interrupt_fn_test.cljc` (our fork) | audit honesty question 4 — keep explicit | (c) — genuinely ours |

### Family F — output capture and print vars

| # | Behavior | Upstream test | Our state | Route |
|---|---|---|---|---|
| F1 | `println`/`print`/`prn`/`pr`/`newline` exact bytes | sci `io_test.cljc:32-38` | audit: already right | (b) |
| F2 | `with-out-str` inside an eval | sci `io_test.cljc:24-25` | untested | (b) |
| F3 | agent's `*print-length*` shapes its own RESULT line | sci `io_test.cljc:40-49` | **D18/S6**; print-path §"Options, not thread bindings" | (b) |
| F4 | `*print-level*` inside an eval → `[:a #]` | sci `io_test.cljc:51-55` | D18 | (b) |
| F5 | `*flush-on-newline*` | sci `io_test.cljc:86-98` | (c) — we capture to a buffer, not a terminal | (c) |
| F6 | `read-line` / `*in*` | sci `io_test.cljc:20-30`; babashka `repl_test.clj:52-53` | (c) — an agent has no stdin; the door is the input | (c) |

### Family G — reader

| # | Behavior | Upstream test | Our state | Route |
|---|---|---|---|---|
| G1 | **built-in `#inst`/`#uuid` read** | `reader.cljc:502-517, 576-584`; edamame `core_test.cljc:431-439` | **D6** — refused. **Root cause corrected below** | (b) |
| G2 | unknown tag → a clean refusal | `reader.cljc:586-600`; edamame `core_test.cljc:431-439` | ours refuses (correct) | (b) |
| G3 | `#=` read-eval refused | `reader.cljc` + edamame `:read-eval` | ours refuses by construction | (b) |
| G4 | namespaced maps `#::foo{…}`, `#::{…}` | `reader.cljc:744-772`; edamame `core_test.cljc:441-454` | untested | (b) |
| G5 | reader conditionals `#?`/`#?@`, `:read-cond :preserve` | `reader.cljc:653-737`; edamame `152-271`; sci `read_test.cljc:27-58` | untested | (b) |
| G6 | line/column metadata on read forms | `reader.cljc:407-446, 792-802`; edamame `314-323` | our reader carries it | (b) |
| G7 | `#'x` var-quote, `@x` deref, `#()` fn literal, `` ` `` syntax quote | `reader.cljc:453-491`; edamame `276-313, 463-499` | untested | (b) |
| G8 | ratio / bigdec / bigint / octal-escape / zero literals | edamame `core_test.cljc:791-825` | untested | (b) |
| G9 | `:eof` option / EOF value | `reader.cljc:738-742`; sci `read_test.cljc:106` | our reader has `::eof` | (b) |
| G10 | invalid symbol values rejected | `reader.cljc:773-781`; edamame `658-672` | untested | (b) |

### Family H — namespaces and vars

| # | Behavior | Upstream test | Our state | Route |
|---|---|---|---|---|
| H1 | `def` returns the var | sci `vars_test.cljc:206-208` | D2 (the FACE is wrong, the value is right) | (b) |
| H2 | **`(ns foo)` and `(require …)` return nil** | `clojure/repl.clj:57-61` | **D14** — we return the namespace name | (b) |
| H3 | `in-ns` sticks; `*ns*` follows | sci `namespaces_test.cljc:115-118` | audit: already right (`::ending-ns`) | (b) |
| H4 | `ns-publics`/`ns-map`/`ns-refers` return var maps | sci `namespaces_test.cljc:174-186, 277-283` | D8 — vars render as markers | (b) |
| H5 | `ns-unmap`, `ns-unalias`, `remove-ns`, `find-var`, `find-ns` | sci `namespaces_test.cljc:284-341` | untested | (b) |
| H6 | `(meta #'f)` shape incl. `:arglists`, `:doc`, `:ns` | sci `core_test.cljc:1197-1200, 1586-1615` | **D19** + W3 (`:file nil` stored nil) | (b) |
| H7 | dynamic vars, `binding`, `with-redefs`, `alter-var-root` | sci `vars_test.cljc:14-235` | untested | (b) |
| H8 | built-in vars are read-only | sci `core_test.cljc:1324-1336` | our security posture depends on it | (b) |

### Family I — genuinely ours (no upstream corpus tests these)

| # | Behavior | Our owner |
|---|---|---|
| I1 | admission node/depth/width/string budget accounting | `test/seon/sci/admit_test.clj` |
| I2 | the closed admitted-node grammar is total (P-TOTAL) | print-path §"Acceptance evidence" |
| I3 | text and hiccup sinks cannot disagree (P-TEE) | print-path §"Acceptance evidence" |
| I4 | `result-edn` round-trips from a stored fact and re-renders identically | print-path decision 1(c) |
| I5 | capability request handler — `fs`/`web`/`llm`/`db` are the only host reach | `seon.effect` |
| I6 | scratch-state lifetime across runs (facts survive, defs do not) | audit honesty question 1 |
| I7 | loud "N of M shown" capping line | audit honesty question 2; ruling #25 |
| I8 | the time limit is uncatchable and not maskable | sci `interrupt_fn_test.cljc` (our fork upstreamed it) |

## 3. Top gaps the mined suites expose that our inventories missed

Ranked by consequence.

### Gap 1 — S4's reader-tag prescription is wrong, and the real fix is one line

The audit says *"Pass the ordinary built-in tag set (`inst`, `uuid`) as
`::tags` from every production caller"* (`audit:174-181`). Reading
edamame's actual dispatch falsifies that. Tag resolution is an `or`:

```clojure
;; reference-code/edamame/src/edamame/impl/parser.cljc:600-603
f (or (when-let [readers (:readers ctx)] (readers sym))
      (default-data-readers sym))   ; = clojure.core/default-data-readers = {uuid …, inst …}
```

Built-ins are already reachable. The reason they are refused is that our
`accepted-reader` **always returns a truthy refusal handler**
(`src/seon/sci/reader.cljc:28-32`), so the `or` can never fall through.

Probed (`tmp/edamame_tag_probe.clj`, 2026-08-01):

```text
ours  #inst  => Reader tag is not accepted: inst
fixed #inst  => #inst "2020-01-01T00:00:00.000-00:00"
fixed #uuid  => #uuid "550e8400-e29b-41d4-a716-446655440000"
fixed #bogus => Reader tag is not accepted: bogus
```

where `fixed` returns `nil` when `clojure.core/default-data-readers`
already owns the tag. This matters beyond convenience: S4 as written is a
**hand list repeated at every caller**, which the standing "no
hand-maintained lists" rule forbids. The computed form reads the
dependency's own var and lives at the one owner.

### Gap 2 — the Throwable VALUE face is absent from the sealed dispatch table

`clojure/test_clojure/printer.clj:124-138` pins that
`(-> e pr-str read-string)` equals `(Throwable->map e)` — stock Clojure
prints exceptions as readable `#error {:cause … :via [...] :trace [...]}`
data. The print-path design's dispatch table has **no row** for a
Throwable, and the audit only ever discusses the error *report line*
(D3/D4/D10/D20, S2). But a Throwable is an ordinary VALUE in a REPL: it
is what `*e` holds (C3), what `(ex-info "x" {})` returns, and what lands
in a collection after a `catch`. Today it falls to `::object`, which is
strictly worse than stock. The grammar needs an explicit decision.

### Gap 3 — sci already ships the stacktrace formatter S2 proposes to rebuild

S2 says to reconstruct a stock-shaped location line from sci's
`:line`/`:column`/`:phase`. Neither inventory mentions that sci exports
`sci/stacktrace` and `sci/format-stacktrace`, pinned by
`sci/test/sci/error_test.cljc:26-81` to produce frame maps
`{:ns user :name g :line 1 :column 27}` and formatted lines
`"user/g - NO_SOURCE_PATH:1:27"`. That is the existing owner of the
location information, it is what makes a real `pst` possible (D13), and
building a second derivation of the same fact is exactly the "second
mechanism" the standing rules forbid. S2 should be re-grounded on it.

### Gap 4 — `(ex-data *e)` is a parity trap where S2 and S3 interact

babashka pins `(throw (ex-info "foo" {:a 6})) (ex-data *e)` → `{:a 6}`
(`repl_test.clj:58-59`). sci pins that the user's ex-data lives on the
**cause**, while the wrapper carries `{:type :sci/error :line :column
:message}` (`error_test.cljc:128-145`). So binding `*e` to the caught
throwable (S3, naively) makes `(ex-data *e)` return sci's internals — the
same leak S2 is trying to stop at the error value, reappearing through a
var. Whatever S2 decides about stripping `:sci.impl/*` must apply to what
`*e` holds, or the two smoothings will contradict each other.

### Gap 5 — five concrete print edge cases with exact upstream bytes

All from `printer.clj`, none in either inventory:

- **empty collection never elides**: `()` at `*print-length* 0`, not
  `(...)` (`:21-24, 38-41`) — the naive emitter gets this wrong;
- **`*print-level* 0` prunes the top-level value to `#`** (`:55-65`);
- **the level×length interaction matrix**, 12 rows (`:67-85`) — the single
  strongest falsifier available for `emit-sequential`;
- **`##Inf`/`##-Inf`/`##NaN` for Float as well as Double** (`:188-195`);
- **demunging** of munged fn names for display (`main.clj:72-79`), which
  D15's `sci.impl.fns$fun$arity_0__75394` face currently skips even though
  sci ships `clojure.repl/demunge`.

### Gap 6 — the `clojure.pprint` route is a recipe, not an open question

S3 says "add `clojure.pprint/print-table`" without saying how, and the
print-path design needs `print-table`'s exact bytes. sci ships no
`clojure.pprint`. The established pattern is three lines
(`sci/test/sci/pprint_test.clj:9-16`): a host fn rebinding `*out*` to
`@sci/out` and `*print-namespace-maps*` to `@sci/print-namespace-maps`,
mapped in under `:namespaces {'clojure.pprint {…}}`; babashka productionizes
exactly this as `pprint-namespace`. Requiring `sci.pprint` additionally
teaches the host printer about `SciRecord` and `sci.lang.Var`.

### Gap 7 — babashka's error face is a rejected alternative worth recording

babashka does **not** use `clojure.main`'s triage. It prints its own
block with `Type:`/`Message:`/`Data:`/`Location:`, a `----- Context -----`
source excerpt with a `^--- msg` caret, and a `----- Stack trace -----`
section (`error_test.clj:193-231`). This is the largest sci-based parity
effort deliberately choosing a *richer* face than stock — because bb is a
script runner reporting to a human, not a REPL. Our goal is the opposite
(ruling #24: indistinguishable from stock), so **S2's choice of
`clojure.main` is correct** — but it is a real decision with a real
precedent on the other side, and the audit records neither.

## 4. What we cover that no upstream suite tests

Family I above, in short: admission budget accounting, the closed-grammar
totality property, the text/hiccup tee property, receipt round-trip
determinism, the capability request handler, cross-run scratch lifetime, and the
loud capping line. These are our genuine differences and **must not be
smoothed** — they are the audit's honesty questions. The one exception is
the interrupt/time-limit corpus, which we already wrote and upstreamed
into sci itself (`interrupt_fn_test.cljc`); that is the model for how a
genuine difference earns a permanent test.

## 5. The adaptation plan

### The gate

One standing namespace, `test/seon/repl_parity_test.clj`, holding the
adapted corpus and growing as divergences close. It is a `bin/test`
namespace like any other — no second runner, no drive script. Its job is
to make each closed divergence a **recurring** proof, since a live probe
that ran once in a lane counts as NOT COVERED.

### The harness shim (what makes route (a) possible)

Every route-(a) case needs the same four-line adapter, and it must exist
before any adaptation:

```clojure
(defn repl-session
  "Evaluate FORMS through the production door as one session and return
   the REPL-visible bytes per form: {:out … :value … :err …}."
  [forms] …)
```

It wraps `seon.sci.eval/fork` + the run-loop fold with production caps,
returning what a stock REPL would have *printed* — the result line
(post-emitter), captured `*out*`, and the error report. With that, the
upstream tables port almost verbatim: `printer.clj`'s length/level
matrices become `(is (= expected (:value (repl-session [form]))))`, and
babashka's `assert-repl` becomes `(is (= expected (:value (repl-session
["1" "(inc *1)"]))))`. Without it, every case must be hand-rewritten,
which is how a parity gate rots.

Two upstream helpers are worth adapting alongside it: babashka's
`multiline-equals` + `process-difference` normalizer
(`error_test.clj:9-35`), which absorbs JDK-version churn in stack frames,
and `clojure.test-helper/platform-newlines`.

### Routes, in one line each

- **(a) run nearly unchanged** — B2/B3/B5/B6/B7 (the printer matrices are
  pure data), C1/C2/C3/C5 (babashka's `assert-repl` lines), D1/D2/D5/D6/D7
  (sci's `repl_test` expectations), E1/E2 (`ex-triage`/`ex-str` are pure
  functions of a `Throwable->map`), E9/E10/E11/E12/E13 (sci's own).
- **(b) adapt into a `test/seon/` regression** — everything whose expected
  bytes depend on our grammar (A1–A10, B1/B4/B8–B11, H-family), our facts
  (D9 `source` over `:seon.fn/source`), or our stripping decisions
  (E4/E8, C4).
- **(c) N/A with reason** — A7 `*print-dup*` (`#=` refused by
  construction), D10 `*read-eval*` (never enabled), F5
  `*flush-on-newline*` (we buffer, no terminal), F6 stdin (the door is the
  input), E15 babashka's block face (deliberately rejected, §3 gap 7),
  E16 the time limit (genuinely ours), and `(type (range 3))` →
  `LazySeq` (audit honesty question 5 — a correct trade).

### First-slice recommendation

**Land the harness shim plus Family B in one commit, alongside the
print-path implementation — and correct S4's one line while you are in
the reader.**

Why this slice:

1. **It is where the sealed contract already is.** The print-path design
   is RULED and ready; Family B is precisely its acceptance evidence, and
   `printer.clj`'s matrices are stronger falsifiers than the nine
   hand-written acceptance rows because they pin *interactions* (B6) and
   *edge cases* (B4, B5) the design's own rows miss.
2. **It carries the highest-severity divergence.** D1 (every seq becomes a
   vector) is B1, and it is the one an agent hits on its first `(map …)`.
3. **The shim is the gate's foundation.** Building it here means Family C
   (babashka's REPL-loop corpus, which closes D7/D13 under S3) is nearly
   free afterwards.
4. **Gap 1 is a one-line fix with a probe already written**
   (`tmp/edamame_tag_probe.clj`) and it prevents S4 from landing as a hand
   list at every caller.

Then, in order: Family C + D (S3 — the REPL vars and the `clojure.repl`
surface, with the `clojure.pprint` recipe from gap 6); Family E (S2,
re-grounded on `sci/stacktrace` per gap 3, and resolving gap 4 before
`*e` is bound); Family G (the reader); Families A9/H (the Throwable face
and the namespace/var surface).

Explicitly **out** of the first slice: Family I. Those are our genuine
differences and their tests already exist or belong to the caps/blob wave.

## Follow-ons filed elsewhere

- Gap 2 (Throwable VALUE face) needs a row added to the SEALED dispatch
  table in `plan/print-path-design-2026-08-01.md` — a contract change, so
  it goes to the owner rather than being assumed here.
- Gap 4 (`ex-data *e`) is a cross-cutting constraint between S2 and S3;
  whichever lands first must not contradict the other.
- W1 (`seon.sci.eval` is not hot-reloadable; the `default` cluster's door
  is broken) still blocks live proof of any of this on that cluster.

## Landing ledger — recurring gate (2026-08-01)

Commits:

- `6e33f4e95` — production-door `repl-session` shim plus Family B;
- `64339dd13` — computed Edamame fallback for built-in reader tags; and
- `9a74c52ab` — every remaining executable row plus the pending ledger; and
- `c4c6859aa` — row isolation and location extraction hardened against
  process-global instrumentation churn.

The recurring gate is `test/seon/repl_parity_test.clj`. Each executable
row is one discovered `deftest` carrying `:parity/row`; known divergences
also carry `:parity/known-divergence`. The runner derives the divergence
roster from those test Vars, prints it once, expects each known divergence
to fail its stock expectation, and fails if one begins passing before its
metadata is promoted. An unmarked row fails normally when it regresses.

The harness uses a fresh canonical in-memory database per row, applies an
explicit `:record` config overlay, reads the effective time limit and
`config/result-caps`, then performs `fork` → `acquire!` → ordered
`evaluate` while threading `:seon.sci.eval/ending-ns`. Each result exposes
captured output, admitted value, error, stored `result-edn` face, and ending
namespace. It does not fabricate the unbuilt post-emitter face.

### Corrected cardinality and result

The headline's “59 rows” is false. The nine tables contain **88** row ids:
A=10, B=11, C=8, D=11, E=16, F=6, G=10, H=8, I=8. Families A–H are 80
stock-parity rows; Family I contributes eight Seon-specific rows. The
advertised 59 equals the report's 53 route-(b) plus six route-(c) rows and
accidentally omits all 21 route-(a) rows.

Current exact gate accounting:

- **69 tested** — 35 presently match the asserted behavior and 34 are
  known divergences;
- **34 known divergences** — A1, A2, A9, B1–B7, B9–B11, C1–C6, D5, D6,
  D9, D11, E2–E4, E8, F3, F4, H1, H2, H4–H6; and
- **19 pending with reasons** — A7, D10, E1, E5, E9, E10, E15, E16,
  F5, F6, G5, G9, I1–I5, I7, I8. The exact per-row reasons live beside
  the rows in the gate namespace.

Focused evidence:

```text
REPL parity known divergences (34): A1, A2, A9, B1, B10, B11, B2, B3,
B4, B5, B6, B7, B9, C1, C2, C3, C4, C5, C6, D11, D5, D6, D9, E2, E3,
E4, E8, F3, F4, H1, H2, H4, H5, H6
REPL parity pending rows (19): A7, D10, E1, E5, E9, E10, E15, E16,
F5, F6, G5, G9, I1, I2, I3, I4, I5, I7, I8

Ran 69 tests containing 69 assertions.
0 failures, 0 errors.
```

The ordered integration checkpoint also passes with the existing eval suite:

```text
bin/test seon.repl-parity-test seon.sci.eval-test
Ran 89 tests containing 147 assertions.
0 failures, 0 errors.
```

### What execution corrected

- A2 is a newly proven divergence: Double symbolic values print correctly,
  but `(float ##Inf)` and `(float ##-Inf)` fail with “Value out of range for
  float” instead of producing Float's `##Inf` / `##-Inf` faces.
- H5 is a newly proven harness-level divergence: `ns-unmap` evaluates in
  the isolated deletion fork, so a later form on the bare evaluate-only
  session still finds the Var until the production terminal transaction
  applies that program deletion.
- A8 and G1 pass after the reader correction: custom handlers remain first,
  tags in `clojure.core/default-data-readers` fall through to Edamame, and a
  genuinely unknown tag still receives Seon's clean refusal.
- C6 cannot observe separate streams because production currently binds
  `sci/out` and `sci/err` to the same `StringWriter`; the executable row
  proves that divergence directly.
- C7 needs the production reply splitter before the eval fold. The gate now
  runs `reply/sources` on one same-line reply and asserts all three resulting
  forms' faces, instead of pretending `evaluate` accepts multiple events.
- E1, E9, and E10 cannot run “nearly unchanged” through the required shim:
  the door exposes neither `clojure.main/ex-triage` nor the raw Throwable
  consumed by `sci/stacktrace` / `sci/format-stacktrace`.

The source audit also found citation gaps: C2's cited Babashka tests never
exercise `*3`; C7 checks only the final output substring; C5's SCI case passes
an explicit throwable; B9's record face is at
`defrecords_and_deftype_test.cljc:197-199,215-218`, not the cited range; H6
does not have a cited complete metadata-map assertion; A4 cites character
reading rather than printing; B11 cites stack-location demunging rather than
function-object display; and F3/F4 cite explicit printed output rather than
the stronger result-face contract.

### Remaining reader-suite boundary

The reader fix intentionally falsifies five obsolete assertions in
`test/seon/sci/reader_test.clj:127-183`, which still require `#inst` and
`#uuid` to be refused. That file was outside this lane's owned paths, so it
was not edited. The focused reader namespace is therefore red until its owner
promotes those assertions to the now-ruled built-in-tag behavior; unknown-tag
and `#=` refusal assertions remain valid.
