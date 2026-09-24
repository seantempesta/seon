---
type: research
status: current
created: 2026-09-24
scope: Production paths in src/ where nil, false or an empty collection stands for failure or unknown rather than absence; ranked by severity, grouped by owning file.
---

# Ambiguous-nil producers census (2026-09-24)

Read-only census. No source edits. Owner (verbatim): "Making sure producers do not
return nil to mean ambivalent things is more my concern. We should be very clear about
what we mean from the start rather than hacking the contracts and having ambiguous nils."
Authorities: CLAUDE.md "Make failure local and loud" ("Never interpret silence, missing
subjects or stale observations as health"), "Keep the whole cause" ("missing evidence is
typed unknown, never nil/default/message-only success"), "Declare errors as flat data".
Companion: `swallowed-errors-census-2026-09-23.md`. That census counts catch clauses.
This one asks what the resulting nil *means* to the reader.

**Broken first.** Every MCP `eval_clj` against `default` returned
`seon.dev.mcp/branch-errors` "This JVM's seon.error has no occurred-since (RESET NEEDED)."
The running JVM predates a `seon.error` change. **RESET NEEDED** (root). It did not block
these read-only queries.

**Method (re-run at HEAD, not trusted).** The working tree was HEAD `fa13c41a8` plus other
lanes' edits, re-walked at `2614d0353`. Line numbers are working-tree numbers and can move.
Scripts are in the session scratchpad, copied from `tmp/swallowed-errors-census/`:
- `walk.clj`, unchanged except the output path. It now finds 379 sites (the census found
  417 including script/bin): 236 catch sites in `src/`, 92 `:discard`, 22 `:message-only`.
- `branches.clj`, new. A rewrite-clj walk over every `if/if-not/when/when-not/and/cond`
  whose test reads an error marker (`:seon.db/invalid-read`, `:seon.schema/expected-value`,
  `:seon.error/at`). It reports what the *error branch* yields. 360 sites:
  163 propagate the subject, 40 throw or `reduced`, 43 wrap the subject, 64 compute an
  expression, and **50 yield a literal, an implicit nil or another symbol**. All 50 were
  read by hand. Most are guards whose next `cond` returns the error. The rest are listed below.
- Contracts: a JVM `eval_clj` over default's rows (`:seon.fn/spec`, `:seon.fn/file`), with
  callers found through a reverse `:seon.fn/calls` lookup. Of 1990 contracted functions,
  **302 in `src/` declare a nilable return** (`:nil`, `[:maybe …]`, `[:= nil]`).
  **22** of those read the database (`seon.db/q|pull|pull-many|datoms|entity`) and
  declare no error arm (listed under class 6).

Classes: **1** catch → nil/false/default. **2** a declared error value dropped by a guard
(`when`, `keep`, `number?`). **3** a refusal passed on as if it were absent, or as a
different value. **4** one nil (or `[]`) for both "no such row" and "could not read".
**5** `(or (:k x) default)` where `x` can be an error map, so the keyword lookup is nil
and the default wins. **6** a nilable contract whose body reaches nil or an error from a
failure branch. Severity: **S1** hides a failure from status, policy, a test or a
destructive act. **S2** an agent or page sees "nothing" for "unknown".
**S3** cosmetic or local.

## Findings, grouped by owning file (one lane per file)

### `src/seon/cluster.clj`

- **S1 · class 5 · `arm-agents!` :3521** `:seon.flow/read-core-error-mode` is
  `(or (:seon.config/on-core-error (config/effective …)) :record)`. `config/effective`
  returns `:seon.config/error | :seon.db/invalid-read-error | validation-refusal`
  (`config.clj:752`). A config read failure, or an absent dial, becomes **`:record`**, the
  permissive mode. `seon.fault/policy` (`fault.clj:135`) says the opposite: "an absent or
  unknown dial … panics". `flow.clj:1108` calls this reader for every fault. **Should
  be:** pass the value unchanged, so that `fault/policy` decides (nil → `:panic`), and
  carry the read error into the fault fact. There is no new schema:
  `:seon.config/error` already exists. Reader: `seon.flow` fault committer.
- **S2 · class 5 · `disarm-agents!` :3636** `backstop-ms` is a keyword over
  `config/effective`. On a read error the stop bound is nil. Should be: refuse the stop
  with the config error, or use the handle's carried bound. Reader: the join bound below it.
- **S3 · class 1+4 · `read-advertisement` :3695/:3719** `(catch Throwable _ nil)`. One
  nil means absent, unparseable, schema-invalid, stale pid, and any throw. It has **zero
  callers** (none in `src/`, `script/` or `bin/`; DB reverse lookup 0). **Delete it.**

### `src/seon/cluster/process.clj`

- **S1 · class 1+4 · `process-start-instant` :76/:85** `(catch Throwable _ nil)`. One nil
  means "not alive", "no start instant" and "the OS query failed". It is worse through
  `matching-process-handle` (:95): a record whose start-instant was recorded nil by
  `subprocess-identity` (:125) equals a failed lookup, `(= nil nil)`. So a **reused pid
  matches and is signalled**. Callers: `matching-process-handle`,
  `process-identity-alive?`, `subprocess-identity`, `script/seon/dev/state.clj:7-8`.
  **Should be:** catch only the declared `SecurityException`/`UnsupportedOperationException`
  into a typed unknown. An unknown start instant never matches. There is no process-identity
  error schema yet (owner: `seon.cluster.process`).

### `src/seon/call_preparation.clj` (the owner's example)

- **S1 · class 3 · `hook` :1485/:1489** An invalid database read, or an invalid current
  snapshot, returns `arguments` unchanged. That is the same path as "not a Seon ctx". The
  call then runs **without its supplied defaults**, and the target reports the failure as
  its own contract violation. That is exactly what `coherent-supplier`'s docstring (:217)
  says must never happen. **Should be:** `(reduced <the invalid-read value>)`.
  `:seon.db/invalid-read-error` exists. Callers: `seon.sci.eval/build-base-ctx`,
  `seon.sci.kernel/invoke`.
- **S1 · class 2 · `plan-for` :977** `(when (number? contract-t) …)`: a
  `:seon.db/error-result` from `contract-transaction` (:707, which declares it) becomes
  nil, "not a contracted function". `plan` (:1132) caches nothing and returns nil. `hook`
  then prepares with no plan. The `arities`/`positional`/`entries` `db/q` results below it
  are not checked either. **Should be:** return the error; `plan` and `hook` propagate it.
  Callers: `plan` (then `hook`).
- **S2 · class 3 · `newest-row-transaction` :305** An unreadable `db/history` silently
  falls back to the current database. The cache-coherence basis then ignores retractions.
  Should be: return the invalid read, as the `ranges` line (:309) already does. Caller:
  `derive-snapshot`.
- Checked, keep: `coherent-supplier` nil = "coherent" (a checker's nil means no finding,
  declared in its `[:or :nil …error]` return). `derive-snapshot` :474 returns a typed
  `incoherent` value. But see the census's R-MSG: it keeps only `ex-message`.

### `src/seon/cluster/agent.clj`

- **S1 · class 4 · `assigned-to` :450** An invalid read gives `[]`, the same as "no agent
  assigned". `render.web/ensure-namespace-owner!` (`web.clj:3159`) reads
  `(first …)` = nil and **creates an owner agent** on a read failure. That is a write
  driven by a failure. The page paths at `web.clj:3416/:3421` show "no owner".
  **Should be:** return the invalid read (`:seon.db/invalid-read-error`); callers
  already propagate that shape. 4 callers.
- S3 · contract gap: `steward-of` :453 declares `[:maybe :seon.agent/id]` over a `db/q`
  that can return an invalid read. When armed, a read failure becomes an output-contract
  refusal instead of the declared error. Add the error arm.

### `src/seon/cluster/prompt.clj`

- **S1 · class 4+5 · `calibration-for` :100** An invalid read gives `[]`, which gives
  `fallback-calibration`. The budget then trusts an assumed ratio: "THE MEASUREMENT THE
  BUDGET TRUSTS" (docstring :103). Should be: propagate the invalid read, and have
  `model-calibration` mark the budget unknown. Callers: `model-calibration` (then
  `agent-calibration`, `prompt`).

### `src/seon/cluster/message.clj`

- **S2 · class 4 · `agent-reference-id` :260** `(when-not invalid result)` gives nil,
  which means "not an agent reference". Callers: `format-ai`, `render-html`. The message
  renders as if it came from outside. Should be: return the invalid read.
- S3 · contract gap: `trigger` :21 and `sender` :72 declare `[:maybe …]` over a bare
  `db/q`. `sender`'s nil means "came from outside", but a read error arrives as an
  undeclared map. Add the error arm.

### `src/seon/turn.clj`

- **S2 · class 5 · `await-turn-permit!` :5278** and **`write-refusal-bound` :5319**:
  `(or (:k (ai/agent-overlay …)) carried (:k (config/effective …)))`. Both producers
  return declared errors (`ai.clj:348`, `config.clj:756`). An overlay read failure
  silently takes the cluster value. A failed effective read can leave the timeout nil.
  Should be: refuse with the overlay's or effective's error.
  `:seon.db/invalid-read-error` and `:seon.config/error` exist.
- **S2 · class 5 · `gate-function-install` :3240** `:seon.config.test/auto-check-cases`
  is a keyword over `config/effective`. A read failure gives a nil case count into
  `seon.test.accretion` (:168). Should be: refuse the install with the config error.
- **S2 · class 1 · `receipt-value` :1709/:1713** `(catch Throwable _ nil)` over
  `edn/read-string` of shown text. Unreadable shown text reads as "no value", and
  `red-receipt?` (:2374) then treats the receipt as not unbound, which is **green**.
  Should be: narrow to the EDN `RuntimeException` and give a typed unreadable value.
  `red-receipt?` must not read it as green.
- Checked, keep: `call-turn` :4485 records `observed-db` when `prompt-db` is refused, with
  the refusal carried beside it. `opening-deferred?` :2717 propagates `limit` errors.

### `src/seon/render.clj`

- **S2 · class 3+6 · `request-projection` :114** (`[:maybe projection]`): an invalid
  database falls through to the request's or ctx's projection, or to nil. 11 callers
  (`render-call`, `invoke-selected`, `raw-output`, …) cannot tell "no projection
  supplied" from "database unreadable". Should be: return the invalid read.
- **S2 · class 2 · `render-program-evidence` :719** An invalid `pull-many` gives nil
  (`[:or :nil :map]`), which reads as "no evidence". Caller: `call-cache-evidence`.
  Return the read error.
- S3 · `walk` :1830 renders an effective-config error with `pr-str`. That is visible,
  but it is the census's R-UNKNOWN-STR.

### `src/seon/render/transcript.clj`

- **S2 · class 2 · `agent-history-under` :886** `keep` drops runs whose pull is an invalid
  read. The history is silently shorter (agent context and page). Should be: stop at the
  first invalid read.
- **S2 · class 3 · `render-ledger` :2290** An invalid `rows` suppresses problems, then
  `(seq rows)` iterates the **error map's entries as turns**. Should be: render the read
  error as the refusal branch.
- S3 · class 2 · `session-budget` :2079 counts an unreadable opening basis under a nil
  step key. S3 · class 1 · `readable-shown` :1115 `(catch Exception _ nil)` (5 callers;
  the problems finder then misses unreadable shown text).
- Checked, keep: `session-problems` :2124 counts an unreadable refresh as `::unknown 1`,
  a typed unknown (though its cause is lost). `selected-run-identities` :694 carries
  `::selected-run-error`.

### `src/seon/sci/eval.clj`

- **S3 · class 6 · `installation-covers-program-change?` :1203** (`[:or :nil :boolean]`):
  any of six read failures gives nil. Its caller `install-evaluated-rows!` (:1319) reads
  nil like false, meaning "do not advance the acquired snapshot". That direction is safe,
  but the read error disappears. Should be: `[:or :boolean :seon.db/invalid-read-error]`,
  with the caller recording it.
- Checked, keep: `declaration-source-value` :826 compares bytes when the source is not EDN.

### `src/seon/schema.clj`

- **S1 · class 1 · `runtime-predicate` :160/:164** `(catch Throwable _ nil)` around
  `requiring-resolve`. A predicate namespace that **fails to compile** becomes "unresolved
  predicate", and the compile error's cause is gone. 9 callers (`build-projection`,
  `declaration-projection`, `materialize-projection`, `assert-complete-contract!`, …).
  Should be: nil only for an unloadable namespace name (`FileNotFoundException`). Anything
  else propagates. The census tracks it as R-DISCARD.
