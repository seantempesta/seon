---
type: research
status: active
tags: [research, sci, context]
---

# SCI-vs-REPL realism audit (2026-08-01)

Owner goal: an agent lives in a REPL rendered from real facts, and the
experience must be as close to a stock `clj` REPL as possible — ideally
the agent cannot tell it is running under sci with admission caps. This
audit sweeps every place where what the agent sees diverges from what a
stock REPL shows, ranks the divergences, and proposes the smoothing at
its owning seam.

## How every claim here was produced

Two live sources, both reproducible:

1. **Stock ground truth** — `clojure -M -r` (Clojure 1.12.5) fed the same
   forms; its exact bytes are quoted in the "real REPL shows" column.
2. **Our door** — a fresh JVM (`clojure -M:dev`, stdin script) building
   `seon.sci.eval/fork` + `evaluate` with the production caps
   (`config/default.edn:20-26`: depth 12 / collection 64 / string 4096 /
   nodes 4096) and reading back `:seon.cluster.eval/result-edn`,
   `:seon.cluster.eval/output`, `:seon.cluster.eval/error`.
   No database, so corpus-fact surfaces (`doc` over `:seon.fn/doc`,
   `source` over `:seon.fn/source`) were read from source
   (`src/seon/sci/eval.clj:681-724`) rather than probed; those rows are
   marked **[source-read]**.

A fresh JVM was used deliberately: the live `default` cluster's door is
currently broken (see *Warts found*, W1), which is itself a finding.

## Divergence inventory

Ranked within each severity band. "Seam" names the one owner that
should change.

