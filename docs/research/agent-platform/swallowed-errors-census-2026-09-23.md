# Swallowed-errors census (2026-09-23)

Lane `swallowed-errors-census`. Fact-finding only; no source edits. Authority:
AGENTS.md "No swallowed errors" (owner, 2026-09-23: "Do not allow swallowing of
errors") and "Total, honest boundaries".

**What was walked.** Every `(catch …)` form, every timed `deref`/`.get`, and every
`alts!!`/`<!!`/`poll!`/`offer!` in `src/`, `script/`, `bin/seon-hook` and
`bin/test-check` (the two Babashka scripts; the other `bin/` files are shell).
One rewrite-clj walk ran over the working tree at HEAD `3fbf9dd48`. Fifteen
src/script/bin files had uncommitted edits from other lanes, so line numbers can
move. It found 343 catch sites (matching `grep -c '(catch '`) and 74 wait/offer sites.
Scripts: `tmp/swallowed-errors-census/{walk,classify,tables,probe}.clj`, raw rows in
`tmp/swallowed-errors-census/rows.edn`. Each flagged site was classified from its
catch clause and, for helpers and ambiguous sites, from the enclosing form or the
helper definition. No site was read file by file.

**Classes.** **A**: the site swallows the failure and must re-surface the full
cause. **B**: the site handles a declared case (a typed JDK class mapped to a declared
refusal, a declared `ex-data` member otherwise rethrown, a rethrow, a carried live
Throwable, or the R41 core-error policy). **C**: the answer is plausibly the declared
case, but the catch class is too broad to prove it (mostly total predicates), or an
owner ruling is needed.

## The one owner fix that comes first (F0): no constructor carries the cause

The census asked for the error constructor that records a cause. There is none that
carries the whole cause.

- `seon.error.refusal/diagnostic` (`src/seon/error/refusal.clj:4`), the 1.1
  constructor, takes `:seon.error/throwable` and keeps only
  `:seon.error/exception-class` plus the **outermost** throwable's first stack frame.
  It drops the message, every `ex-data` map, the cause chain and the other frames.
- `seon.error/prepare` (`src/seon/error.clj:551`) keeps the class, `top-frame`
  (`error.clj:149`, again the outermost throwable's first frame), the deepest
  `ex-data` (`error.refusal/refusal`) and the root message. Its own docstring
  (`error.clj:118-128`) says the chain "is not recoverable from `data-edn`".
- `:seon.error/cause` (`resources/seon/schemas/seon.error.edn:316`) is declared
  `:seon.db/ref`, so it is not a cause chain. `script/seon/operator.clj:292` puts a
  `Throwable->map` value under that key on the wire, but only when the failure
  escapes `seon.cluster.boot/request!`. `boot.clj:443` catches first and keeps only
  `ex-message`. That is the 2026-09-23 nuke path.
- Hand-built partial chain readers already exist: `seon.ai/cause-chain`
  (`ai.clj:1160`, "class: message" strings only), `sci/eval.clj:1299`,
  `instrument.clj:514` `registration-cause-data`, and `bin/seon-hook:459`
  `throwable-message` (first non-blank message).
- The whole-cause carriers already in the tree are `clojure.core/Throwable->map`
  (`reference-code/clojure/src/clj/clojure/core_print.clj:473`) and the printer's
  `:seon.print/throwable` face, which admits through `Throwable->map`
  (`src/seon/sci/admit.clj:257-259`). The test runner uses the first at `runner.clj:3408`,
  `:3472` and `result-read-error`.

**Probe (executed, JVM `clojure-1.12.5`, `tmp/swallowed-errors-census/probe.clj`).**
`inner` throws `(ex-info "Keyword cannot be cast to Number" {:seon.probe/leaf 1})`, and
`outer` wraps it with `(ex-info "wrapper" {:seon.probe/outer 2} e)`.
- `diagnostic` returned only the keys `(:seon.error/at :seon.error/exception-class
  :seon.error/frame :seon.error/layer :seon.error/operation)`.
- The frame was `[user$outer invokeStatic "probe.clj" 3]`, the wrapper's frame and not
  the root's. `ex-data kept? false false`.
- `Throwable->map` returned `:via` with both links (type, message, data), `:cause`
  `"Keyword cannot be cast to Number"`, and 21 `:trace` frames. Under bb/SCI the
  diagnostic frame was `[sci.lang.Var invoke "lang.cljc" 213]`, an interpreter frame.

**F0 (B3, plan step 1.1, accreted in place, no new constructor).** When
`:seon.error/throwable` is supplied, `diagnostic` also records the chain from
`Throwable->map`: per link the class, message and `ex-data`, the root message, and the
frames whose demunged class is first-party (`seon.*`/`my.*`, the rule
`error.clj:357` `stack-failing-function` already applies). It takes
`:seon.error/frame` from the root cause, not the wrapper. `seon.error/prepare` uses the
same leaf. The member name is an **owner decision**: search the registry first. Do not
repurpose the ref-typed `:seon.error/cause`. It is a schema-resource change, so it
needs the incremental adoption proof (plan 1.3e). F0 turns the 9 R-DIAG sites into B
with no caller edit. It is the precondition for R-HELPER and R-MSG, and it retires
`seon.ai/cause-chain` and the hook's `throwable-message`.

## Rules

| rule | what the site does | fix | mechanism |
|---|---|---|---|
| R-DIAG | already hands `:seon.error/throwable` to `refusal/diagnostic` (or via `handler-failure`/`exception-value`) | none after F0 | F0 alone |
| R-HELPER | calls a private helper that keeps message/class/`ex-data` but not the throwable | pass the throwable through `diagnostic` inside the helper; callers unchanged | one hand edit per helper |
| R-MSG | builds a `:seon.error/*` map inline with `(ex-message e)` or a fixed message | `(refusal/diagnostic (assoc <map> :seon.error/throwable e))` | ONE rewrite-clj script (below) |
| R-UNKNOWN-STR | turns the failure into a string inside a non-error shape (`{:status :unavailable :reason msg}`, `(str "unavailable: " msg)`, `{::unreadable msg}`) | typed unknown carrying the diagnostic | hand; many die with B4 machinery |
| R-LOG | logs `(.getMessage e)`/`ex-message` and returns nil/false/exit | log `(pr-str (Throwable->map e))` and return the typed unknown | one sed for `bin/seon-hook` `log!` sites; hand elsewhere |
| R-DISCARD | `(catch Throwable\|Exception _ nil\|false\|default)` in ordinary code | name the declared case and narrow the class, or delete the try | hand, one decision per site |
| R-PRED | a total predicate or parse probe answers false/nil on a broad class | narrow to the dependency's declared exception | owner ruling, then hand (C) |
| R-CAUSE | rethrows or refuses without chaining the caught throwable | add it as the `ex-info` cause | hand (2 sites) |
| R-EXDATA | returns `ex-data` of any `ExceptionInfo` | declared-member check, else rethrow | hand (1) |
| R-OFFER | offers a fault to a channel and drops `offer!`'s false | on false, apply the core-error policy with the full cause | hand (4) |
| R-TIMEOUT-ABSENT | a timed `deref` sentinel becomes nil/absence | typed timeout naming the bound | hand (4) |
| B-* | typed, declared, rethrow, carried Throwable, `addSuppressed`, R41 policy, handled wait, losable signal | none | — |

**Helpers behind R-HELPER (36 sites):**
- `seon.db/dependency-error` (`db.clj:204`), 8 sites. It keeps message, class name and
  top `ex-data`. `pull-budget-error` (`db.clj:2175`) keeps message only.
- `seon.fs.jvm/error-value` (`fs/jvm.clj:51`), 5 sites, and
  `seon.edit.jvm/filesystem-refusal` (`edit/jvm.clj:90`), 1 site. On the undeclared
  path both drop the throwable through `flat-error`.
- `seon.cluster.boot/diagnostic` (`boot.clj:190`, no throwable parameter), 5 sites:
  `boot.clj:351`, `:443` (the nuke), `operator.clj:359`, `:437`, `bin/seon-hook:1519`.
- `seon.instrument/failure-cause` (`instrument.clj:140`, message only), 3 sites.
  `registration-cause-data` (`instrument.clj:514`) + message, 1 site (`instrument.clj:871`).
- `seon.sci.kernel/failure-value` (`kernel.clj:532`, class + `ex-data`, no frames or
  chain), 2 sites: `kernel.clj:658` and `sci/eval.clj:3267`.
- `seon.web.jvm/classified-error` (`web/jvm.clj:35`), 2 sites.
- `seon.render.web/failed-page-result` and `unreportable-page-result`
  (`render/web.clj:2236`, `:2291`, class + message, warn-log), 2 sites.
- One site each: `seon.render/walk-error` (`render.clj:1702`),
  `seon.test.runner/unconfirmed-confirmation` (`runner.clj:4317`),
  `seon.bootstrap-drive/failed-report` (`bootstrap_drive.clj:356`),
  `seon.cluster/mcp-projection-error` (`cluster.clj:349`), the `commit-fault!`
  last-resort shape (`cluster.clj:2820`), `seon.sci.eval/failure-result`
  (`sci/eval.clj:3214`), `seon.ai/unreadable-stream-data` (`ai.clj:809`).

**Sites to fix first (A, highest consequence).**
- `cluster/boot.clj:443` `request!`: the nuke refusal with no frame.
- `db.clj:2012` `query-call-valid?` answers **true** when validation throws. That
  reads failure as health.
- `error.clj:1061` `refusal-data` and `error.clj:762` `fact-source`: swallowing inside
  the error owner itself.
- `repl.clj:165` `error-text`, `repl.clj:461` `pretty-response`: error rendering
  swallows its own failure.
- `cluster/boot.clj:307` `stop!`, `cluster/source.clj:269`/`:280`, `cluster/wake.clj:560`
  `unlisten!`: lifecycle cleanup failures discarded.
- `turn.clj:5393`/`:5416`: a core fault that cannot be offered is printed as
  `ex-message` only.
- `cluster/wake.clj:388`/`:546`, `render/web.clj:2868`: fault `offer!` results dropped.

## Ranked conversion plan

1. **F0** (B3, hand, owner names the member). This gates everything below: without it
   every conversion still loses the chain and frames.
2. **R-HELPER** (36 sites, about 17 helpers). Hand-edit each helper. It has no caller
   edits and can land in the owners' files in parallel once F0 lands: A2 (`db.clj`),
   B1 (`boot.clj`, `operator.clj`, hook), B2 (`kernel.clj`, `sci/eval.clj`,
   `render*.clj`), B3 (`fs/jvm.clj`, `edit/jvm.clj`, `web/jvm.clj`, `instrument.clj`
   is A1).
3. **R-MSG** (27 A + 8 C). ONE rewrite-clj script over all catch bodies whose value is
   a map literal containing `:seon.error/at`. It rewrites the map to
   `(seon.error.refusal/diagnostic (assoc <map> :seon.error/throwable <binding>))` and
   adds the `seon.error.refusal` require where missing, then runs clj-kondo and a
   load. Residue by hand: maps inside `refuse!`/`assoc`/`with-meta` (`turn.clj:3445`,
   `runner.clj:3089`, `runner.clj:3290`, `cluster/export.clj:224`).
4. **R-LOG in `bin/seon-hook`** (10 sites). One sed:
   `(log! "TAG" (.getMessage error))` → `(log! "TAG" (pr-str (Throwable->map error)))`.
   Its `throwable-message` callers (`:556`, `:736`, `:760`, `:1792`) go by hand.
5. **B4 runner rows** (21 A in `seon/test/runner.clj`, mostly R-UNKNOWN-STR/R-MSG). Ask
   the three questions first: most belong to worker/slot/staged-result machinery that
   plan 1.3d commit 5 deletes. Delete them with it; do not convert them.
6. **R-DISCARD** (64 A + 14 C). Hand edits, one decision per site: the declared
   exception and its declared refusal, or no try at all. 14 are in `bin/seon-hook`,
   3 in `db.clj`, 4 in `repl.clj`.
7. **R-PRED** (29 C). Needs one owner ruling on the rule. A mechanical
   `Throwable`→`Exception` narrowing script is possible, but it only stops swallowing
   `Error`. Each predicate's declared parser class still has to be named by hand.
8. **R-OFFER, R-CAUSE, R-EXDATA, R-TIMEOUT-ABSENT** (12 sites). Small hand edits,
   bundled with their owner's slice.

Wait/offer sites are almost all B. Timed `.get` calls throw `TimeoutException`, which
the catch table covers. The `::expired`/`::timeout` sentinels are compared and refused.
`flow.clj:1176`/`:1287` read nil from `<!!` as channel close, which is correct. The 28
wake/kick `offer!`s are losable signals by the architecture's channel rule.

No existing issue covers this class (`rg -li swallow docs/seon/issues` → two unrelated
notes). The census commits no issue, per the lane boundary.

## Timings

| step | command | wall |
|---|---|---:|
| walk (417 sites, 109 files) | `bb tmp/swallowed-errors-census/walk.clj` | 2.37 s |
| classify + overrides | `bb tmp/swallowed-errors-census/classify.clj` | 0.05 s |
| constructor probe, bb/SCI | `bb tmp/swallowed-errors-census/probe.clj` | 0.03 s |
| constructor probe, JVM | `java -cp clojure-1.12.5.jar:… clojure.main probe.clj` | 0.53 s |

### Counts by class

| class | catch sites | wait/offer sites | total |
|---|---:|---:|---:|
| A | 177 | 4 | 181 |
| B | 114 | 66 | 180 |
| C | 52 | 4 | 56 |
| total | 343 | 74 | 417 |

### Counts by rule and class

| rule | A | B | C |
|---|---:|---:|---:|
| B-CARRY | 0 | 29 | 0 |
| B-DECLARED | 0 | 5 | 1 |
| B-LOSABLE | 0 | 28 | 0 |
| B-POLICY | 0 | 2 | 0 |
| B-RETHROW | 0 | 47 | 0 |
| B-SUPPRESSED | 0 | 2 | 0 |
| B-TYPED | 0 | 32 | 0 |
| B-WAIT-HANDLED | 0 | 35 | 0 |
| R-CAUSE | 2 | 0 | 0 |
| R-DIAG | 9 | 0 | 0 |
| R-DISCARD | 64 | 0 | 14 |
| R-EXDATA | 1 | 0 | 0 |
| R-HELPER | 36 | 0 | 0 |
| R-LOG | 15 | 0 | 0 |
| R-MSG | 27 | 0 | 8 |
| R-OFFER | 3 | 0 | 1 |
| R-PRED | 0 | 0 | 29 |
| R-TIMEOUT-ABSENT | 1 | 0 | 3 |
| R-UNKNOWN-STR | 23 | 0 | 0 |

### Counts by owner (A / B / C)

| owner | A | B | C | total |
|---|---:|---:|---:|---:|
| A1 | 13 | 8 | 13 | 34 |
| A2 | 11 | 12 | 5 | 28 |
| B1 | 61 | 34 | 11 | 106 |
| B2 | 35 | 57 | 14 | 106 |
| B3 | 29 | 41 | 8 | 78 |
| B4 | 31 | 26 | 5 | 62 |
| C1 | 0 | 0 | 0 | 0 |
| D1 | 1 | 2 | 0 | 3 |

### A and C sites by owner and rule

| owner | B-DECLARED | R-CAUSE | R-DIAG | R-DISCARD | R-EXDATA | R-HELPER | R-LOG | R-MSG | R-OFFER | R-PRED | R-TIMEOUT-ABSENT | R-UNKNOWN-STR |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A1 | 0 | 1 | 0 | 5 +2C | 0 | 4 | 0 | 3 +2C | 0 | 0 +9C | 0 | 0 |
| A2 | 0 +1C | 0 | 0 | 3 +1C | 0 | 8 | 0 | 0 | 0 | 0 +3C | 0 | 0 |
| B1 | 0 | 1 | 0 | 27 +5C | 0 | 7 | 12 | 4 +2C | 0 | 0 +3C | 0 +1C | 10 |
| B2 | 0 | 0 | 2 | 16 +4C | 0 | 7 | 2 | 5 +4C | 3 +1C | 0 +5C | 0 | 0 |
| B3 | 0 | 0 | 7 | 8 | 1 | 9 | 0 | 2 | 0 | 0 +7C | 0 +1C | 2 |
| B4 | 0 | 0 | 0 | 5 +2C | 0 | 1 | 1 | 12 | 0 | 0 +2C | 1 +1C | 11 |
| C1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| D1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 |

## Full site table

Every site the walk found, sorted by file and line. `fn` is the enclosing top-level form.

| site | fn | owner | class | rule |
|---|---|---|---|---|
| `bin/seon-hook:86` | `log!` | B1 | C | R-DISCARD |
| `bin/seon-hook:218` | `absolute-file-path` | B1 | A | R-DISCARD |
| `bin/seon-hook:245` | `display-path` | B1 | A | R-DISCARD |
| `bin/seon-hook:275` | `run-clj-kondo` | B1 | A | R-DISCARD |
| `bin/seon-hook:286` | `run-clj-kondo` | B1 | A | R-UNKNOWN-STR |
| `bin/seon-hook:342` | `read-transit-entry` | B1 | A | R-DISCARD |
| `bin/seon-hook:372` | `uncached-definition-analysis` | B1 | A | R-DISCARD |
| `bin/seon-hook:374` | `uncached-definition-analysis` | B1 | A | R-DISCARD |
| `bin/seon-hook:514` | `run-schema-admission` | B1 | A | R-DISCARD |
| `bin/seon-hook:523` | `run-schema-admission` | B1 | A | R-UNKNOWN-STR |
| `bin/seon-hook:556` | `reconstruct-exact-edit` | B1 | A | R-UNKNOWN-STR |
| `bin/seon-hook:736` | `reconstruct-patched-file` | B1 | A | R-UNKNOWN-STR |
| `bin/seon-hook:760` | `reconstruct-file-content` | B1 | A | R-UNKNOWN-STR |
| `bin/seon-hook:1039` | `markdown-feedback` | B1 | A | R-LOG |
| `bin/seon-hook:1061` | `docstring-feedback` | B1 | A | R-LOG |
| `bin/seon-hook:1088` | `with-pending-lock` | B1 | A | R-LOG |
| `bin/seon-hook:1112` | `read-pending-unlocked` | B1 | A | R-DISCARD |
| `bin/seon-hook:1161` | `clear-reviewed-pending!` | B1 | A | R-DISCARD |
| `bin/seon-hook:1191` | `load-rubric` | B1 | A | R-LOG |
| `bin/seon-hook:1294` | `call-agy` | B1 | B | B-WAIT-HANDLED |
| `bin/seon-hook:1307` | `call-agy` | B1 | B | B-TYPED |
| `bin/seon-hook:1310` | `call-agy` | B1 | A | R-LOG |
| `bin/seon-hook:1334` | `write-full-review!` | B1 | A | R-LOG |
| `bin/seon-hook:1343` | `process-alive?` | B1 | C | R-PRED |
| `bin/seon-hook:1349` | `read-review-worker-unlocked` | B1 | A | R-DISCARD |
| `bin/seon-hook:1375` | `launch-review-worker-unlocked!` | B1 | A | R-LOG |
| `bin/seon-hook:1402` | `schedule-gemini-review!` | B1 | A | R-LOG |
| `bin/seon-hook:1411` | `pending-files` | B1 | A | R-DISCARD |
| `bin/seon-hook:1453` | `run-review-worker!` | B1 | A | R-LOG |
| `bin/seon-hook:1457` | `run-review-worker!` | B1 | A | R-DISCARD |
| `bin/seon-hook:1501` | `publish-source-paths` | B1 | A | R-MSG |
| `bin/seon-hook:1519` | `publish-source-paths` | B1 | A | R-HELPER |
| `bin/seon-hook:1620` | `recorded-digests` | B1 | A | R-DISCARD |
| `bin/seon-hook:1743` | `-main` | B1 | A | R-DISCARD |
| `bin/seon-hook:1792` | `-main` | B1 | A | R-LOG |
| `bin/seon-hook:1848` | `-main` | B1 | B | B-CARRY |
| `bin/test-check:74` | `` | B4 | A | R-LOG |
| `script/seon/dev/changed_test.clj:163` | `analyze-host` | B4 | A | R-MSG |
| `script/seon/dev/changed_test.clj:212` | `run-command!` | B4 | B | B-RETHROW |
| `script/seon/dev/clj_kondo.clj:95` | `ensure-dependency-cache!` | B1 | A | R-UNKNOWN-STR |
| `script/seon/dev/dependency_digest.clj:78` | `git-dependency-pins` | B4 | A | R-TIMEOUT-ABSENT |
| `script/seon/dev/docstring.clj:164` | `safe-sexpr` | B3 | C | R-PRED |
| `script/seon/dev/docstring.clj:299` | `check-source` | B3 | A | R-DISCARD |
| `script/seon/dev/docstring.clj:378` | `scan` | B3 | A | R-DISCARD |
| `script/seon/dev/markdown.clj:1153` | `validate-repository-pins` | B3 | A | R-UNKNOWN-STR |
| `script/seon/dev/mcp.clj:106` | `discovery-rows` | B1 | A | R-DISCARD |
| `script/seon/dev/mcp.clj:194` | `close-clj-session!` | B1 | C | R-DISCARD |
| `script/seon/dev/mcp.clj:214` | `open-clj-session!` | B1 | B | B-RETHROW |
| `script/seon/dev/mcp.clj:215` | `open-clj-session!` | B1 | C | R-DISCARD |
| `script/seon/dev/mcp.clj:313` | `require-single-clj-form!` | B1 | B | B-RETHROW |
| `script/seon/dev/mcp.clj:320` | `require-single-clj-form!` | B1 | B | B-RETHROW |
| `script/seon/dev/mcp.clj:356` | `decoded-projection-event` | B1 | A | R-DISCARD |
| `script/seon/dev/mcp.clj:448` | `namespace-symbol!` | B1 | A | R-DISCARD |
| `script/seon/dev/mcp.clj:559` | `execute-clj-eval` | B1 | B | B-CARRY |
| `script/seon/dev/mcp.clj:604` | `execute-clj-eval` | B1 | B | B-TYPED |
| `script/seon/dev/mcp.clj:615` | `execute-clj-eval` | B1 | A | R-MSG |
| `script/seon/dev/mcp.clj:662` | `runtime-observation` | B1 | A | R-UNKNOWN-STR |
| `script/seon/dev/mcp.clj:715` | `path!` | B1 | A | R-DISCARD |
| `script/seon/dev/mcp.clj:843` | `handle-request` | B1 | A | R-LOG |
| `script/seon/dev/mcp.clj:872` | `start-parent-watchdog!` | B1 | A | R-LOG |
| `script/seon/dev/mcp.clj:886` | `-main` | B1 | B | B-DECLARED |
| `script/seon/operator.clj:151` | `prepl-value!` | B1 | B | B-TYPED |
| `script/seon/operator.clj:162` | `prepl-value!` | B1 | A | R-CAUSE |
| `script/seon/operator.clj:210` | `connected!` | B1 | B | B-WAIT-HANDLED |
| `script/seon/operator.clj:216` | `terminate!` | B1 | B | B-WAIT-HANDLED |
| `script/seon/operator.clj:217` | `terminate!` | B1 | B | B-TYPED |
| `script/seon/operator.clj:220` | `terminate!` | B1 | B | B-WAIT-HANDLED |
| `script/seon/operator.clj:232` | `down!` | B1 | A | R-DISCARD |
| `script/seon/operator.clj:245` | `force-stop!` | B1 | A | R-DISCARD |
| `script/seon/operator.clj:247` | `force-stop!` | B1 | B | B-WAIT-HANDLED |
| `script/seon/operator.clj:292` | `launch-form` | B1 | B | B-CARRY |
| `script/seon/operator.clj:318` | `launch!` | B1 | B | B-WAIT-HANDLED |
| `script/seon/operator.clj:331` | `launch!` | B1 | B | B-WAIT-HANDLED |
| `script/seon/operator.clj:340` | `launch!` | B1 | B | B-WAIT-HANDLED |
| `script/seon/operator.clj:359` | `request!` | B1 | A | R-HELPER |
| `script/seon/operator.clj:437` | `-main` | B1 | A | R-HELPER |
| `my/program.clj:24` | `read-result` | B1 | A | R-MSG |
| `seon/ai.clj:661` | `extra-body` | B2 | A | R-MSG |
| `seon/ai.clj:809` | `stream-event` | B2 | A | R-HELPER |
| `seon/ai.clj:878` | `stream-fold` | B2 | A | R-DISCARD |
| `seon/ai.clj:1201` | `interruptible-lines` | B2 | B | B-CARRY |
| `seon/ai.clj:1423` | `send-request` | B2 | C | R-MSG |
| `seon/ai.clj:1459` | `send-request` | B2 | B | B-TYPED |
| `seon/ai.clj:1484` | `send-request` | B2 | A | R-DIAG |
| `seon/await.clj:91` | `await-port-operations` | B3 | B | B-WAIT-HANDLED |
| `seon/await.clj:140` | `await!` | B3 | B | B-WAIT-HANDLED |
| `seon/await.clj:141` | `await!` | B3 | B | B-TYPED |
| `seon/await.clj:145` | `await!` | B3 | B | B-WAIT-HANDLED |
| `seon/blob.clj:54` | `store-faithful-edn` | A2 | C | R-PRED |
| `seon/blob.clj:292` | `stage-binary!` | A2 | B | B-RETHROW |
| `seon/bootstrap.clj:94` | `render-help-html` | B3 | A | R-DISCARD |
| `seon/bootstrap.clj:769` | `calls-symbol?` | B3 | C | R-PRED |
| `seon/bootstrap.clj:778` | `contains-history-query?` | B3 | C | R-PRED |
| `seon/bootstrap_drive.clj:464` | `run-drives!` | B3 | A | R-HELPER |
| `seon/call_preparation.clj:476` | `snapshot` | A1 | A | R-MSG |
| `seon/call_preparation.clj:1104` | `supply` | A1 | A | R-DISCARD |
| `seon/call_preparation.clj:1117` | `supply` | A1 | A | R-MSG |
| `seon/cluster.clj:151` | `cluster-name?` | B1 | B | B-TYPED |
| `seon/cluster.clj:499` | `mcp-project` | B1 | A | R-HELPER |
| `seon/cluster.clj:521` | `mcp-valf` | B1 | B | B-LOSABLE |
| `seon/cluster.clj:528` | `mcp-valf` | B1 | C | R-MSG |
| `seon/cluster.clj:2237` | `development-source-refresh!` | B1 | B | B-LOSABLE |
| `seon/cluster.clj:2700` | `serve!` | B1 | B | B-RETHROW |
| `seon/cluster.clj:2818` | `commit-fault!` | B1 | B | B-CARRY |
| `seon/cluster.clj:2820` | `commit-fault!` | B1 | A | R-HELPER |
| `seon/cluster.clj:3088` | `arm-agents!` | B1 | B | B-LOSABLE |
| `seon/cluster.clj:3156` | `disarm-agents!` | B1 | B | B-WAIT-HANDLED |
| `seon/cluster.clj:3175` | `disarm-agents!` | B1 | C | R-TIMEOUT-ABSENT |
| `seon/cluster.clj:3217` | `read-advertisement` | B1 | A | R-DISCARD |
| `seon/cluster/agent.clj:651` | `submit-source-in-projection` | B2 | B | B-LOSABLE |
| `seon/cluster/agent.clj:871` | `acquire-context!` | B2 | B | B-RETHROW |
| `seon/cluster/agent.clj:874` | `acquire-context!` | B2 | B | B-SUPPRESSED |
| `seon/cluster/agent.clj:878` | `acquire-context!` | B2 | B | B-SUPPRESSED |
| `seon/cluster/agent.clj:972` | `arm!` | B2 | B | B-LOSABLE |
| `seon/cluster/agent.clj:981` | `await-turn-completion!` | B2 | B | B-WAIT-HANDLED |
| `seon/cluster/agent.clj:995` | `await-turn-completion!` | B2 | B | B-WAIT-HANDLED |
| `seon/cluster/agent.clj:1006` | `await-turn-completion!` | B2 | B | B-WAIT-HANDLED |
| `seon/cluster/agent.clj:1024` | `await-turn-completion!` | B2 | C | R-OFFER |
| `seon/cluster/agent.clj:1146` | `armer-step` | B2 | B | B-LOSABLE |
| `seon/cluster/agent.clj:1224` | `armer-step` | B2 | B | B-LOSABLE |
| `seon/cluster/agent.clj:1225` | `armer-step` | B2 | B | B-LOSABLE |
| `seon/cluster/boot.clj:256` | `start!` | B1 | B | B-RETHROW |
| `seon/cluster/boot.clj:307` | `stop!` | B1 | A | R-DISCARD |
| `seon/cluster/boot.clj:310` | `stop!` | B1 | B | B-RETHROW |
| `seon/cluster/boot.clj:351` | `readable-response` | B1 | A | R-HELPER |
| `seon/cluster/boot.clj:443` | `request!` | B1 | A | R-HELPER |
| `seon/cluster/export.clj:211` | `copy-store!` | D1 | B | B-RETHROW |
| `seon/cluster/export.clj:224` | `copy-store!` | D1 | A | R-MSG |
| `seon/cluster/export.clj:373` | `export!` | D1 | B | B-RETHROW |
| `seon/cluster/process.clj:69` | `live?` | B1 | C | R-PRED |
| `seon/cluster/process.clj:81` | `process-start-instant` | B1 | A | R-DISCARD |
| `seon/cluster/process.clj:116` | `await-subprocess-value` | B1 | B | B-WAIT-HANDLED |
| `seon/cluster/process.clj:156` | `terminate-subprocess!` | B1 | B | B-WAIT-HANDLED |
| `seon/cluster/process.clj:159` | `terminate-subprocess!` | B1 | C | R-DISCARD |
| `seon/cluster/process.clj:160` | `terminate-subprocess!` | B1 | B | B-CARRY |
| `seon/cluster/process.clj:228` | `run-process!` | B1 | B | B-RETHROW |
| `seon/cluster/registry.clj:206` | `branch!` | A2 | B | B-RETHROW |
| `seon/cluster/registry.clj:329` | `retire-branch!` | A2 | B | B-RETHROW |
| `seon/cluster/source.clj:269` | `resolve-population` | B1 | A | R-DISCARD |
| `seon/cluster/source.clj:280` | `retire-scratch!` | B1 | A | R-DISCARD |
| `seon/cluster/source.clj:388` | `record-results!` | B1 | B | B-CARRY |
| `seon/cluster/source.clj:409` | `publication-input-digest!` | B1 | A | R-MSG |
| `seon/cluster/source.clj:574` | `publish!` | B1 | B | B-CARRY |
| `seon/cluster/status.clj:45` | `thread-counts` | B1 | A | R-UNKNOWN-STR |
| `seon/cluster/status.clj:93` | `snapshot` | B1 | A | R-UNKNOWN-STR |
| `seon/cluster/status.clj:163` | `agents` | B1 | A | R-UNKNOWN-STR |
| `seon/cluster/store.clj:326` | `acquire-flock!` | A2 | B | B-TYPED |
| `seon/cluster/store.clj:332` | `acquire-flock!` | A2 | B | B-RETHROW |
| `seon/cluster/store.clj:375` | `complete-store?` | A2 | C | R-PRED |
| `seon/cluster/store.clj:501` | `open-store!` | A2 | B | B-RETHROW |
| `seon/cluster/store.clj:509` | `open-store!` | A2 | B | B-RETHROW |
| `seon/cluster/wake.clj:384` | `deliver!` | B2 | B | B-LOSABLE |
| `seon/cluster/wake.clj:388` | `deliver!` | B2 | A | R-OFFER |
| `seon/cluster/wake.clj:535` | `route!` | B2 | B | B-LOSABLE |
| `seon/cluster/wake.clj:542` | `route!` | B2 | B | B-LOSABLE |
| `seon/cluster/wake.clj:546` | `route!` | B2 | A | R-OFFER |
| `seon/cluster/wake.clj:547` | `route!` | B2 | B | B-DECLARED |
| `seon/cluster/wake.clj:560` | `unlisten!` | B2 | A | R-DISCARD |
| `seon/config.clj:196` | `read-edn-map` | B3 | B | B-RETHROW |
| `seon/db.clj:309` | `resolve-database-value` | A2 | A | R-HELPER |
| `seon/db.clj:594` | `read-result-digest` | A2 | B | B-RETHROW |
| `seon/db.clj:1142` | `read-evidence-current?` | A2 | C | R-DISCARD |
| `seon/db.clj:1707` | `decode-query-field` | A2 | B | B-CARRY |
| `seon/db.clj:1901` | `supplied-database-value` | A2 | A | R-HELPER |
| `seon/db.clj:2012` | `query-call-valid?` | A2 | A | R-DISCARD |
| `seon/db.clj:2028` | `query-guard-message` | A2 | A | R-DISCARD |
| `seon/db.clj:2085` | `q` | A2 | A | R-HELPER |
| `seon/db.clj:2242` | `pull-call` | A2 | A | R-HELPER |
| `seon/db.clj:2459` | `datoms-call` | A2 | A | R-HELPER |
| `seon/db.clj:2518` | `index-page` | A2 | A | R-HELPER |
| `seon/db.clj:2559` | `database-view` | A2 | A | R-HELPER |
| `seon/db.clj:2573` | `database-identity` | A2 | A | R-HELPER |
| `seon/db.clj:2794` | `rejected-value` | A2 | A | R-DISCARD |
| `seon/db.clj:3283` | `write-owned-values-error` | A2 | B | B-RETHROW |
| `seon/db.clj:3752` | `agent-provenance?` | A2 | C | R-PRED |
| `seon/db.clj:3816` | `transact-call` | A2 | B | B-WAIT-HANDLED |
| `seon/db.clj:3818` | `transact-call` | A2 | B | B-RETHROW |
| `seon/db.clj:3853` | `transact-call` | A2 | C | B-DECLARED |
| `seon/edit.clj:121` | `parse-root` | B3 | A | R-DIAG |
| `seon/edit.clj:145` | `location-sexpr` | B3 | C | R-PRED |
| `seon/edit.clj:281` | `lossless-candidate` | B3 | A | R-DIAG |
| `seon/edit/jvm.clj:163` | `edit` | B3 | A | R-HELPER |
| `seon/effect.clj:524` | `dispatch` | B3 | B | B-RETHROW |
| `seon/effect.clj:929` | `request*` | B3 | B | B-TYPED |
| `seon/effect.clj:931` | `request*` | B3 | A | R-DIAG |
| `seon/effect.clj:935` | `request*` | B3 | A | R-DIAG |
| `seon/error.clj:762` | `fact-source` | B3 | A | R-MSG |
| `seon/error.clj:1061` | `refusal-data` | B3 | A | R-DISCARD |
| `seon/eval/drive.clj:67` | `await-fact!` | B3 | B | B-LOSABLE |
| `seon/eval/drive.clj:70` | `await-fact!` | B3 | B | B-LOSABLE |
| `seon/eval/drive.clj:72` | `await-fact!` | B3 | B | B-WAIT-HANDLED |
| `seon/eval/drive.clj:138` | `read-result` | B3 | A | R-DISCARD |
| `seon/eval/drive.clj:411` | `run-sample!` | B3 | B | B-RETHROW |
| `seon/flow.clj:351` | `execute-work!` | B2 | B | B-LOSABLE |
| `seon/flow.clj:386` | `execute-work!` | B2 | B | B-CARRY |
| `seon/flow.clj:393` | `execute-work!` | B2 | B | B-LOSABLE |
| `seon/flow.clj:400` | `execute-work!` | B2 | B | B-CARRY |
| `seon/flow.clj:407` | `execute-work!` | B2 | B | B-LOSABLE |
| `seon/flow.clj:438` | `io-terminal!` | B2 | B | B-LOSABLE |
| `seon/flow.clj:481` | `execute-io-work!` | B2 | B | B-CARRY |
| `seon/flow.clj:490` | `execute-io-work!` | B2 | B | B-CARRY |
| `seon/flow.clj:882` | `submit!!` | B2 | B | B-WAIT-HANDLED |
| `seon/flow.clj:1073` | `fault-committer-step` | B2 | B | B-CARRY |
| `seon/flow.clj:1159` | `report-committer-loss!` | B2 | C | R-DISCARD |
| `seon/flow.clj:1176` | `join-fault-committer-errors!` | B2 | B | B-WAIT-HANDLED |
| `seon/flow.clj:1287` | `join-error-fanout!` | B2 | B | B-WAIT-HANDLED |
| `seon/flow.clj:1305` | `stop-error-fanout!` | B2 | B | B-WAIT-HANDLED |
| `seon/flow.clj:1309` | `stop-error-fanout!` | B2 | B | B-WAIT-HANDLED |
| `seon/fn/analyzer.clj:193` | `manifest-source-roots` | B1 | A | R-DISCARD |
| `seon/fn/analyzer.clj:624` | `stored-arglists` | B1 | A | R-DISCARD |
| `seon/fn/analyzer.clj:674` | `referenced-program-namespaces` | B1 | C | R-DISCARD |
| `seon/fs.clj:255` | `attributes` | B3 | B | B-TYPED |
| `seon/fs.clj:341` | `delete-recursively-impl!` | B3 | B | B-TYPED |
| `seon/fs/jvm.clj:143` | `relative-observation` | B3 | B | B-TYPED |
| `seon/fs/jvm.clj:469` | `read-opened` | B3 | B | B-TYPED |
| `seon/fs/jvm.clj:491` | `read-window` | B3 | A | R-HELPER |
| `seon/fs/jvm.clj:715` | `write` | B3 | B | B-TYPED |
| `seon/fs/jvm.clj:725` | `write` | B3 | B | B-TYPED |
| `seon/fs/jvm.clj:728` | `write` | B3 | A | R-HELPER |
| `seon/fs/jvm.clj:850` | `glob` | B3 | A | R-HELPER |
| `seon/fs/jvm.clj:855` | `glob` | B3 | A | R-HELPER |
| `seon/fs/jvm.clj:895` | `stat` | B3 | A | R-HELPER |
| `seon/instrument.clj:186` | `program-graph-arglists` | A1 | A | R-HELPER |
| `seon/instrument.clj:203` | `jvm-arglists` | A1 | A | R-HELPER |
| `seon/instrument.clj:225` | `diagnostic-arglists` | A1 | A | R-HELPER |
| `seon/instrument.clj:532` | `request-member` | A1 | C | R-DISCARD |
| `seon/instrument.clj:666` | `compiled-wrapper` | A1 | B | B-RETHROW |
| `seon/instrument.clj:871` | `apply!` | A1 | A | R-HELPER |
| `seon/issue.clj:73` | `note-opened` | B3 | C | R-TIMEOUT-ABSENT |
| `seon/issue.clj:182` | `qualified-token` | B3 | C | R-PRED |
| `seon/issue.clj:421` | `index-tx` | B3 | A | R-DISCARD |
| `seon/issue.clj:701` | `generate!` | B3 | A | R-EXDATA |
| `seon/maintenance.clj:72` | `result-entity` | B3 | A | R-MSG |
| `seon/maintenance.clj:463` | `attempt` | B3 | A | R-DIAG |
| `seon/maintenance.clj:876` | `collect-store!` | B3 | B | B-RETHROW |
| `seon/maintenance.clj:880` | `collect-store!` | B3 | B | B-RETHROW |
| `seon/plan.clj:522` | `ready-subjects` | B3 | B | B-CARRY |
| `seon/plan.clj:745` | `done-query-result` | B3 | B | B-WAIT-HANDLED |
| `seon/plan.clj:1337` | `plan!` | B3 | B | B-CARRY |
| `seon/print.cljc:108` | `generated-item-identity` | B2 | C | R-DISCARD |
| `seon/print.cljc:441` | `compare-values` | B2 | B | B-TYPED |
| `seon/print.cljc:1149` | `fit-entry` | B2 | C | R-DISCARD |
| `seon/program.cljc:77` | `edn-round-trip-symbol?` | B1 | C | R-PRED |
| `seon/program.cljc:459` | `` | B1 | C | R-MSG |
| `seon/render.clj:1853` | `walk` | B2 | A | R-HELPER |
| `seon/render/data.clj:38` | `parse-cursor` | B2 | C | R-PRED |
| `seon/render/lint.clj:330` | `reads-as-collection?` | B2 | C | R-PRED |
| `seon/render/lint.clj:331` | `reads-as-collection?` | B2 | C | R-DISCARD |
| `seon/render/ns.clj:138` | `read-edn` | B2 | A | R-DISCARD |
| `seon/render/ns.clj:153` | `schema-form-refs` | B2 | A | R-DISCARD |
| `seon/render/transcript.clj:1186` | `readable-shown` | B2 | A | R-DISCARD |
| `seon/render/transcript.clj:1381` | `render-session` | B2 | B | B-RETHROW |
| `seon/render/transcript.clj:1609` | `outline-everything` | B2 | B | B-RETHROW |
| `seon/render/transcript.clj:1651` | `render-outline` | B2 | B | B-RETHROW |
| `seon/render/value.clj:208` | `counted-size` | B2 | A | R-DISCARD |
| `seon/render/value.clj:240` | `window` | B2 | A | R-MSG |
| `seon/render/value.clj:448` | `value-node` | B2 | C | R-MSG |
| `seon/render/web.clj:167` | `read-query-value` | B2 | C | R-PRED |
| `seon/render/web.clj:734` | `turn-function-result` | B2 | B | B-TYPED |
| `seon/render/web.clj:2272` | `failed-page-result` | B2 | B | B-DECLARED |
| `seon/render/web.clj:2360` | `render-pass` | B2 | A | R-HELPER |
| `seon/render/web.clj:2365` | `render-pass` | B2 | A | R-HELPER |
| `seon/render/web.clj:2837` | `feed` | B2 | B | B-LOSABLE |
| `seon/render/web.clj:2868` | `feed` | B2 | A | R-OFFER |
| `seon/render/web.clj:2870` | `feed` | B2 | B | B-DECLARED |
| `seon/render/web.clj:2932` | `same-origin?` | B2 | C | R-PRED |
| `seon/render/web.clj:3017` | `query-entity` | B2 | A | R-DISCARD |
| `seon/render/web.clj:3034` | `route-namespace` | B2 | A | R-DISCARD |
| `seon/render/web.clj:3413` | `data-response` | B2 | A | R-MSG |
| `seon/render/web.clj:3644` | `start!` | B2 | B | B-TYPED |
| `seon/repl.clj:89` | `shown-value` | B2 | A | R-DISCARD |
| `seon/repl.clj:123` | `def-note` | B2 | A | R-DISCARD |
| `seon/repl.clj:165` | `error-text` | B2 | A | R-DISCARD |
| `seon/repl.clj:461` | `pretty-response` | B2 | A | R-DISCARD |
| `seon/schedule.clj:111` | `valid-cron?` | B3 | C | R-PRED |
| `seon/schedule.clj:121` | `valid-timezone?` | B3 | C | R-PRED |
| `seon/schedule.clj:644` | `invoke-handler` | B3 | A | R-DIAG |
| `seon/schedule.clj:745` | `arm-timer` | B3 | B | B-LOSABLE |
| `seon/schedule.clj:746` | `arm-timer` | B3 | B | B-TYPED |
| `seon/schedule.clj:793` | `schedule-step` | B3 | B | B-LOSABLE |
| `seon/schedule.clj:794` | `schedule-step` | B3 | B | B-LOSABLE |
| `seon/schema.clj:162` | `runtime-predicate` | A1 | A | R-DISCARD |
| `seon/schema.clj:224` | `converged-predicate-var` | A1 | A | R-DISCARD |
| `seon/schema.clj:668` | `canonical-reference-graph` | A1 | C | R-DISCARD |
| `seon/schema.clj:983` | `first-party-source-roots` | A1 | A | R-DISCARD |
| `seon/schema.clj:988` | `first-party-source-roots` | A1 | A | R-DISCARD |
| `seon/schema.clj:1320` | `malli-form?` | A1 | C | R-PRED |
| `seon/schema.clj:1340` | `pull-selector?` | A1 | C | R-PRED |
| `seon/schema.clj:1608` | `register!` | A1 | B | B-RETHROW |
| `seon/schema.clj:3048` | `pulled-attribute-entry` | A1 | B | B-CARRY |
| `seon/schema.clj:3660` | `function-accepts-in?` | A1 | C | R-PRED |
| `seon/schema.clj:3676` | `function-returns-in?` | A1 | C | R-PRED |
| `seon/schema.clj:3691` | `function-accepts-and-returns-in?` | A1 | C | R-PRED |
| `seon/schema/admission.clj:105` | `parse-source` | A1 | C | R-MSG |
| `seon/schema/admission.clj:400` | `compilation-finding` | A1 | C | R-MSG |
| `seon/schema/admission.clj:475` | `admit` | A1 | A | R-MSG |
| `seon/schema/datahike.clj:218` | `compiled-attribute-selection` | A1 | C | R-PRED |
| `seon/schema/datahike.clj:230` | `compiled-attribute-selection` | A1 | C | R-PRED |
| `seon/schema/datahike.clj:251` | `storable-attribute-in?` | A1 | C | R-PRED |
| `seon/schema/datahike.clj:347` | `reader-round-trips?` | A1 | C | R-PRED |
| `seon/schema/datahike.clj:542` | `decode-attribute-value-in` | A1 | A | R-CAUSE |
| `seon/schema/edn.clj:216` | `read-schema-resource` | A1 | B | B-RETHROW |
| `seon/schema/edn.clj:236` | `read-schema-resource` | A1 | B | B-RETHROW |
| `seon/schema/edn.clj:545` | `admit-changed-identities` | A1 | B | B-RETHROW |
| `seon/schema/edn.clj:622` | `admit` | A1 | B | B-RETHROW |
| `seon/schema/internal.cljc:439` | `assert-compilable-schema!` | A1 | B | B-RETHROW |
| `seon/sci/admit.clj:457` | `project` | B2 | B | B-POLICY |
| `seon/sci/admit.clj:473` | `project` | B2 | B | B-POLICY |
| `seon/sci/admit.clj:634` | `restorable-node` | B2 | A | R-DISCARD |
| `seon/sci/admit.clj:751` | `admit-walk` | B2 | B | B-RETHROW |
| `seon/sci/eval.clj:771` | `declaration-source-value` | B2 | A | R-DISCARD |
| `seon/sci/eval.clj:991` | `install-row!` | B2 | A | R-MSG |
| `seon/sci/eval.clj:1299` | `host-namespace!` | B2 | C | R-MSG |
| `seon/sci/eval.clj:2008` | `acquire-program!` | B2 | C | R-MSG |
| `seon/sci/eval.clj:3214` | `evaluate` | B2 | A | R-HELPER |
| `seon/sci/eval.clj:3217` | `evaluate` | B2 | B | B-RETHROW |
| `seon/sci/eval.clj:3267` | `evaluate-for-install` | B2 | A | R-HELPER |
| `seon/sci/kernel.clj:273` | `own-arm` | B2 | B | B-RETHROW |
| `seon/sci/kernel.clj:658` | `invoke` | B2 | A | R-HELPER |
| `seon/sci/kernel.clj:677` | `invoke` | B2 | A | R-DIAG |
| `seon/sci/reader.cljc:151` | `require-bindings` | B2 | A | R-DISCARD |
| `seon/sci/reader.cljc:573` | `read-events` | B2 | B | B-RETHROW |
| `seon/sci/reader.cljc:868` | `recovering-events` | B2 | B | B-CARRY |
| `seon/sci/reader.cljc:933` | `read` | B2 | B | B-CARRY |
| `seon/shell/jvm.clj:102` | `virtual-task` | B3 | B | B-CARRY |
| `seon/shell/jvm.clj:290` | `terminate-tree!` | B3 | B | B-WAIT-HANDLED |
| `seon/shell/jvm.clj:312` | `await-exit` | B3 | B | B-WAIT-HANDLED |
| `seon/shell/jvm.clj:314` | `await-exit` | B3 | B | B-TYPED |
| `seon/shell/jvm.clj:329` | `output-descriptor` | B3 | B | B-TYPED |
| `seon/shell/jvm.clj:405` | `execute` | B3 | A | R-DISCARD |
| `seon/shell/jvm.clj:425` | `execute` | B3 | B | B-RETHROW |
| `seon/shell/jvm.clj:445` | `run` | B3 | B | B-RETHROW |
| `seon/shell/jvm.clj:447` | `run` | B3 | A | R-DIAG |
| `seon/test.clj:93` | `changed-since-green` | B4 | A | R-UNKNOWN-STR |
| `seon/test.clj:155` | `bounded-result` | B4 | B | B-TYPED |
| `seon/test.clj:161` | `bounded-result` | B4 | A | R-MSG |
| `seon/test.clj:793` | `select` | B4 | B | B-RETHROW |
| `seon/test.clj:1189` | `resolve-test` | B4 | A | R-MSG |
| `seon/test.clj:1192` | `resolve-test` | B4 | A | R-MSG |
| `seon/test/accretion.clj:39` | `generatable?` | B4 | C | R-PRED |
| `seon/test/accretion.clj:51` | `schema-row` | B4 | C | R-PRED |
| `seon/test/accretion.clj:179` | `auto-check` | B4 | B | B-CARRY |
| `seon/test/arm.clj:33` | `load-declared-predicate-owners!` | B4 | A | R-DISCARD |
| `seon/test/arm.clj:72` | `declared-namespace` | B4 | A | R-UNKNOWN-STR |
| `seon/test/bounds.clj:39` | `silence-seconds` | B4 | C | R-DISCARD |
| `seon/test/cache.clj:58` | `input-paths` | B4 | B | B-WAIT-HANDLED |
| `seon/test/cache.clj:230` | `gitlink-digests` | B4 | B | B-WAIT-HANDLED |
| `seon/test/cache.clj:361` | `child!` | B4 | B | B-WAIT-HANDLED |
| `seon/test/cache.clj:591` | `newest-base` | B4 | C | R-TIMEOUT-ABSENT |
| `seon/test/fast.clj:114` | `-main` | B4 | B | B-CARRY |
| `seon/test/runner.clj:483` | `liveness-diagnostic` | B4 | A | R-UNKNOWN-STR |
| `seon/test/runner.clj:549` | `persist-virtual-thread-dumps!` | B4 | A | R-UNKNOWN-STR |
| `seon/test/runner.clj:587` | `fire-liveness-backstop!` | B4 | A | R-UNKNOWN-STR |
| `seon/test/runner.clj:1407` | `sci-base-namespace-sizes` | B4 | A | R-UNKNOWN-STR |
| `seon/test/runner.clj:1688` | `run-task!` | B4 | A | R-MSG |
| `seon/test/runner.clj:1741` | `load-declared-predicate-owners!` | B4 | A | R-DISCARD |
| `seon/test/runner.clj:1780` | `declared-namespace` | B4 | A | R-UNKNOWN-STR |
| `seon/test/runner.clj:1888` | `reassert-contracts!` | B4 | B | B-RETHROW |
| `seon/test/runner.clj:1942` | `bounded-worker-task!` | B4 | B | B-WAIT-HANDLED |
| `seon/test/runner.clj:1946` | `bounded-worker-task!` | B4 | B | B-CARRY |
| `seon/test/runner.clj:1948` | `bounded-worker-task!` | B4 | B | B-TYPED |
| `seon/test/runner.clj:2335` | `reach-entries` | B4 | A | R-MSG |
| `seon/test/runner.clj:2420` | `program-digest` | B4 | A | R-MSG |
| `seon/test/runner.clj:3089` | `reusable-result` | B4 | A | R-MSG |
| `seon/test/runner.clj:3175` | `start-cluster!` | B4 | B | B-RETHROW |
| `seon/test/runner.clj:3197` | `completion-reach-digests` | B4 | A | R-UNKNOWN-STR |
| `seon/test/runner.clj:3290` | `staged-completion` | B4 | A | R-MSG |
| `seon/test/runner.clj:3349` | `persistent-results-form` | B4 | A | R-MSG |
| `seon/test/runner.clj:3402` | `record-snapshot!` | B4 | B | B-CARRY |
| `seon/test/runner.clj:3482` | `run-results` | B4 | B | B-CARRY |
| `seon/test/runner.clj:3519` | `latest-results` | B4 | B | B-CARRY |
| `seon/test/runner.clj:3544` | `recorded-run!` | B4 | B | B-CARRY |
| `seon/test/runner.clj:3566` | `recording-failure` | B4 | A | R-MSG |
| `seon/test/runner.clj:3702` | `read-exchange-reply!` | B4 | A | R-DISCARD |
| `seon/test/runner.clj:3835` | `worker-exchange!` | B4 | B | B-TYPED |
| `seon/test/runner.clj:3842` | `worker-exchange!` | B4 | A | R-MSG |
| `seon/test/runner.clj:3889` | `start-worker!` | B4 | B | B-RETHROW |
| `seon/test/runner.clj:3987` | `await-process-tree-exit` | B4 | B | B-WAIT-HANDLED |
| `seon/test/runner.clj:3990` | `await-process-tree-exit` | B4 | B | B-TYPED |
| `seon/test/runner.clj:4203` | `run-task-pool!` | B4 | A | R-UNKNOWN-STR |
| `seon/test/runner.clj:4222` | `run-task-pool!` | B4 | B | B-CARRY |
| `seon/test/runner.clj:4259` | `run-task-pool!` | B4 | A | R-UNKNOWN-STR |
| `seon/test/runner.clj:4285` | `run-task-pool!` | B4 | A | R-UNKNOWN-STR |
| `seon/test/runner.clj:4295` | `run-task-pool!` | B4 | A | R-DISCARD |
| `seon/test/runner.clj:4460` | `confirm-task-results!` | B4 | B | B-RETHROW |
| `seon/test/runner.clj:4463` | `confirm-task-results!` | B4 | A | R-HELPER |
| `seon/test/runner.clj:4645` | `finish-run!` | B4 | B | B-CARRY |
| `seon/test/runner.clj:4684` | `run-parallel-stage!` | B4 | B | B-RETHROW |
| `seon/test/runner.clj:4920` | `run-coordinator!` | B4 | C | R-DISCARD |
| `seon/test/selection.clj:132` | `source-forms` | B4 | A | R-DISCARD |
| `seon/test/selection.clj:175` | `changed-working-paths` | B4 | B | B-WAIT-HANDLED |
| `seon/turn.clj:1733` | `receipt-value` | B2 | A | R-DISCARD |
| `seon/turn.clj:3092` | `repaired-span` | B2 | C | R-PRED |
| `seon/turn.clj:3445` | `phase` | B2 | A | R-MSG |
| `seon/turn.clj:4371` | `call-turn` | B2 | B | B-LOSABLE |
| `seon/turn.clj:5297` | `await-turn-permit!` | B2 | B | B-WAIT-HANDLED |
| `seon/turn.clj:5393` | `offer-write-refusal-fault!` | B2 | A | R-LOG |
| `seon/turn.clj:5416` | `offer-turn-backstop-fault!` | B2 | A | R-LOG |
| `seon/turn.clj:5448` | `arm-turn-completion-backstop!` | B2 | B | B-LOSABLE |
| `seon/turn.clj:5460` | `arm-turn-completion-backstop!` | B2 | B | B-WAIT-HANDLED |
| `seon/turn.clj:5508` | `step` | B2 | B | B-LOSABLE |
| `seon/turn.clj:5572` | `step` | B2 | B | B-LOSABLE |
| `seon/turn.clj:5588` | `step` | B2 | B | B-LOSABLE |
| `seon/turn.clj:5611` | `step` | B2 | B | B-LOSABLE |
| `seon/turn.clj:5613` | `step` | B2 | B | B-LOSABLE |
| `seon/web/jvm.clj:60` | `admitted-uri` | B3 | B | B-DECLARED |
| `seon/web/jvm.clj:180` | `decoded-text` | B3 | B | B-TYPED |
| `seon/web/jvm.clj:181` | `decoded-text` | B3 | B | B-TYPED |
| `seon/web/jvm.clj:182` | `decoded-text` | B3 | B | B-TYPED |
| `seon/web/jvm.clj:365` | `fetch` | B3 | A | R-UNKNOWN-STR |
| `seon/web/jvm.clj:370` | `fetch` | B3 | B | B-TYPED |
| `seon/web/jvm.clj:374` | `fetch` | B3 | B | B-TYPED |
| `seon/web/jvm.clj:379` | `fetch` | B3 | A | R-HELPER |
| `seon/web/jvm.clj:445` | `search` | B3 | A | R-DISCARD |
| `seon/web/jvm.clj:471` | `search` | B3 | B | B-TYPED |
| `seon/web/jvm.clj:475` | `search` | B3 | B | B-TYPED |
| `seon/web/jvm.clj:480` | `search` | B3 | A | R-HELPER |