- **S2 · class 1 · `converged-predicate-var` :226** swallows a `require :reload` failure
  and returns the stale Var. Its docstring admits "a caller that swallows the refusal".
- S2 · class 1 · `first-party-source-roots` :1089/:1095/:1100: an unreadable
  `clojure.basis` gives an empty root set. Every frame is then classified non-first-party,
  which blinds `stack-failing-function`. Should be: a typed unknown.
- Checked, keep: `malli-form?` :1430 is declared-typed (`RuntimeException`); the fix to
  `query-call-valid?` (census A, `db.clj:2243`) has landed and it no longer answers true on
  failure.

### `src/seon/render/ns.clj`

- **S2 · class 1 · `read-edn` :136** `(catch Throwable _ nil)`: a malformed stored EDN
  string renders the same as a blank one. 4 callers (`signature-form`,
  `normalize-schema-form`, `referenced-schema-closure`, `stored-arglists`).
  `schema-form-refs` :151 has the same shape (partial reference set). Should be: the
  file's own `read-refusal?` value, which is already used 17 times in this file.

### `src/seon/shell/jvm.clj`

- **S2 · class 1 · `execute` :368** `(catch Throwable _ nil)` around the stdin task's
  result: a failed stdin write vanishes from the shell receipt. Should be: carry the
  throwable as the `::stdin` evidence (census R-DIAG shape).