| # | What the model does | Real REPL shows | We show | Sev | Owning seam |
|---|---|---|---|---|---|
| D1 | `'(1 2 3)`, `(map inc [1 2])`, `(keys m)`, `(sort …)`, `(seq "ab")`, `(first {:a 1})` | `(1 2 3)`, `(2 3)`, `(:a :b)`, `(1 3)`, `(\a \b)`, `[:a 1]` | `[1 2 3]`, `[2 3]`, `[:a :b]`, `[1 3]`, `[\a \b]`, `[:a 1]` — every seq becomes a vector | **HIGH** | `seon.sci.admit` print leaf (`admit.clj:287-289` folds coll/seq/Collection into vectors) |
| D2 | `(def x 41)` / `(defn f …)` | `#'user/x` | `#:seon.sci.admit{:reference "sci.lang.Var", :name "#'user/x"}` | **HIGH** | `admit.clj:67-74` (`reference`) + print leaf |
| D3 | any failing form | `Execution error (IndexOutOfBoundsException) at user/eval168 (REPL:1).`<br>`null` | `Execution error: clojure.lang.ExceptionInfo` — the sci WRAPPER class, root cause hidden; no location line | **HIGH** | `eval.clj:374-378` (`diagnosis`) + `380-396` (`failure-value`) |
| D4 | any failing form, then reads `:seon.error/data` | ex-data is the user's own map | agent-visible data carries `:type :sci/error`, `:line 1 :column 1`, `:file nil`, `:phase "analysis"`, `:sci.impl/callstack`, and on a time-limit `#:sci.impl{:interrupt #:seon.sci.admit{:opaque "java.lang.Object"}}`, plus our own `:seon.eval/fn-entries` / `:allocated-bytes` | **HIGH** | `eval.clj:380-396` copies `(ex-data throwable)` and `record` straight into the agent value |
| D5 | a big result (`(range)`, a 189-row query) | prints everything, or honors `*print-length*` with `…`; the agent knows it was cut | `[0 1 … 62 :seon.sci.admit/elided]`; deep maps end in `:seon.sci.admit/elided`; strings become `#:seon.sci.admit{:truncated-string "…", :elided true}`; `:seon.sci.admit/capped?` is a separate fact the transcript does not print | **HIGH** | `admit.clj:96-102`, `139-147`, `241-249`; transcript printer |
| D6 | `#inst "2020-01-01"`, `#uuid "…"` | the literal reads | `Execution error: Reader tag is not accepted: inst` / `uuid` — every built-in tag is refused because callers pass no `::tags` and `:readers` is consulted for built-ins too | **HIGH** | `seon.sci.reader` (`reader.cljc:20-32`, `:116`) |
| D7 | `*1`, `*2`, `*3`, `*e` | last three values / last exception | always `nil` — `evaluate` binds only `sci/ns`, `sci/out`, `sci/err` (`eval.clj:952-957`); sci ships the vars (`reference-code/sci/src/sci/impl/namespaces.cljc:1539-1542`, `core.cljc:180`) and nobody sets them | **HIGH** | `eval.clj/evaluate` + the run-loop fold |
| D8 | `(in-ns 'foo)`, `*ns*`, `(all-ns)`, `(ns-publics 'clojure.string)` | `#object[clojure.lang.Namespace 0x38600b "foo.bar"]`; a map of `#'clojure.string/join` vars | `#:seon.sci.admit{:opaque "sci.lang.Namespace", :name "foo.bar"}`; a map of `#:seon.sci.admit{:reference "sci.lang.Var" …}` | MED-HIGH | `admit.clj:293-299` (`opaque`) + print leaf |
| D9 | `(defrecord R [a b])` then `(->R 1 2)` | `user.R` then `#user.R{:a 1, :b 2}` | `#:seon.sci.admit{:opaque "sci.lang.Type"}` then `{:a 1, :b 2, :seon.sci.admit/type "user.R"}` | MED-HIGH | `admit.clj:266-273` (record projection) + print leaf |
| D10 | `undefined-symbol` | `Syntax error compiling at (REPL:0:0).`<br>`Unable to resolve symbol: undefined-symbol in this context` | `Execution error: Unable to resolve symbol: undefined-symbol` (sci's raw message; sci already carries `:line`/`:column`/`:phase "analysis"` — `reference-code/sci/src/sci/impl/utils.cljc:62-70`, `167-181`) | MED-HIGH | `eval.clj/diagnosis` |
| D11 | `(source f)`, `(apropos "x")`, `(pst)`, `(find-doc "x")`, `(print-table rows)` | all resolve bare | bare `source`/`apropos`/`pst` are `Unable to resolve symbol`; only `dir` and `doc` were referred into `clojure.core` (`eval.clj:198-205`, `716-724`). `clojure.pprint` is absent entirely — `print-table`, the design's chosen table face, does not exist | MED-HIGH | `eval.clj` base ctx |
| D12 | `(source some-corpus-fn)` **[source-read]** | prints the defining source | `clojure.repl/source-fn` needs `:file`+on-disk source or a ctx `:load-fn` (`reference-code/sci/src/sci/impl/namespaces.cljc:2310-2334`); we set neither, so every corpus fn prints `Source not found` — while `:seon.fn/source` holds the exact bytes | MED-HIGH | `eval.clj` (a `source` var over program facts, mirroring `program-doc-var` at `eval.clj:696-724`) |
| D13 | `(clojure.repl/pst)` | prints the last stack trace | `Cannot invoke "java.lang.Throwable.getCause()" because "cause" is null` — a consequence of D7 (`*e` never set); sci's `pst` derefs `*e` (`namespaces.cljc:2385-2391`) | MED | same as D7 |
| D14 | `(require '[clojure.set :as set])` | `nil` | `user` — the namespace name, because a require-only form is committed as a namespace-context row and its `:seon.ns/name` REPLACES the evaluated value (`eval.clj:1043-1047`) | MED | `eval.clj/evaluate` value selection |
| D15 | `(atom 1)`, `(fn [] 1)` | `#object[clojure.lang.Atom 0x… {:status :ready, :val 1}]`, `#object[user$eval176$fn__177 0x… "…"]` | `#:seon.sci.admit{:reference "clojure.lang.Atom"}`, `#:seon.sci.admit{:opaque "sci.impl.fns$fun$arity_0__75394"}` — the second literally spells `sci.impl` | MED | `admit.clj:55-74` + print leaf |
| D16 | `(class x)` / `(type x)` | `java.lang.String`, `clojure.lang.LongRange` | `#:seon.sci.admit{:opaque "java.lang.Class"}` — the class NAME is dropped; also `(type (range 3))` is `clojure.lang.LazySeq` here because the base ctx uses sci's interrupt-aware core (`eval.clj:162-164`) | MED | `admit.clj:293-299`; the LazySeq difference is a deliberate, correct trade |
| D17 | `(Throwable->map e)`, `(future …)`, `(System/getProperty …)` | work | `Unable to resolve symbol: …` | MED (honesty, not a bug) | base ctx surface; see *Honesty questions* |
| D18 | `(binding [*print-length* 3] (pr-str (range 10)))` | `"(0 1 2 ...)"` | `"(0 1 2 ...)"` — identical. But the RESULT line ignores every print var: `result-edn` is `(pr-str projection)` under the HOST thread's bindings (`admit.clj:394`), so an agent setting `*print-length*` sees no change to its own result line, while the host's `*print-namespace-maps*` silently decides whether results read `#:a{:b 1}` or `{:a/b 1}` | MED | `admit.clj:390-395` |
| D19 | `(meta #'f)` | `{:arglists ([x]), :doc "doc", :line 1, :column 1, :file nil, :name f, :ns #object[…]}` | same shape, but `:ns` is an opaque marker map and `:file nil` is a stored nil the agent will read as meaningful | LOW | `admit.clj` print leaf |
| D20 | `(nth [1 2] 9)` / `([1 2] 5)` | `Execution error (IndexOutOfBoundsException) …` + `null` | `Execution error: clojure.lang.ExceptionInfo` — worst instance of D3 (sci's wrapper carries a nil message, so `diagnosis` falls back to the wrapper class name, `eval.clj:376-378`) | (folded into D3) | `eval.clj/diagnosis` |
| D21 | first `(type …)` anywhere in the process | instant | ~2.2 s of native work, 2.5 GB allocated, `fn-entries 1`; under a short time-limit it surfaces as `Execution error: Ran out of time after 2065ms.` for an innocent form. Measured: first call 2231 ms, every later call 0 ms, process-wide | LOW (once per process) but maximally confusing when it hits | JVM/sci warmup; needs its own dig |

Non-divergences worth recording (they already match, do not "fix" them):
`(dir clojure.string)`, `(doc inc)`, `(doc f)` on a sci-defined fn,
`(clojure.repl/dir user)`, `pr-str` of namespaced maps, `*print-meta*`,
`(try … (catch Throwable e (ex-data e)))` → `{:a 1}`, wrong-arity
(`Wrong number of args (0) passed to: user/f`), bare `dir` →
`Can't take value of a macro: #'clojure.repl/dir` (byte-identical to
stock), `(source f)` on a REPL-defined fn → `Source not found`
(also stock), `#uuid` VALUES from `(random-uuid)`, `println`/`prn`
output capture, and `(eval '(+ 1 2))`.

## Ranked smoothing proposals

### S1 — One traversal, two leaves: a REPL-faithful print of the bounded value (fixes D1, D2, D5, D8, D9, D15, D16, D19)

**The fix.** Today `result-edn` is `(pr-str <the total-codec data>)`
(`admit.clj:394`), so every marker map the codec invented for RECOVERY
is also what the agent READS. Split those two jobs at the leaf, not by
adding a second walk: keep `:seon.sci.admit/value` exactly as it is
(bounded, cycle-free, queryable, committed) and give the same walk a
second emitter that produces REPL text — vars as `#'user/x`, namespaces
as `#object[clojure.lang.Namespace 0x0 "foo"]`, sequences with parens,
records as `#user.R{…}`, opaque host values as `#object[…]`, and every
elision as Clojure's own `...` / `#` idiom rather than
`:seon.sci.admit/elided`. This is precisely the design already named as
the crux in `repl-session-context-2026-08-01.md:91-98` ("one traversal,
two serialization leaves"); the HTML projection is the third leaf.

**Seam.** `seon.sci.admit` — `project`/`project-node` gain an emitter,
and `admit` returns `:seon.cluster.eval/result-edn` from the REPL leaf
while `::value` keeps the data leaf.

**Cost.** Medium. The walk already exists; the work is the leaf table
plus deciding what `result-edn` means (see below). Sequence-vs-vector
(D1) needs the walk to remember `seq?`/`list?` at the node it flattened
— today that information is discarded, which is the only structural
change.

**Owner ruling needed: yes, one.** `:seon.cluster.eval/result-edn` is
currently BOTH the durable receipt projection and the agent-visible
line. If it becomes REPL text it stops being readable EDN, which P9
(the whole context reads through the reader) actually still tolerates —
`#'user/x`, `(1 2 3)`, `#user.R{…}` and `#object[…]` all READ; `...`
does not. Options for the owner: (a) result-edn becomes REPL text and
the data projection lives on `::value` only; (b) two attributes; (c)
result-edn stays data and the transcript printer re-prints from
`::value` (cheapest, but then the printer must invert markers, which is
lossy for D1). Recommend (a) — with the P9 property re-run, because the
elision face is the one token that can break it.

### S2 — Errors through a clojure.main-shaped triage (fixes D3, D4, D10, D20)

**The fix.** `diagnosis` (`eval.clj:374-378`) currently reports
`(ex-message throwable)` or the wrapper's class name. Replace it with a
triage that mirrors `clojure.main/ex-triage` + `ex-str`
(`clojure/main.clj:207`, `:268`, `:347` in the 1.13.0-alpha4 jar):

- walk to the ROOT cause (sci wraps everything in `ex-info` with
  `:type :sci/error`, `utils.cljc:167-181`), report ITS simple class
  name and ITS message;
- use sci's own `:line`/`:column`/`:phase` to choose the stock prefix —
  `Syntax error compiling at (REPL:L:C).` + `… in this context` for
  `:phase "analysis"`, `Execution error (ClassName) at ns/… (REPL:L).`
  otherwise;
- STRIP `:sci.impl/*`, `:type :sci/error`, `:file nil`, and the
  `:seon.eval/*` record out of the agent-visible `:seon.error/data`.
  Those belong on the receipt fact (they already ride
  `:seon.sci.admit/record`), not inside the value the agent reads. The
  interrupt marker leaking as `#:sci.impl{:interrupt …}` is the sharpest
  instance: it is a private sentinel by construction
  (`utils.cljc:42-49`) and must never be agent-visible.

**Seam.** `seon.sci.eval` — `diagnosis` + `failure-value` only.

**Cost.** Small, and it is the highest confusion-per-byte win after S1:
"Execution error: clojure.lang.ExceptionInfo" for an index error is
strictly worse than stock, and every model has seen the stock face
thousands of times.

**Owner ruling needed: no** for the message shape (it is strictly closer
to stock). **Yes** for one sub-question: whether the agent should still
see a machine-readable `:seon.error/kind` alongside the stock text.
Recommend keeping the kind (it is ours, not sci's) and dropping only
the sci/eval internals.

### S3 — Bind the REPL vars and complete the `clojure.repl` surface (fixes D7, D11, D12, D13)

**The fix.** Three small wirings:

- `*1 *2 *3 *e` — sci already defines them (`namespaces.cljc:1539-1542`);
  set them at the END of each form in the run-loop fold (the fold, not
  `evaluate`, because the fold is what makes a run a session). `*e`
  should hold the caught throwable so `pst` works.
- refer `source`, `apropos`, `find-doc`, `pst` the same way `dir`/`doc`
  are referred today (`eval.clj:198-205`), and add
  `clojure.pprint/print-table` — the design already chose print-table as
  the canonical tabular answer, and it currently does not exist.
- `source` over corpus facts: mirror `program-doc-var`
  (`eval.clj:696-724`) with a `source` macro that prints
  `:seon.fn/source` for a program row and falls back to sci's
  `source-fn`. This is the seam the plan names ("`source` on an
  interpreted corpus fn should print `:seon.fn/source` facts").

**Seam.** `seon.sci.eval` base ctx + `acquire!` + the run-loop fold.

**Cost.** Small each; `*1/*2/*3` touch the fold's contract (one more
thing the fold owns), which is the only design decision.

**Owner ruling needed: no**, except to confirm that `clojure.pprint` is
admitted into the base ctx.

### S4 — Accept the built-in reader tags (fixes D6)

Pass the ordinary built-in tag set (`inst`, `uuid`) as `::tags` from
every production caller, so `accepted-reader` (`reader.cljc:28-32`) only
refuses genuinely unknown tags. Seam: `seon.sci.reader` callers. Cost:
tiny. Ruling: no — refusing `#inst` was never a decision, it is a
consequence of `:readers` being a function consulted for built-ins too
(`reader.cljc:115-116`).

### S5 — Make `(require …)` return `nil` (fixes D14)

The value substitution at `eval.clj:1043-1047` exists so the committed
row's identity is the returned value; for a namespace-CONTEXT row (no
declaration) the honest REPL value is `nil`. Seam: `evaluate`. Cost:
tiny. Ruling: no.

### S6 — Print vars owned by the session, not the host thread (fixes D18)

`admit`'s `pr-str` runs under whatever the HOST thread's `*print-*`
bindings are. Bind them explicitly from the agent's own sci vars at the
print leaf (sci exposes them: `sci/print-length`, `sci/print-level`,
`sci/print-namespace-maps` — `reference-code/sci/src/sci/core.cljc:166-171`)
so `(set! *print-length* 20)` in an agent's session actually changes its
next result line, and so the receipt's bytes do not depend on which
thread happened to print them. Seam: `seon.sci.admit`. Cost: small.
Ruling: worth one line — caps still bound the WALK; print vars only
shape the TEXT, and the two must not be confused.

## Honesty questions — must NOT be smoothed silently

These are places where making it "look like a real REPL" would be a
lie. Each needs an owner decision about what the agent is TOLD, not a
rendering trick.

1. **Scratch-state lifetime.** Within one run the fold shares one fork,
   so `def`, `defonce`, atoms and `in-ns` all persist across forms
   exactly like a REPL session (verified: `(def x 41)` → `(inc x)` → 42;
   `(defonce y (atom 0))` → `(swap! y inc)` → 1). ACROSS runs, only
   `defn` + a complete `:malli/schema` survives, via program facts and
   `acquire!`; every other def is gone. A real REPL session keeps
   everything until the process dies. This is the single largest
   experiential divergence and it is INVISIBLE — nothing in the
   transcript marks the boundary where the fork was thrown away. Do not
   fake persistence. The owner should choose between: (a) the banner /
   `help` states the rule and the transcript marks each run boundary
   honestly (recommended — it is also a real Clojure idea: a new REPL
   session); (b) scratch defs become facts too (large, and it would
   store derived state); (c) the fork survives across runs in-process
   (fast, but then a crash silently changes semantics — the crash model
   says nothing re-executes).
2. **Capping must be visible.** A 189-row query silently rendering 64
   rows plus an elision token is the failure mode the caps exist to
   avoid, not to hide. `:seon.sci.admit/capped?` is already computed
   (`admit.clj:390-395`) and the prototype printer drops it. Whatever
   S1 chooses for the elision face, a capped result must SAY it was
   capped, in a form a model reads as truth (stock `...` plus, e.g., a
   `;; 64 of 189 shown` comment line) — never a bare `...` that reads as
   the agent's own `*print-length*`.
3. **The sandbox surface is genuinely smaller.** `System/*`, `future`,
   arbitrary interop, `Throwable->map`, and file IO are absent by
   construction. `Unable to resolve symbol: System/getProperty` is
   honest; emulating them would not be. What should improve is the
   MESSAGE (S2) and a `help` line saying which surfaces exist — not the
   surface.
4. **The time limit is real.** `Ran out of time after 2005ms.` is a
   truthful face for an uncatchable interrupt (`interrupt.md:6-9`); a
   stock REPL's nearest relative is Ctrl-C. Keep it explicit, and keep
   `fn-entries`/`allocated-bytes` on the receipt where they are
   diagnostics — just not inside the agent's error value (S2).
5. **`(type (range 3))` is `clojure.lang.LazySeq`, not `LongRange`.**
   That is the interrupt-aware core doing its job (`eval.clj:158-164`).
   Correct trade; document it rather than chase parity.

## What already reads authentically (calibration)

This system is much closer to a real REPL than the divergence list
suggests, and several of the hardest parts are already right:

- **Session semantics within a run are genuine.** One fork per run,
  used as given (`eval.clj:71-80`), so defs accumulate and `in-ns`
  sticks — `:seon.sci.eval/ending-ns` follows `(in-ns 'foo.bar)`
  correctly, which is what makes a truthful prompt possible.
- **Printed output is captured and interleaved** (`eval.clj:62-69`):
  `(println "hi")` → `hi\n` then `nil`, exactly the REPL's two-part
  answer.
- **Arity errors are stock**: `Wrong number of args (0) passed to:
  user/f` — byte-identical to Clojure, and sci earns this through its
  own message rewriting (`utils.cljc:90-119`).
- **`doc` is right**, including for agent-defined fns and for corpus
  rows through the program-derived macro (`eval.clj:696-724`), and
  `dir` prints exactly the installed publics.
- **`ex-data` round-trips cleanly** through a user `try`/`catch`.
- **`*print-length*`, `*print-meta*`, `*print-namespace-maps*` all work
  INSIDE the eval** — sci binds them properly
  (`namespaces.cljc:1502-1506`); only the result line ignores them (D18).
- **Bare `dir` fails with the exact stock message.** That one line is
  the proof that the target is reachable: when the wiring is right, the
  faces come out identical for free.
- **The admission caps are principled and total** — depth/width/node
  accounting with reserved nodes for cut markers, no cycles, no
  realization of infinite tails. The problem is never that it bounds;
  it is only how the bound PRINTS.

## Warts found while reading (outside the checklist)

- **W1 — `seon.sci.eval` is not hot-reloadable, and the live `default`
  cluster's door is broken right now.** Every evaluation on `default`
  returns `class seon.sci.eval.EvaluationArm cannot be cast to class
  seon.sci.eval.EvaluationArm (… DynamicClassLoader @17eb7dee … @63a12da4)`.
  Cause: `EvaluationArm` is a `deftype` (`eval.clj:265-272`) while the
  guard holding the closure that casts it is a `defonce`
  (`eval.clj:296-297`); re-evaluating the namespace makes a new class
  the old closure cannot cast. Every agent turn on that cluster is
  failing. This deserves an issue: either the arm stops being a deftype
  (a small array/record of longs behind a protocol-free accessor), or
  the guard rebuilds when the class identity changes.
- **W2 — first `(type …)` in a process costs ~2.2 s / 2.5 GB.** Measured
  above (D21). Under a short time-limit it presents as a spurious
  timeout. Worth a separate dig; it is a velocity as well as a realism
  issue.
- **W3 — `:file nil` and `:message nil` are stored nils** in
  agent-visible error data (`utils.cljc:173-179` produces them; we pass
  them through). Absent should be absent.
- **W4 — the `record` diagnostics ride inside `:seon.error/data`** for
  EVERY failure (`eval.clj:384-385`), so `fn-entries` and
  `allocated-bytes` are part of what a model reads when it makes a
  typo. They are receipt diagnostics; they are already on the receipt.
- **W5 — `#:seon.sci.admit{…}` markers are a Seon word in the agent's
  face.** Even where a marker is the honest answer (an opaque host
  object), the namespace tells the agent it is inside a projection
  layer. `#object[…]` says the same thing in Clojure's own vocabulary.
