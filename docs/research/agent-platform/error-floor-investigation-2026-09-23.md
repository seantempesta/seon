---
type: research
status: evidence (point-in-time, 2026-09-23, lane braid-errors, Opus 5.5, HEAD db296b08f). The design is folded into lane-b3-errors-tasks-dials.md §2a ("Shown text first") and §5 E1–E7, which supersede §3–§8 here; §7 option 2 (never armed) was rejected by the owner 2026-09-23 15:15Z
created: 2026-09-23
tags: [agent-platform, errors, repl, mcp, operator, simple-made-easy]
owner: B3 (errors) display half; composes the one error route (error-route-final-design-2026-09-23.md, recording half) and lane repl-prd (REPL scope)
---

# Lane ERRORS-FAIL-ALONE — an error floor that depends on nothing

**Owner (2026-09-23):** "Make sure agents are sent to fully investigate and recommend
fixes." AGENTS.md "Simple made easy" names this braid directly: "error reporting that
renders through the projection it is reporting broken". "Situated programs fail": each
part fails ALONE and LOUDLY; a failure that "blinds diagnosis is the worst braid there is".

**The braid.** Every path from a throw to a human or agent goes through the machinery it
may be reporting broken: the schema projection, the armed Malli wrappers, the value
renderer, the database and the EDN reader. When one of them breaks, the error about it is
refused, replaced by the second failure, or printed as bytes nobody can read.

**The un-braiding in one sentence.** First, at every boundary, build a **floor** from the
Throwable alone: bounded plain text of class, message, bounded ex-data, cause chain and
first-party frames, using only `clojure.core`. Show it every time. Rich rendering and
database recording are optional layers **on top**. When a layer fails, its own floor is
**added** to the reply. It never replaces the original.

This document owns the floor (`seon.error.refusal/floor`) and the wire rule (§3.2). The
REPL lane (`lane-repl-simple-reliable.md`, since folded into B1 §3b) owns
restructuring `mcp-valf`, the ThreadLocal hand-off and `get_value`. That lane *uses* the
floor and the rule and does not redesign them. The error-route design owns recording,
policy and delivery (`docs/research/agent-platform/error-route-final-design-2026-09-23.md`).
This document changes none of it except the order: the floor is built before `record!`,
so a recording failure can never hide the original.

## 1. Evidence (observed; each probe exact and timed)

Probes ran in default's JVM through MCP `eval_clj` (session `braid-errors`, read-only,
throwaway namespace `braid-errors.floor-probe`; HEAD `db296b08f`). No Vars of default were
redefined.

| # | Probe | Result | Wall |
|---|---|---|---|
| P1 | `(pr-str (Throwable->map (ex-info "outer" {:v #'clojure.core/map :big (vec (range 100000))} (ex-info "inner" {:store (Object.)} (IllegalStateException. "root")))))`, then `edn/read-string` | 590,656 chars; the read fails with `No dispatch macro for: '`. This is the exact `bin/seon start`/`down` masking message. Raw ex-data printed whole is both huge and unreadable. | 5 ms |
| P2 | `clojure.main/ex-triage` + `ex-str` on the same map | `Execution error (IllegalStateException) at user/eval476776 (REPL:1). root` is bounded and readable, but keeps only the root link. The outer message, every ex-data key and the chain are gone. | (in P1) |
| P3 | `armed?` = `(not (identical? @v (mi/-f->original @v)))` for `seon.error.refusal/chain`, `refusal/diagnostic`, `seon.fault/fault!`, `seon.cluster.boot/diagnostic`, `seon.cluster/mcp-valf`, `seon.sci.admit/admit-value`, `seon.render.value/prepare` | **All `true`.** Every error constructor runs inside the Malli wrapper it may be reporting broken. | 2 ms |
| P4 | `(throw (ex-info "outer probe" {:probe/k 1 :probe/var #'clojure.core/map} (IllegalStateException. "root probe")))` over MCP | The face shows only `"root probe"`. The outer message, **both ex-data keys** and the chain are dropped. `:seon.error/frame` is `["seon.operator.prepl$io_prepl" "invokeStatic" "prepl.clj" 11]`, a machinery frame, because `first-seon-frame` matches any `seon.` class (`src/seon/cluster.clj:355-362`). | 7 ms |
| P5 | Throwaway `floor-text` (≈20 lines, core only, `*print-length*` 8, `*print-level*` 3, 300 chars per value, 8 links, 6 first-party frames per link) on P1's exception, plus `(range)`, a `reify` whose `toString` throws, and a 4-deep map | 1,088 chars. It shows the class and message of all three links, every ex-data key (the Var prints as `#'clojure.core/map`, the 100k vector as `[0 1 … 7 ...]`, the infinite seq bounded, the throwing object as `#unprintable[… java.lang.RuntimeException]`) and each link's frames. As a string it reads back through `edn/read-string` exactly. | **372 µs** warm (50 warm-up calls) |