- Checked, keep: `await-exit` :273 (timeout → false, then verified force);
  `output-descriptor` :290 (non-UTF-8 → no decoded text; bytes and digest retained).

### `src/seon/ai.clj`

- **S2 · class 1 · `stream-fold` :868** `(try (sink snapshot) (catch Throwable _ nil))`:
  a failing live-display sink is silently dropped for the whole stream. Should be: record
  the sink failure once on the snapshot. Checked, keep: `interruptible-lines` :1156 carries
  the throwable in its atom.

### Smaller (S3, one line each)

- `bootstrap.clj:130` `situation` and `repl.clj:68` `frame`: an overlay error falls
  through to `db/q`, whose invalid-read map then becomes the "limit" (class 5).
- `fn/analyzer.clj:665` `stored-arglists`: malformed means "no arglists" (class 1).
  `:715` is justified in its comment (C).
- `issue.clj:423` `index-tx`: a malformed `:created` date silently takes `opened` (class 1).
- `eval/drive.clj:137` `::unreadable`: a keyword sentinel, not a declared error (class 1).
- `bootstrap.clj:94` `render-help-html`: a parse failure is length 0 (class 1).
- Contract gaps (class 6, the rest of the 22): `ai/model-row` (`some->` over a pull error
  into `ordinary-model-row`), `bootstrap/task-message-id`, `sci.eval/agent-namespace`,
  `oversight/current-run-id`, `oversight/cluster-name`, `eval.drive/terminal-state`,
  `problems/form-problem`, `error/refusal-text`, `turn/render-ai`,
  `cluster/ensure-cluster-entity!`, `message/render-html`. Each declares `[:maybe x]` over
  a read that can refuse. Fix: add the producer's declared error arm, never widen to
  `:any`.

## Legitimate "nothing" returns checked (the rule applied both ways)

- Absent path, typed JDK case: `fs.clj:255` `attributes`, `:341`,
  `fs/jvm.clj:143` and `:725` (`NoSuchFileException` → nil/absent).
- Total predicates over a declared exception: `schedule/valid-cron?` :116,
  `valid-timezone?` :130, `web/same-origin?` :3002, `cluster/cluster-name?` :152,
  `program/edn-round-trip-symbol?` :80, `schema.datahike/reader-round-trips?` :357,
  `blob/store-faithful-edn` :52 (nil = not faithful, declared `[:maybe :string]`).
- Parsers whose nil means "not that token": `issue/qualified-token` :184,
  `render.data/parse-cursor` :41 (documented default cursor),
  `web.jvm/decoded-text` :180-182.
- A checker's nil means "no finding": `call-preparation/coherent-supplier`,
  `error.refusal/refusal` :142 (nil = no classified refusal in the chain).
- Guards whose next branch returns the error (≈30 of the 50 `branches.clj` hits):
  `bootstrap:601`, `cluster:419`, `cluster:2884`, `cluster.source:464`, `db:3814`,
  `plan:372`, `program.cljc:534/:603`, `test.clj:1670/:1674`, `render.transcript:2014`.