P5 shows the floor is cheap enough to build unconditionally on every error path. It is
bounded by its caps, not by the value. The one exception is a user `print-method` that
does heavy work before it writes (§5, risk 1).

Earlier evidence (2026-09-23; `docs/research/agent-platform/fix-schedule-2026-09-23.md`
lines 204 and 219, plus landing notes):
- With a broken `seon.schema` loaded, MCP refused every value: "expected must be a print
  node". That refusal was the armed output contract of `admit-value`
  (`src/seon/sci/admit.clj:766`). `boot/diagnostic` failed inside the value renderer
  (`src/seon/render/value.clj:509`, `prepare`), so `bin/seon init` returned no cause.
- A partial boot was masked as `No dispatch macro for: '` (P1's class).
- A prepl answer reached 2 GB because an instrument refusal carried the whole handle as
  `:seon.error/offending` (`src/seon/instrument.clj:410`). `a85bf004b` bounded that one
  path by rendering through `render-ai`, which added a renderer dependency at the wire.
- "Requested array size exceeds VM limit" while projecting a result. **Cause not
  verified here.** The current face keeps only that message
  (`src/seon/cluster.clj:384-406`), so nobody can see which site allocated. The floor's
  frames will name it at the next occurrence.
- The Datahike fork raises `:node-not-found` with `:store store` in ex-data
  (`reference-code/datahike/src/datahike/index/persistent_set.cljc:439-441`, pin
  `2cc313a61`). Any printer that prints ex-data whole prints the konserve store.

## 2. Every error path: what must be healthy for the error to be seen

"Needs" lists the components that must be healthy for a human or agent to see *anything*
about the original failure. **Bold** marks a dependency the error may be reporting broken.

| Path (throw → viewer) | Where | Needs today | What is lost when a need fails |
|---|---|---|---|
| **Refusal constructors** `refusal/chain`, `refusal/diagnostic` | `src/seon/error/refusal.clj:74-115` | **armed wrapper + `:seon.error/chain`/`:seon.error/base` schemas** (P3). `chain` also keeps each link's ex-data raw and unbounded (`:seon.error/data data`, line 85). | A broken error schema refuses the constructor, and that refusal replaces the original. Raw ex-data carries handles to every printer downstream. |
| **Instrument violation** | `src/seon/instrument.clj:272-395`; arm failures `registration-error` `:826-842` | **Malli explain + `error/problem-sentence` / `explain-problem` (prose owner)**; `registration-error` calls the armed `refusal/diagnostic`. `:seon.error/offending` is the live argument (whole handles). | A contract failure *about the contract machinery* is reported through that machinery. The offending live value reaches the wire whole (the 2 GB answer). |
| **Core fault** `seon.fault/record!` → `fault!` | `src/seon/fault.clj:43-155, 170-217` | **armed wrapper**, **config read**, **`db/carried-projection`**, **`error/prepare`** (`src/seon/error.clj:515-671`: admission, `validate-declaration!` ×2, signature under the projection), blob store, **database**. The last-resort catch keeps only `class: message`, or `(str fault)`, which can be megabytes (schedule row 30). | A recording failure turns the original into one line. Under `:panic` the ex-info message is the fact's message, and `ex-data` holds only the receipt. The original survives only as the cause, which reaches a viewer only if the next printer prints causes. |
| **Operator request** `boot/request!` → `readable-response` | `src/seon/cluster/boot.clj:559-690`; `diagnostic` `:264-301`; `readable-response` `:535-546` | `diagnostic` 4-arity calls **`render.value/render-ai` + `render/agent-render-profile`** on the offending value and on each chain link's ex-data (`a85bf004b`). `readable-response` round-trips `pr-str`→`edn/read-string`, and on failure calls `diagnostic`, which renders again. The catch calls `fault/fault!` (**database**), which throws a panic in development. | A renderer failure inside the `catch` throws out of it, and the prepl shows the render failure instead of the boot failure (the `init` "no cause" sighting). A `:panic` escapes `request!` as a prepl exception event, so what the operator shows depends on the prepl path below. |
| **Operator prepl** `seon.operator.prepl/io-prepl` | `resources/seon/operator/prepl.clj:5-36` | `ns-resolve`s `seon.cluster/mcp-valf` (**projection, admission, renderer, config read, blob**). When that is unloaded, `pr-str` of the raw `:val`. For an exception event that is `Throwable->map` with **raw ex-data** (P1). The out-fn is not total: a `valf` throw propagates into `clojure.core.server/prepl`'s catches (`reference-code/clojure/src/clj/clojure/core/server.clj:249-261`), which call out-fn again, and a second throw ends the session. | A printed Var or `#object` makes the reply unreadable. A projection failure kills the answer or the session. |
| **Operator client** `prepl-value!` | `script/seon/operator.clj:133-181` | **EDN reader** on `:val`. A read failure throws "Malformed PREPL result", and its reader message is what the user sees. | The boot failure is replaced by `No dispatch macro for: '`. |
| **MCP projection** `mcp-valf` → `mcp-project` | `src/seon/cluster.clj:364-547` | For an exception, `exception-summary` (`:364-382`) keeps the root message, root class and a frame from `first-seon-frame`. Only `:seon.error/message` is admitted. Then **config read (db) → `admit-value` (armed, projection) → `render.value/artifact`/`prepare` (renderer, render profile) → blob** → `canonical-edn`. The fallback `mcp-projection-error` (`:384-406`, **armed**) keeps the failure's message and drops the original value's failure entirely. | P4: ex-data, the outer message and the chain are always lost, even when every component is healthy. With a broken projection, every reply is the projection refusal. |
| **MCP client** | `script/seon/dev/mcp.clj:327-360` | **EDN reader**. `decoded-projection-event` catches a read failure and keeps the raw string (`:353-359`), which is correct. | Only what the server sent. |
| **Test runner member failure** | `src/seon/test/runner.clj:62-156, 253-276`; stored as `:seon.test/failure-message` `:339-345` | `throwable-text` (`:98-112`) keeps the **outermost** link's class, message and raw stack. There is no cause chain, no ex-data and no root frames. A non-Throwable expected/actual goes through **`value/render-ai` + `report-options` → `schema/handed-projection`** (`:71-80`), inside clojure.test's report hook. | The root cause of a wrapped failure is never stored. A render failure throws inside `do-report` and replaces the assertion's report. |
| **Emergency printers** (stderr) | `boot.clj:324-333` `uncaught-emergency!` (**`prn (Throwable->map …)` unbounded**); `boot.clj:373-378`; `cluster.clj:3307-3328` `emit-core-fault!` (**the prepared fact's message**, so it needs `error/prepare`); `flow.clj:1170-1190` `report-committer-loss!` (**class only**); `turn.clj:5386, 5409` | See each entry. | Either unbounded (GB to stderr) or reduced to class or message, with no chain and no frames. |
| **Log line** `error/log-line` | `src/seon/error.clj:1219-1283` | A **prepared fact** (a projection and database product). | Nothing is logged when preparation fails. |
| **Datahike raise** | `persistent_set.cljc:439`; `replikativ.logging/raise` logs at the raise site (schedule row 24h) | Whatever the logger prints | The konserve store object is printed whole. |

**One pattern runs through the whole table.** The error's *display* is computed late, by
the richest component, and shares that component's fate. Nothing computes a display
first from the Throwable alone.

## 3. Design

### 3.1 The floor: one function of the Throwable

`seon.error.refusal/floor` : Throwable → string. It grows the existing pure
cause-chain owner in place (`refusal.clj` already has `chain`, `root-frame` and
`frame-function`, and its docstring says "Pure diagnostic construction"). It requires
only `clojure.core`, like the rest of that namespace.

- **Content, per link, outermost to root** (as `Throwable->map :via` orders it,
  `reference-code/clojure/src/clj/clojure/core_print.clj:473-500`): the class name and
  message, then up to 12 ex-data entries `key value`, then up to 6 first-party frames.
  Frames reuse `frame-function`/`first-party-frame?`, so machinery frames such as
  `seon.error.*`, `seon.instrument` and the `io-prepl` frame from P4 drop out; that list
  gains `seon.operator.`. At most 8 links. `.getSuppressed` entries are listed by class
  and message: the suppressed recording failure in a panic is exactly what must be seen.
- **Bounded by construction.** Each value prints into a `java.io.Writer` that throws a
  private sentinel after N chars (≈10 lines), with `*print-length*` 8,
  `*print-level*` 3 and `*print-meta*` false. Cost is O(N) per field whatever the value
  size: a 1 GB string or a 10⁶-element vector costs the same as a small one. A
  print-method that throws becomes `#unprintable[<class> <failure class>]`. Total text
  ≤ ~8 KB. The caps are compiled constants (`seon.config/defaults` keys, not cluster
  configuration). They are not read from the database, because the database may be
  what failed.
- **Output is a string.** A string is always readable EDN (P5), and it is readable
  by both a human and an agent. Its data twin (class, message, chain) remains
  `refusal/chain`, the rich layer. The floor is deliberately not a second data shape.
- **Dependency seams used, none rebuilt:** `ex-message`, `ex-data`, `ex-cause`,
  `Throwable/getSuppressed`, `StackTraceElement`, `clojure.lang.Compiler/demunge`,
  `print-method`. The floor does **not** use `clojure.main/ex-triage`/`ex-str` (P2):
  they keep only the root link and would drop exactly the outer context this lane
  exists to keep. `ex-triage`'s `:clojure.error/phase` is still read when present
  (prepl read/compile phases), so the first line can name the phase.
- **Cost:** O(links × (entries × N + frames)), constant-bounded, measured at 372 µs
  (P5). No database, projection, contract, renderer or allocation proportional to the
  value. **Simplest alternative considered:** `ex-str` alone (drops the chain);
  `pr-str (Throwable->map t)` under print bindings (still unbounded for a single huge
  string or a throwing print-method, and raw-object reprs make it unreadable, P1).

**Arming.** The floor keeps a declared contract (the law: every function has one) but
must work when contracts do not. §7 asks the owner to decide how. The recommendation is a
declared "never armed" property for the floor alone.

### 3.2 The wire rule: replies are data, and the floor rides first

1. **Every error reply carries `:seon.error/floor` (a string) as its first member**,
   computed before any projection, render, config read or database write. That covers
   the prepl `:ret` exception event, the operator response, the MCP face and the test
   member's failure message.
2. **A layer that fails adds, never replaces.** The rich layer (projection, render, blob,
   recording) runs inside `try`. On failure, the reply keeps the original floor and
   gains `:seon.error/layer-failures [<floor of the layer's failure> …]`. The "No
   swallowed errors" rule holds: the whole failure is shown, just not in place of the
   original.
3. **Nothing unreadable crosses a wire.** Every value that leaves a process has been
   through admission (readable by construction) or is floor text. The `pr-str` of raw
   `Throwable->map` goes away at every site (`prepl.clj:32`, `boot.clj:329`). The wire
   renders nothing: bounded evidence at the wire uses the floor's bounded printer, not
   `render-ai`. This partly reverts `a85bf004b`, keeping its bound while removing its
   renderer dependency.
4. **Clients never lose the floor.** `prepl-value!` (`operator.clj:174-181`) and
   `mcp.clj` show `:seon.error/floor` when present. On a read failure they show the raw
   bounded text together with the reader error, instead of replacing the text with the
   reader error.
5. **The prepl out-fn is total.** `io-prepl` reads the live Throwable from `*e`: prepl
   sets `*e` before calling out-fn on the same thread (`server.clj:250-254, 257-260`).
   It computes the floor from `*e` and only then calls the projector inside `try`,
   before it clears `*e` (5aa3989d0 keeps that clearing). The projector is passed in as
   a `:valf` option, as in `clojure.core.server/io-prepl` (`server.clj:275-296`), so
   a regression can hand in one that throws without redefining a default Var.

### 3.3 Recording is separate from display

The floor is shown on the caller's path (thrown, returned or printed) whatever
`record!` does. `fault!` computes the floor first and puts it in the panic's ex-data as
`:seon.error/floor`. The panic message stays one line:
`SEON CORE FAULT (panic): <root class>: <root message>`. The existing
`.addSuppressed` of the recording failure stays, so the floor shows that failure too.
`record!`'s last-resort catch (`fault.clj:143-155`) returns both floors instead of
`class: message` / `(str fault)`, which removes schedule row 30's megabyte `str`.
**Database down:** the policy is unchanged (panic in both modes, no fallback store,
no retry). What changes is that the panic now *carries* the original floor, so the
reachable REPL and stderr show the cause, not only "could not record". Emergency stderr
prints the floor once. That is not print-only, because the throw still happens.

### 3.4 What each current mechanism becomes

| Mechanism | Becomes |
|---|---|
| `refusal/chain`, `root-frame`, `diagnostic` | **keep** as the rich data layer; `chain` stops carrying raw ex-data and carries the floor's bounded print of it instead (fixes the 2 GB class at the source) |
| `refusal/floor` | **new**, ≈45 lines including the bounded writer |
| `cluster/exception-summary`, `first-seon-frame`, `nil-deref?` (`cluster.clj:350-382`) | **delete**: the floor is the exception face, and the REPL lane's face composes floor + `chain` |
| `cluster/mcp-projection-error` | **shrink**: floor of the failure + value class; no contract-armed fallback map |
| `boot/diagnostic` 4-arity `render-ai` calls, and the `render` / `render.value` requires in `boot.clj` | **delete**; bounded print via the floor printer |
| `boot/readable-response` round trip | **keep the check**; its failure branch returns `{:seon.error/floor …}` without calling `diagnostic` |
| `boot/uncaught-emergency!` `prn Throwable->map`, the `record-uncaught!` dev print, `cluster/emit-core-fault!`, `flow/report-committer-loss!`, `turn.clj:5386/5409` printers | **convert** to one `println` of the floor (the rich parts stay where they add information, such as a signature) |
| `runner/throwable-text`, `printable`'s Throwable branch | **delete** → `refusal/floor`; stored failure messages gain the whole chain |
| `runner/failure-message` render of expected/actual | **keep**, inside `try`; a render failure appends its floor plus a bounded print of the value |
| `error/log-line` | **keep** (rich layer); the floor is what reaches stderr when preparation fails |
| operator/MCP clients' read-failure branches | **convert**: raw text + reader error, floor first |
| Datahike `:store` in `:node-not-found` ex-data | fork one-liner, folded into schedule row 24h (datahike held by 24q); the floor already bounds it |

## 4. Slice plan (each is one loadable slice, adds ≤ ~100 src lines, deletes where it can)

Every regression asserts WANTED behaviour and runs on default's JVM through
`bin/test-check --test …`. No scratch roots. The "broken projection" case uses a
**throwaway context**: a fork with an empty handed projection, or a throwaway namespace
whose schemas are unregistered. Default's Vars are never redefined.

| # | Slice | Files (holder) | +src / −src (est.) | Regression (wanted behaviour) |
|---|---|---|---|---|
| E1 | `refusal/floor` + bounded writer; `chain` bounds ex-data through it; floor exempt from arming (§7 option chosen) | `src/seon/error/refusal.clj`, `src/seon/instrument.clj` (`collect-contracts!` `:815-825` + changed-candidate filter `:909-918`, ≈4 lines) — both **free** | +50 / −3 | `floor-shows-every-link-bounded`: P5's exception (Var, 100k vector, `(range)`, throwing `toString`, three links, a suppressed failure) → text < 8 KB, < 5 ms, every class, message and ex-data key, ≥ 1 first-party frame per link, `edn/read-string` of `pr-str` round-trips. `floor-needs-no-projection`: with projection and contracts unavailable in a throwaway context, the floor still returns class + message + frames, and the Var is unarmed after `instrument/apply!`. |
| E2 | Total `io-prepl` with `:valf` option; floor from `*e` onto exception events; projector failure adds a layer failure; no raw `pr-str` of `Throwable->map`. Clients show the floor and keep raw text on read failure. | `resources/seon/operator/prepl.clj`, `script/seon/operator.clj`, `script/seon/dev/mcp.clj` — **free** (released by repl-star-vars / opus-publication); coordinate with repl-prd, which may claim `mcp.clj` | +30 / −10 | `prepl-reply-is-readable-and-carries-the-floor`: a prepl session whose form throws ex-info carrying a Var and a 10⁶ vector → every event `edn/read`s, `:seon.error/floor` names both links, reply < 64 KB. `prepl-survives-a-throwing-projector`: `:valf` that throws → the reply keeps the original floor + the projector's floor, and the session answers the next form. |
| E3 | Boot: floor first in `diagnostic`; delete the `render-ai` calls; `readable-response` failure returns the floor only; `uncaught-emergency!` + dev print → floor | `src/seon/cluster/boot.clj` — **free** | +12 / −25 | Convert `boot_reply_test` (a85bf004b): the large-evidence reply stays bounded *and* carries `:seon.error/floor`. New `request-failure-shows-its-cause-when-rendering-is-unavailable`: a `request!` whose evidence has a throwing `print-method` → reply readable, floor names the original failure. |
| E4 | `fault!`/`record!`: floor before record; panic ex-data `:seon.error/floor`; last-resort catch returns both floors | `src/seon/fault.clj` — free today, but lands **inside or after the M4 error-route slice** (same file) | +8 / −8 | `a-recording-failure-never-hides-the-original`: `fault!` against a closed connection in a throwaway store → panic whose `:seon.error/floor` names the original class and message, with the recording failure among the suppressed. |
| E5 | MCP face = floor (+ `chain` when healthy); delete `exception-summary`/`first-seon-frame`/`nil-deref?`; shrink `mcp-projection-error`; `emit-core-fault!` → floor | `src/seon/cluster.clj` — **held by m4-n1**; queue as a follow-up after its release, and hand the face composition to repl-prd if that lane holds `mcp-valf` | +10 / −45 | `mcp-exception-face-keeps-outer-context`: P4's form → face contains "outer probe", `:probe/k`, "root probe" and a first-party frame (not `io_prepl`). `mcp-face-survives-a-refused-projection`: a value whose admission throws → the original floor is present. |
| E6 | Runner: `throwable-text`/`printable` → floor; render of expected/actual inside `try` | `src/seon/test/runner.clj` — **held by test-overhead**; follow-up after release | +6 / −18 | `a-wrapped-test-error-stores-its-root-cause`: a test throwing `(ex-info "outer" {} (IllegalStateException. "root"))` → stored `:seon.test/failure-message` contains both links and a root first-party frame. |
| E7 | `flow/report-committer-loss!`, `turn.clj` stderr printers → floor; Datahike `:store` out of `:node-not-found` data | `src/seon/flow.clj`, `src/seon/turn.clj` (**m4-n1**); `reference-code/datahike` (**24q**) | +4 / −8 | Each printer's existing test asserts the floor's first line; the fork change gets a raise-data key test in the fork. |

**Order:** E1 → E2 and E3 in parallel (disjoint, both free) → E4 with M4 → E5/E6/E7 as
their holders release. E1 alone already fixes the source of the 2 GB class (bounded
`chain`). E1+E2 end "No dispatch macro" masking and the dead-session class.

**Projected net lines:** src ≈ +120 / −117, **net ≈ +3**, and ≈ −30 if §7 option 1 is
chosen (no instrument exemption, a smaller floor). Test ≈ +150 / −10 (one regression per
behaviour class). This is net-neutral, not a shrink. What it deletes is the late,
braided display code: `exception-summary`, the wire renders and `throwable-text`. The
largest follow-on deletion it enables is `cluster/mcp-projection-error`'s whole
contract-armed fallback family once repl-prd rebuilds `mcp-valf` on the floor. That
follow-on is owed to repl-prd. The `Src-growth:` trailer for E1 names E5 as the deletion.

## 5. Braids, roles, and whether each part can fail alone

| Part | One role | Fails alone after this design? |
|---|---|---|
| `refusal/floor` | Throwable → bounded text | yes: core only, unarmed, bounded, total (catches its own print failures per field) |
| rich layer (`chain`, `error/prepare`, render, MCP projection) | explain richly | yes: its failure is appended to the floor, never substituted |
| recording (`record!`, database) | durable fact + wake | yes: display no longer depends on it; the database-down panic carries the floor |
| wire (prepl, operator/MCP clients) | carry data | yes: the floor is a string, readable by construction; clients keep raw text |
| policy (`fault/policy`) | how loud | unchanged |

**Braids removed:** display↔projection, display↔contracts, display↔renderer,
display↔database, reply↔EDN reader, the error constructor↔its own contract.
**Braid added:** none. The floor depends on `clojure.core` alone.
**Risks (unresolved):**
1. A user `print-method` that does unbounded work *before* writing is still paid.
   The bounded writer stops output, not computation. This is not observed today;
   Datahike's DB print is bounded.
2. `.getSuppressed` and `ex-cause` cycles: bounded by the 8-link `take`. The suppressed
   entries of each link are printed one level deep only.
3. The "Requested array size exceeds VM limit" site is unverified. E2/E5 make the next
   occurrence name it; it gets no issue note until then.

## 6. Collisions and coordination

- `cluster.clj`, `flow.clj`, `turn.clj`: m4-n1 holds them. E5 and E7 wait for release;
  nothing here runs beside it.
- `runner.clj`: test-overhead holds it. E6 waits.
- `fault.clj`: lands with or after the M4 error-route slice (same file), without
  changing its recording design.
- repl-prd: its PRD was not committed when this was written. The rule is that repl-prd
  owns `mcp-valf`/ThreadLocal/`get_value` and consumes `refusal/floor` and §3.2. If
  repl-prd claims `script/seon/dev/mcp.clj` or `prepl.clj`, E2's client/prepl halves go
  to it as a follow-up with the regressions above.
- `reference-code/datahike`: 24q holds it. The `:store` one-liner joins row 24h.

## 7. Owner decision: how the floor escapes arming

The floor must run when contracts are broken, yet the law says every function has a
complete contract and the armed wrapper runs it (P3). Three options, simplest viable
first:

1. **Core-only file, no contract.** Put the floor beside `io-prepl` in
   `resources/seon/operator/` (that file is already core-only and uncontracted). The
   program rows do not index it. *Guarantee:* works with projection/contracts broken;
   zero instrument change. *Cost:* ≈40 src lines; E1 shrinks to +40. *Gives up:* the
   contract law for one function, and agents cannot find it as a program function.
2. **(Recommended) Declared contract, never armed.** The floor stays in
   `seon.error.refusal` with a complete contract (`[:=> [:cat :seon.error/throwable]
   :string]`) and one declared Var property `:seon.instrument/never-armed` with its
   reason. Two `instrument.clj` filter sites (≈4 lines) honour it, and the contracts
   compile check still compiles it. *Guarantee:* works with contracts broken; the
   contract stays declared, compiled and visible; a regression replaces runtime
   checking. *Cost:* ≈4 extra src lines + one regression. *Gives up:* runtime
   validation of one total function, and adds one arming exemption. Any later use of the
   property needs the owner's permission, so it stays one-of-a-kind.
3. **Armed, with a local fallback.** Keep arming, and have each call site catch the
   floor's own refusal and emit `(str class ": " message)`. *Guarantee:* the class and
   message survive. *Cost:* ~1 line × ~10 sites. *Gives up:* the chain and frames
   exactly when contracts are broken, which is the case this lane exists for. It also
   adds a second fallback, the braid this design removes. **Not recommended.**

## 8. Verification boundary

Investigation and design only. There were no source edits, adoption, test-check or
publication. Evidence: P1–P5 above (MCP `eval_clj`, JVM mode, private session,
read-only, throwaway namespace). Every operation was sub-second: 2 ms, 5 ms, 7 ms and
30 ms evaluations, and file reads under 100 ms. Source line citations are against the
working tree at HEAD `db296b08f`. `cluster.clj` and `runner.clj` hold uncommitted WIP by
their holders, so their line numbers may move.