- Fail-safe direction: `fault.clj:187`, where a thrown config read gives nil mode and
  `policy` gives `:panic` (the cause is still dropped; census R-DISCARD).
  `web.jvm/search` :429 gives nil, then the declared `:my.web/unparseable-response`.
- Displays with a declared fallback: `repl/shown-value` :89, `pretty-response` :455
  (show the exact text), `render.web/graph-model` :932 and `acquire-debug-data` :1703
  (the observation's refusal is rendered by the caller).
- `test.clj:99`, `cluster/status.clj:44/:92/:162` return a typed `unknown`. They are
  not nil, but they are message-only (census R-MSG).

## Rule for authors (for `.agents/skills/data-oriented-clojure/SKILL.md`)

**Nil, `false` and empty collections mean only "nothing exists". When a producer cannot
read, parse, resolve or decide, it returns its declared error or typed unknown. Every
reader passes that value on or refuses. None of them turns it into nil, a default, or the
"not applicable" branch.**

## Counts per class (failure-meaning sites, this census)

| class | count | of which S1 |
|---|---|---|
| 1 catch → nil/false/default | 15 | 2 (`process-start-instant`, `runtime-predicate`) |
| 2 declared error dropped by a guard | 6 | 1 (`plan-for`) |
| 3 refusal passed on as absent or other | 5 | 1 (`hook`) |
| 4 one nil/`[]` for none and unreadable | 4 (+2 shared with 1) | 2 (`assigned-to`, `calibration-for`) |
| 5 `(or (:k err) default)` | 8 | 1 (`arm-agents!` core-error mode) |
| 6 nilable contract reaching failure | 302 nilable in `src/`; 22 read the DB without an error arm | 0 on their own |

## Top 10 producers to fix first

1. `seon.cluster/arm-agents!` :3521: config failure or absent dial → `:record`. It contradicts `fault/policy`.
2. `seon.call-preparation/hook` :1485/:1489: invalid read → arguments run unprepared.
3. `seon.call-preparation/plan-for` :977 (+ `plan`): read error → "not contracted".
4. `seon.cluster.process/process-start-instant` :85: failure = dead, and nil = nil matches reused pids.
5. `seon.cluster.agent/assigned-to` :450: read failure → `[]` → `ensure-namespace-owner!` creates an agent.
6. `seon.schema/runtime-predicate` :164 (+ `converged-predicate-var` :226): compile error → "unresolved".
7. `seon.cluster.prompt/calibration-for` :100: read failure → assumed calibration trusted by the budget.
8. `seon.turn/receipt-value` :1713: unreadable shown text → `red-receipt?` false (green).
9. `seon.render/request-projection` :114: invalid DB → another projection or nil, for 11 callers.
10. `seon.turn/await-turn-permit!` :5278 and `write-refusal-bound` :5319 (+ `gate-function-install` :3240): overlay or effective errors taken as defaults.

Also delete `seon.cluster/read-advertisement` (no callers).
