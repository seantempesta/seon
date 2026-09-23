---
type: plan
status: implementation specification; proof gates stated below
created: 2026-09-21
tags: [agent-platform, lane-b3, errors, tasks, config, effects, env]
---

# Lane B3 — one error model, one task family, dials that mean something

Owned: `src/seon/error.clj`, `src/seon/error/refusal.clj`, `src/seon/issue.clj`,
`src/seon/issue/*`, `src/my/issue.clj`, `src/seon/plan.clj`, `src/seon/config.clj`,
`src/seon/effect.clj`, `src/seon/search.clj`, `src/seon/env.clj`,
`src/seon/bootstrap.clj`, `config/default.edn`, the 42 schema resources matching
`seon.error*`, `seon.issue*`, `seon.plan*`, `my.plan*`, `seon.effect*`,
`seon.config*`, `seon.env*`, `seon.search*` (2,409 lines), and the
`:seon.error/kind` / `:seon.error/class` sites in every file no other lane holds.
Source anchors and historical observations refer to HEAD `209a6652a`. Recheck current ownership before implementation; this specification claims no new live proof. B3 also owns surviving `context.clj`, `problems.clj`, `eval.clj`, `background.clj`, `shell/jvm.clj` and `note.clj` code omitted from the earlier ownership table, coordinating B2's terminal-state move and B4's test callers. Their baselines must be counted separately before adding them to §8; no implicit deletion or ownership gap is permitted.

## 0. For the owner: what was dumb, and the simpler way

**Errors.** An error is built by copying itself. The one constructor demands
seven `diagnostic-*` keys (`error/refusal.clj:37-74`) and 275 sites supply them;
in the two specimens read the 2026-09-21 evidence collection the "cause" was a verbatim copy of the
message beside it, and the "evidence" a copy of the frame beside it
(`effect.clj:677-694` copies its `:seon.error/offending` into `diagnostic-cause`).
The set of error schemas a boundary may return is copied by hand into 14 places
(695 lines, six different member sets — pack B3 §4b) while the registry-wide
union derivation at `error.clj:1921` (to be renamed) computes a complete set
from the registry — which the ruling retires: every arity declares its own
explicit error union. 799 sites still write
a `:seon.error/kind` the live schema does not store (pack §3a). When a fault is
recorded the recorder prints the map to EDN and stores it beside datoms that say
the same thing, then explodes a Malli path into component entities and keeps 33
predicate/generator definitions (`error.clj:2314-2658`) to prove the explosion
reassembles. On every occurrence it pulls and sums ALL occurrences of the root
(`error.clj:1538-1552`) to learn whether this is the first. Rendering lives in the
recorder, so `seon.db` reaches `seon.error` through five lazy delays
(`db.clj:48-60`) and `seon.error` reaches `seon.sci.eval` through one more
(`error.clj:37-41`).

**The simpler way, as data flow.** An error is one flat map: when, where, who,
an optional message, the members its own declared schema names. The function
that returns it declares which error schemas it can return, and the armed
wrapper checks the returned map against exactly those (A1's seam) — through the acquired output validator. A returned map accepted by a declared success branch remains data; there is no separate base-shape classifier. Callers branch on the explicit union or a declared member. Recording is a different question:
identity (D13) receives the one schema name supplied explicitly by the producer,
carried through normalization into the writer and hashed. Historical observations
without that evidence require RESET, not migration. The writer reads the
one occurrence it increments (one lookup), asks whether the root already has an
occurrence (one seek), and on a repeat hands the signature to the task writer,
whose identity upsert makes the call idempotent. When an evaluation owns a result, its offending value is the value renderer's shown text plus that result reference; outside an evaluation, availability is explicit; nothing the schema does not declare is stored.
Rendering leaves the recorder; each delay retires with its actual caller and require-cycle proof.

**Tasks.** Two lifecycles for one noun: `seon.issue` (1,510 lines; notes read by
a hand-written character scanner, 1,882 issue rows on `default` from note
paths, none with a detector, none assigned — probe P3) and `seon.plan` (1,887
lines; a per-agent component tree with its own reconcile). Done is decided by
re-running a whole-program detector to check one subject (`issue.clj:1042-1064`).

**The simpler way.** `seon.task` is one entity: linked facts plus an optional
assigned agent. One transaction function turns a trigger into a task identity
(detector + subject) and either does nothing new (the task exists) or creates
it; one transaction function starts it (task + agent + first turn atomically,
refusing a task with no way to be done); done is a subject-local query written
by settlement only. A plan step is a task with a parent. Notes never enter `src/`.

**Dials.** 96 config dials live (probe P1); 23 are tuned constants nobody derives
from an event. A bound belongs to the seam that admits the work; a timer beside
an event the system already publishes is a guess. Each such dial is replaced by
its event AND a bound at that seam, or it stays until that proof exists.

## 1. Goal and the numbers that prove it

| Measure | Before (measured at HEAD / on `default`) | After (target) | Form |
|---|---|---|---|
| owned src lines | 10,972 (`error` 2,790 · `issue`+`plan`+`my` 4,169 · `config` 926 · `effect` 1,053 · `search` 571 · `env` 531 · `bootstrap` 932) | ≈ 2,850 retained in the repository (§8: 2,500 in owned files + 300 moved to `seon.render.error` + 45 moved to `admission`) | `wc -l` |
| owned schema lines | 2,409 in 42 files | ≈ 1,000 (provisional until §2a's stored shape settles) | `wc -l resources/seon/schemas/{seon.error*,seon.issue*,seon.plan*,my.plan*,seon.effect*,seon.config*,seon.env*,seon.search*}.edn` |
| `:seon.error/kind` lines | 799 (src 306 · test 443 · script 46 · bin 4) | 0 | `rg -c ':seon.error/kind' src test script bin` |
| `:seon.error/class true` lines in resources | 172 | 0 | `rg -c ':seon.error/class true' resources` |
| `:seon.error/diagnostic-` lines in src | 2,431 | 0 | `rg -c ':seon.error/diagnostic-' src` |
| hand-copied unions | 14 copies / 6 files / 695 lines | 0 | pack §4b table |
| bytes per recorded fault, specimen entity 41647 | reports `data-size` 55,201; stores 1,062 UTF-8 bytes of `data-edn`; `capped? true`; the occurrence blob entity carries a digest and NO size datom, so blob bytes are unmeasured (probe P2) | declared datoms + one shown text ≤ the agent profile; duplicate EDN removed; complete-rendering blob durability remains an owner decision (§2a) | probe §4.1 |
| recorded faults on `default` | 2 roots, 2 occurrences (counts 4 and 1) | unchanged semantics, smaller rows | probe P1 |
| issue/task rows on `default` | 1,882 (`:seon.issue/agent` 0, `:seon.issue/detector` 0 — probe P1 returned nil for both counts) | only tasks with a live subject after the owner's note audit | probe §4.2 |
| done check for one task | `detector-rows` over the whole program (`issue.clj:629`, called from `done?` `:1058`) | one identity seek + the subject's reaching tests | probe §4.3 |
| occurrence write | pulls every occurrence of the root (`error.clj:1544-1547`) | one occurrence lookup + one existence seek | probe §4.4 |
| dials | 96 (`seon.config/dial-attributes`, live) | measure remaining declarations after each §2c event/bound proof; 73 is a conditional target | `(count (seon.config/dial-attributes (seon.db/carried-projection db)))` |

Historical probes from 2026-09-21, all `eval_clj` mode `jvm`, cluster `default`, `read_only true`:

- **P1** (5 ms): `{:reported 55201 :edn-bytes 1062 :capped? true :root-blob-digest? false :occurrence-count 0 :roots 2 :occurrences-total 2 :issues-with-agent nil :issues-with-detector nil :effect-rows nil :dials 96}` — the form pulled entity 41647's `data-size`/`data-edn`/`capped?`, counted `:seon.error/signature`, `:seon.error.occurrence/id`, `:seon.issue/agent`, `:seon.issue/detector`, `:seon.effect/id` roots with `(count ?e) .`, and counted `dial-attributes`.
- **P2** (2 ms): the two occurrence components on `default` pulled with `{:seon.error.occurrence/data-blob [*]}` → counts 4 and 1, each blob entity `{:seon.error.occurrence/blob-digest "<64 hex>"}` only — no `:seon.blob/size` on it; the blob was not read and the blob's bytes are reported as unmeasured, never estimated.
- **P3** is the historical census below (`:issues 1882 :kind-installed? false :largest-error 676197`).

Exact historical forms, 2026-09-21, `eval_clj` JVM mode, `read_only true`, cluster `default`, timeout 10,000 ms; these are evidence from that basis, not fresh observations:

Probe 1, MCP `ret` 3 ms:

```clojure
(let [db (seon.db/db (seon.operator/connection "default"))] {:basis (seon.db/basis-t db) :projection? (some? (seon.db/carried-projection db)) :kind-installed? (contains? (:schema db) :seon.error/kind) :largest-error (seon.db/q '[:find (max ?n) . :where [_ :seon.error/data-size ?n]] db) :issues (seon.db/q '[:find (count ?e) . :where [?e :seon.issue/id _]] db)})
;; => {:basis 536870949, :issues 1882, :kind-installed? false,
;;     :largest-error 676197, :projection? true}
```

Probe 2, MCP `ret` 4 ms:

```clojure
(let [db (seon.db/db (seon.operator/connection "default")) ids (seon.db/q '[:find [?e ...] :where [?e :seon.error/data-size 55201]] db)] (mapv (fn [e] (let [r (seon.db/pull db [:seon.error/data-size :seon.error/data-edn :seon.error/capped? :seon.error/data-blob :seon.error.occurrence/data-blob] e)] {:entity e :reported-size (:seon.error/data-size r) :stored-edn-bytes (when-let [s (:seon.error/data-edn r)] (alength (.getBytes s "UTF-8"))) :capped? (:seon.error/capped? r) :blob? (boolean (or (:seon.error/data-blob r) (:seon.error.occurrence/data-blob r)))})) (take 3 ids)))
;; => [{:blob? true, :capped? true, :entity 41647,
;;      :reported-size 55201, :stored-edn-bytes 1062}]
```

## 2. The data flow

### 2a. One error value

**Durability is already ruled; the gate dissolves.** Owner ruling 2026-09-23 05:50 (goals note §3, "The durable form of any result is the value printer's rendering"): blob = the complete rendering, entity text = the capped shown text, live object only as `result/e<id>`; no faithful-EDN attempt, no encoder extension. Applied to a recorded error: the occurrence keeps its blob reference to the complete value-renderer rendering (the existing `blob/put!` of one rendering, kept), the capped shown text, and the `result/e<id>` handle when an evaluation owns it; `data-edn`, `data-size`, `capped?` and the second render are the duplicates that go. Nothing here is conditional on a further decision. This is distinct from evaluation results, whose objects remain in memory only.

| Step | Data | Computed when | Carried where | Proportional to |
|---|---|---|---|---|
| construct | `{:seon.error/at :seon.error/layer :seon.error/operation ?message + the declared domain members}` | at the refusing function, `at` supplied as data | the return value (or `ex-data` of the one throw at a `:panic` seam) | the one map |
| validate | the returning arity's declared output, including its explicit error alternatives | after the call, in the armed wrapper | Malli compiles and retains the complete arity output validator at wrapper acquisition | the declared contract; values accepted by success branches remain data, with no second error classifier or population scan |
| identity | D13 signature: `[layer operation declared-schema throwable-class frame expected-key/shape path]` → `id/id … 64` | once per recorded error, from its complete observation and the one schema name explicitly carried by its producer in recording custody; a normalizer that converts a declared transient refusal supplies its own declared output name before identity | `:seon.error/signature` and `:seon.error/declared-schema` on the root | the one declared schema; no population scan, set or structural fallback. RESET NEEDED for historical observations without this evidence; disposable data is not migrated |
| result | `{:seon.error/shown :seon.error/result-id?}` | before recording, by the caller, through B2's result mechanism with an explicit profile and, when a ctx is supplied, the owning binding (`prepare-result`'s two renders and `blob/put!`, `error.clj:635-668`, are deleted) | the request handed to `recording` | one render under the profile; the handle names the OFFENDING value, not the explanation; after restart the handle is explicitly unavailable and the text remains |
| store | root `{signature declared-schema id layer operation frame? exception-class? expected-key? expected-shape? path?}` + occurrence component `{id count first-at last-at process agent? turn? message? shown? result-id?}` + the storable attributes of the one producer-declared schema and its base; declaration inventory supports storage metadata only | one `:db.fn/call commit-call` (`error.clj:1567`) | Datahike; transaction provenance in tx-meta (`transaction.cljc:903-922`); `:seon.error.occurrence/process` is the OBSERVED process, declared as such in its docstring | the declared datoms — `data-edn`, `data-size`, `capped?`, `data-blob`, `dropped-fault-*`, `proc`, `op`, `cid`, `throwable-class` (duplicate of `exception-class`), `regressions`, `issue` (the task points at the error, never the reverse) are deleted from `seon.error.edn:103-140` and from `commit-call`'s `evidence` list (`:1621-1630`); `:seon.instrument/fn`/`arm`/`expected` survive as members of their own declared schemas |
| path | `:seon.error/path` one value | at the wrapper's refusal | `:db.type/any` (A2's fork admission; RESET) — otherwise retain the existing structured representation until A2 supplies equivalent queryable storage | one problem; the `location`/`segment`/`omission`/`key` components and the 33 predicates (`error.clj:2314-2658`) retire only after the replacement preserves the D13 identity and queryable path |
| occurrence | `count`, `first-at`, `last-at` | inside `commit-call` from the mid-transaction db | the one occurrence `[:seon.error.occurrence/id id]` (already `old`, `:1578`) | one lookup; `recurrence` (`:1538`) and its sum are deleted |
| repeat → task | the complete task-trigger request for detector `seon.error/recurring`, subject `[:seon.error/signature signature]`, error ref and declared completion inputs | inside the same transaction when the root already has an occurrence: one seek `[:find ?o . :in $ ?s :where [?r :seon.error/signature ?s] [?r :seon.error/occurrences ?o]]` | the task's `:seon.task/errors` ref | one identity upsert; idempotent on every later occurrence |
| refusal inside the writer | a read that fails inside `commit-call` | — | throws through the transaction-function refusal convention (`turn.clj:305 refuse!`), never a map returned as transaction data | — |
| render | `seon.render.error/render-ai` / `render-html` (B2's namespace) | at read, through ordinary schema-pair selection | the schema property, declared once on `:seon.error/base`; the one named error schema renders with its base block | 275 identical property copies deleted only after §6.2 proves the base and named domain evidence survive |

**The constructor's contract.** `seon.error.refusal/diagnostic` (`refusal.clj:37`)
keeps its name and stays the one leaf; the pass-through facade `seon.error/diagnostic`
(`error.clj:304`) is deleted. Input: `[:map [:seon.error/at :seon.error/at]
[:seon.error/layer :seon.error/layer] [:seon.error/operation :seon.error/operation]
[:seon.error/message {:optional true} :seon.error/message] [:seon.error/throwable
{:optional true} :seon.error/throwable]]`, open — every domain member rides through
untouched. It consumes only `:seon.error/throwable`; it adds `:seon.error/frame` and
`:seon.error/exception-class` from a supplied Throwable and nothing else. Output:
`:seon.error/base`. This is the ONE Var whose output is the base — the arming
already exempts an arity declared as base (`::base?`, `instrument.clj:800`). It
broadens no caller's union because the caller's own arity still names its exact
error schema and the caller's wrapper validates the returned map against that
declaration: the constructor's base output does not replace a producing caller's exact union. Requiring `at` where it was previously absent is a contract change, so every caller supplies it in the same constructor-conversion slice. A site
without a Throwable may write the map literal directly (§6.1 probes whether the
constructor dissolves entirely into `throwable-members`).

**`diagnostic-cause`**: `effect.clj:677-694`
carries a Throwable in both `:seon.error/offending` and `diagnostic-cause`, and the
MCP specimen carried the message twice (pack §4a). The rule is therefore
"delete duplicate labels, never information by prefix": at each of the 275
sites, a `diagnostic-*` value that copies a sibling member is dropped; one that
does not is kept under its owning declared member (a Throwable → `:seon.error/throwable`
→ frame/exception-class; a keyword "cause" that named a kind is deleted under
D3, the schema being the meaning). The landing note counts each disposition.

**Kinds, classes, guards.** `kind` is absent from the live schema (P3
`:kind-installed? false`): the 799 sites are a pure code cut. D3 rules kind
and the class markers deleted in ONE cut: the 799 lines (37 src files,
`cluster.clj` 25 and `fn.clj` 25 among them), the 172 `:seon.error/class true`
markers, and `error_class_schema_test` land in one commit once the two held
files are free (§5 commit 4). The 167 `;; debt:` guards (`rg -c ';; debt:' src`)
are converted per callee union to the callee's declared propagation path — never another repeated guard or predicate; preserve a distinguishing member read only where the function makes a genuine domain decision — in the commit that fixes that callee's union.

**Union copies.** Of the 14, the six inside `error.clj` and the two in
`refusal.clj` are inspection inputs (cause-chain walk, stored-observation
restore, `refusal`) and become `:seon.error/base` inputs; `kernel.clj:540-574`
and `admit.clj:576-613` are B2's OUTPUT contracts, `seon.effect.edn:195-458` is
`:seon.effect/request-result` (an output; §2d), `seon.db.edn:9-30` is A2's
`:seon.db/error-result`. Output unions are NOT replaced by base: each keeps or
derives its exact alternatives from the callables it forwards (the producing-contract rule).
The `refusal_test` drift check dies with the eight inspection copies.

**Load cycle.** Functions that move to `seon.render.error` (B2 lands the
namespace; B3 deletes them from `error.clj` in the same publication):
`notice :831`, `schema-expectation :977`, `explain-problem :1020`,
`problem-sentence :1072`, `log-line :1421`, `render-ai :1975`, `render-html :1989`,
`faults-form :2119`, `index-refusal-prose :2298`, with their private helpers
(`refusal-text`, `rendered-error-value`, `ai-prose`, `scalar-text`) and the
`docstring-parts` delay (`error.clj:37-41`). `seon.error` then requires no render
namespace. `db.clj:48-60` holds FIVE delays into `seon.error`: three prose
functions (they die when A1's `default-errors` overlay renders the noun
description — A1-7) and two render pairs; both are A2's to repoint or delete
after the require graph is read — B3 edits nothing in `db.clj`.

**`:panic`/`:record`** (§1k): the one dial `:seon.config/on-core-error` is read at
the fault committer (B1's `cluster.clj`) and `db.clj:3228` (A2); B3 changes
nothing there. `:seon.config.error/escalate-to`, `/recurrence-limit`, `steward`
(`error.clj:1526`), `message-tx` (`:1553`) and the `recipients` block
(`:1663-1669`) are deleted: recurrence opens a task, never a message. **Ruled since (owner 2026-09-23, AGENTS.md error policy; [final design](../../../research/agent-platform/error-route-final-design-2026-09-23.md)):** every unhandled error takes the one route `seon.fault/fault!` — stored with `:seon.error/chain` at the owning boundary (never through the counted-dropping fault channel), deduplicated by the D13 signature with count/last-at, and delivered by waking the responsible agent (root by default, else the namespace's owning agent) through the ordinary wake route; `:panic` throws to the caller and stops the failing graph, `:record` keeps running; a database that cannot store the error panics in both modes. It lands as the M4 slot with the flow must-now items (README §4 row 1.6), so the committer is no longer "unchanged".

### 2b. One task family

`resources/seon/schemas/seon.task.edn` (new; replaces `seon.issue.edn`,
`seon.issue.citation.edn`, `seon.plan.edn`, `my.plan.edn`, `my.plan.item.edn`).
Observations are values, statements are refs (G2):

| Attribute | Type | Deletion dial | Note |
|---|---|---|---|
| `:seon.task/id` | string, identity | — | detected: `(id/id (into (sorted-map) {:seon.task/detector detector attr value}))` — the exact input keys of `issue/subject-id` (`issue.clj:554-564`) with the key renamed; authored: supplied, or minted once by `(id/id)` — never from the title |
| `:seon.task/title`, `/problem` | string | — | problem is the instruction text |
| `:seon.task/severity` | enum blocker/friction/cleanup | — | the one closed set (AGENTS §3); ranks the index |
| `:seon.task/detector` | `:qualified-symbol` VALUE | — | present on detected tasks; a symbol with no `:seon.fn` row is reported positively, never "done" |
| `:seon.task/subject` | `[:tuple :qualified-keyword :seon.schema/value]` VALUE | — | the installed identity value the detector saw |
| `:seon.task/tests`, `/functions`, `/namespaces` | `[:set :qualified-symbol]` VALUE | — | retracting a test touches no task datom; a cited symbol with no row is "unknown", never green; append-only after assignment (today's `seon.issue.edn:10` property, kept) |
| `:seon.task/errors` | `[:set :seon.db/ref]` | optional, sweep | the error root(s); the task points at the error, never the reverse |
| `:seon.task/agent` | ref, `:seon.wake/listen true`, `:seon.wake/opens-turn? true` | optional | the ASSIGNED agent (D2), asserted exactly once by `start-call`; this is today's `seon.issue.edn:30-33` declaration moved verbatim — the only listened task attribute and not a new one. The RESPONSIBLE agents are derived `fn → ns → :seon.ns/agents`, never stored (goals §5) |
| `:seon.task/budget`, `/budget-exhausted-tx`, `/resolved-tx`, `/created-by` | as `seon.issue` today | — | T1/T4 unchanged |
| `:seon.task/parent` | ref → task | optional; writer refuses unrepaired incoming obligations before sweep | a plan step; with `:seon.task/needs [:set ref]` and `:seon.task/position :int` |

Deleted with the note pipeline: `status` (open = no `resolved-tx`), `opened`,
`path`, `keys`, `files` + the citation component, `runs`, `issues`, `members`,
`unresolved`, `commits`, `class`. No `updated-tx`, no additional wake attribute
(goals §5: "do not put a wake attribute on the task").

| Mechanism | Data | When | Where | Proportional to |
|---|---|---|---|---|
| `seon.task/trigger-call [db request]` | detected request: detector + subject + declared task members; authored request: supplied/once-minted id + title/problem + completion inputs | inside the caller's transaction (fault writer, detector run, merge writer, `my.task/add!`) | `:db.fn/call` | one identity lookup: present → `[]` (no new task, agent or notification: D2); absent → the task row. Never creates an agent |
| `seon.task/start-call [db request]` | `{:seon.task/id task-id}` plus optional `:seon.task/agent existing-agent-id` | inside the starting transaction | `:db.fn/call` | refuses: no task; the task is already assigned; no declared completion evidence (tests/detector for repair work, a declared triggering-message/reply completion relation for conversation, or nonempty derived children for a parent); detector/subject naming no current row; a named agent that does not exist; unavailable completion inputs. Admits: composes B2's agent-creation and first-turn transaction data for a NEW agent, or asserts `:seon.task/agent` on an EXISTING agent (root for a conflict task). The assertion is the first wake |
| repeat trigger, assigned task | a new occurrence on a linked error root, a new detector finding | inside that transaction | the error's occurrence datoms | no task datom changes; B2 routes the new actionable occurrence through the existing listened message/occurrence owner, then the task read refreshes by its evidence. Continuation alone cannot wake a parked or budget-exhausted agent; prove that case before replacing delivery. Duplicate delivery emits no second occurrence or message |
| repeat trigger, unassigned task | same | same | — | `[]`; the task waits for `start-call` |
| duplicate delivery | the same finding submitted twice in one transaction or two | — | identity upsert | one task, one agent, zero notifications; proven by §7's idempotence regression |
| `seon.task/done?` [db task] | declared completion evidence | at settlement, against ONE database value | pure query | `(tests? ∨ detector?) ∧ (tests? ⇒ every cited symbol has a `:seon.test` row with positive green at current reach — today's `tests-done-query`, `issue.clj:1028-1040`) ∧ (detector? ⇒ the detector's subject-scoped query names nothing)`; a missing test row, missing subject or unresolvable detector is UNKNOWN → not done. For repair tasks carrying both, both obligations hold. Conversation completion requires the accepted reply answering its triggering message; a parent requires a nonempty complete child set. These are declared data relationships, not a stored task kind |
| `seon.task/settle-call [db agent-id]` | this agent's open tasks | turn close, after the tests ran | replaces `plan/settle-call` (`plan.clj:952`) + `issue/exhaust-tx` (`:1310`) | writes `resolved-tx` when `done?`; `budget-exhausted-tx` + the T4 message to root when the budget is spent |
| `seon.task/run-tests!` | the task's cited tests whose reach changed | turn close, before settlement, OUTSIDE the transaction | B4's final `seon.test/run` over the task's actual program, with explicit required test identities and recording authority | replaces `plan/run-issue-tests!` (`:823`); never inside a transaction function |
| detectors | `public-without-doc`, `-contract`, `-reaching-test` (`issue/detect.clj:181,228,285`) | when root's `seon.schedule.task` row fires (`schedule.clj:331 fire-call` claims the fire; the fired function runs the detector) and at publication for the CHANGED declarations | `seon.task` queries taking `{:seon.task/subject [a v]}` and starting from the indexed identity `[?f :seon.fn/sym ?sym]` | the affected declarations; the private-contract detector (3,145 private functions without contracts — goals §2d) is added because private contracts are the first task class; scope by declared provenance, never by name |
| conflict task (D6/D8) | detector `'seon.program/conflict`, subject = D1's structural conflict identity (competing definitions + basis), assigned to root | D1's merge writer | `trigger-call` + `start-call` with `:seon.agent/id "root"` | one instance per structural conflict; B3 supplies writers that compose on one transaction's current facts, D1 supplies the resolvable conflict subject, immutable source commits and basis. For repeated conflict delivery, the writer starts only an unassigned task; an already assigned root task is retained. Explicit start on an assigned task refuses |
| plan step | a task with `parent`/`needs`/`position`, `created-by` = the author | `my.task/add!` | the same entity | readiness = open ∧ every declared `needs` exists and is resolved ∧ every child resolved, within the author's tasks; an aggregate parent is done only when its nonempty child set is complete. Preserve missing-edge evidence under retraction; optional sweep cannot silently erase an obligation. Validate new parent AND needs edges, self-links, cycles, author scope and sibling position ties under the declared query-work bound; `plan!`/`compile-tree`/`refuse-*` (`plan.clj:1202-1576`) deleted |

Task retraction refuses while any surviving task still depends on it through `parent`/`needs`, unless the same final transaction explicitly repairs those obligations. This writer decision precedes Datahike's automatic incoming-ref sweep; deleting a target cannot make its dependents ready. Parent-cycle and needs-cycle checks both belong to the task writer.

Consequences: `:seon.agent/plan` (`seon.agent.edn:156`, a component) is
deleted — agents are never retracted, so nothing cascades; its readers
`cluster/agent.clj:174,312`, `cluster/status.clj:116`,
`render/transcript.clj:1244-1282,2134-2136` (B2) become `(seon.task/of-agent db id)`.
`turn.clj` reads issue attributes at `:2050-2051`, `:2689`, `:2724-2725`,
`:2912-2942`, `:4999` besides the seven `plan/` sites (`:2270,3573,3575,3603,3612,4834,4838`)
— thirteen sites, B2's file. `bootstrap.clj:378,544-557` reads `plan/ready-subjects`
for the retired generated opening (§6.4). `my.task` is the thin `my.*` protocol
over `seon.task` facts (AGENTS §3 layering, as `my.message` over
`seon.cluster.message`), not a second family: `tasks`, `task`, `add!`, `tests!`,
`start!`; a manual `complete!` is an owner decision (§8).

Namespace responsibility is B3's surviving schema/reader work: `:seon.ns/agents` is a many-to-many ref relation, separate from an agent's REPL namespace. Candidate messages use B2's cluster-qualified message owner; D1 records candidate identity/address on the shared task through this same family. Starting candidate work must not also run it on shared. B2/D1 prove inherited unrelated turns/schedules do not advance. Candidate-local completion is evidence; shared repair resolution occurs only with D1's explicit accepted merge. Resume reuses the assigned agent and declared budget transition; B2 exposes stopped/failed graph state as unavailable, never healthy absence. First live contract-coverage work waits for D1's complete merge proof.

Notes: the relevance audit (README §8) already classified the 369 live notes;
class A is deleted now, class B closes at its spec's landing, the rest stay as
documents. There is no bulk promotion script: a task is created by `my.task/add!`
when an agent (root, or a namespace agent) takes a note up, carrying the note's
claim and location as the task's problem and subject. Promoting a directory of
notes into a directory of tasks would be the "fake tasks" the owner ruled out.

### 2c. Dials

Candidate removals (23 historical declarations), each gated by its consumer and bounded-event proof: `seon.config.ai.retry/*` (6 — one
provider/failover policy must be preserved through B2's request owner; remove these only after its declared attempt bound replaces the retry mechanism), `ai.backup/timeout-ms` (only after the shared effective deadline preserves both attempts), `ai/chars-per-token-prior`, `shell/inline-output-bytes`,
`shell/preview-bytes` (one inline cut: `fs/max-inline-bytes`),
`test/auto-check-cases` (B4 agrees), `bootstrap/beyond-closure-token-budget`,
`agent/write-refusal-bound`, `agent/show-all-settings`, `error/recurrence-limit`,
`error/escalate-to`, `error/max-evidence-bytes` (the render profile is the one
bound), `maintenance/min-usable-ratio`, `web/max-search-results`,
`render.agent/composition`, `render/issue-opening`, `flow/ping-timeout-ms`,
`render/coalesce-ms`. Kept although "unread by literal": the seven
`:seon.config.ai/*` wire passthroughs (`ai.clj:592-639` reads them through the
`:seon.ai/wire` property) and `shell/{home,path,lang}` (`shell/jvm.clj:96`);
`run/max-episode-runs` stays and is renamed with B2's commit 8.

| Dial standing for an event | The event, and the proof each replacement owes |
|---|---|
| `agent/turn-completion-backstop-ms` (600 s) | `:seon.turn/closed-tx`; B2 derives the bound from ALL admitted work (every evaluation's `eval/time-limit-ms`, every provider attempt's `ai/timeout-ms`, settlement) — not one evaluation plus one HTTP wait. The key stays B3's until that derivation lands, then is deleted |
| `operator/event-silence-backstop-ms` (30 s) | guards the operator's prepl exchanges too (`fresh_operator.clj:81-98`, `prepl-eval!` `:1822`), not only B4's worker pool; B1 owns its bounded admission; NOT deleted on B4's behalf |
| `shell/termination-grace-ms` (1 s) | `Process.onExit` after `destroyForcibly` (`shell/jvm.clj:331-332`); stays until the shell owner proves tree termination and output-drain completion under the remaining bound — an exhausted execution budget cannot also promise cleanup time |
| `flow/ping-timeout-ms` (20 ms) | proc replies; `flow/ping` (`flow.clj:136-142`) returns only responders within its default 1,000 ms — the dial is deleted and the caller reports expected-minus-responding procs as missing replies |
| `render/coalesce-ms` (16) | the SSE feed's own channel readiness (`render/web.clj:2048-2056,2653-2672`); B2 removes the sleep and verifies slow-consumer delivery; B3 deletes the row after — the sliding buffer is not browser readiness and is not claimed to be |
| `seon.effect/time-limit-ms` (per-request override) | handler completion under the capability's declared bound carried on admission; removing a shorter request override changes behaviour — deferred to the owner (§8) |
| `seon.test-support/event-backstop-seconds`, `seon.test/time-limit-ms` | B4's terminal facts; `seon.test/long-ms` is a declared duration allowance, not an event |

**`seon.env`** (`env.clj:36-115`): `defrecord Environment`, the `defonce` class
pin, `environment?`, `environment-state?` (asserts `IAtom`, `:74`),
`environment-state` (`:81`), `replace-environment!` (`:107`),
`advance-projection!` (`:134`), two generators and the `print-method` are
deleted; the environment is a namespaced map, `:seon.env/environment [:map …]`,
one render pair. Its consumers, ALL outside B3: `db.clj:1851,4388`,
`cluster.clj:2111,3178`, `sci/eval.clj:152,157,174,647,2206,2849,2854`,
`test/runner.clj:1513`. **`effect/*request-context*`** (`effect.clj:42`; 16
reads in `effect.clj`, and FIVE outside: `shell/jvm.clj:401,492`,
`instrument.clj:161,232`, `sci/eval.clj:2909`): one environment is captured at
evaluation/request admission and passed through supplied defaults, handler
arguments and Flow request data; `request*` (`:788`) takes it as its first
argument; `with-request-context` (`:488`) is deleted only after the last
outside reader is converted. Paired cut: A1 (`instrument.clj`), A2 (`db.clj`),
B1 (`cluster.clj`), B2 (`sci/eval.clj`, `shell/jvm.clj`), B4 (`runner.clj`).

### 2d. Effects, search, blob

| Item | Decision | Evidence |
|---|---|---|
| synchronous effect rows | a row is written only for `:seon.effect/background? true` and for capabilities declaring write-back provenance (`my.edit`: `:seon.effect/file`, `/form-span`) | readers: `background.clj:36-125`, `edit/jvm.clj:73-74`, `turn.clj:1732` (recovery stamps), `turn.clj:2002-2003` (per-turn ordinals — B2 converts to the evaluation's shown text). **RESET NEEDED** regardless of `default`'s 0 rows (P1) |
| `:seon.effect/request-result` | a justified polymorphic dispatcher result; the selected handler's own compiled output contract validates its result and supplies the declared error alternatives used in settlement | the producing-contract rule; no copied registry-wide union |
| `receipt-state`, `payload-face`, `receipt-identities` (`effect.clj:53-72`) | deleted with the retired spelling | vocabulary |
| `reach-rules`/`capabilities` (`:142-169`) | kept; the query already takes a root; measure the reached work under program growth before adding any elision — none is added on assumption | the producing-contract rule2; sole caller `test/accretion.clj:98` |
| `seon.search` | deleted whole: `derived/lucene`, the handle, atoms, lock, `index-step` proc, cluster wiring (`cluster.clj:72,789,796,2859-2867,2911,2923-2924,2990,3022,3094-3099,3133-3137` — held), `:seon.search/handle` env member, `seon.search.edn`, `search_test`, in ONE commit with its callers | `search/search` has zero src callers (pack §8) |
| `tokens` + `similar-identities` (`search.clj:123-167`, 45 pure lines) | moved to `seon.schema.admission` (A1's file; paired commit) | sole caller `admission.clj:332` |
| `blob/verify-stored!` (`blob.clj:149-176`) | handed to A2 with the correct citation: the staging digest is computed at `staged-write` (`blob.clj:179-183`); the re-read is what may be redundant; A2 states the guarantee being trusted | the producing-contract rule2 |

## 3. Reading list

| Read | Guarantees |
|---|---|
| `reference-code/malli/src/malli/core.cljc:996-1022` `:or` explainer; `:2207` `:gen` option; `:2626-2648` retained Schema cache | `:or` short-circuits validators; explanation accumulates failed branches until one succeeds — reuse the compiled explainer, never `explain` per call; `:gen` receives each child schema when its callable is built (A1's wrapper probe) |
| `reference-code/malli/src/malli/error.cljc:44-172` `default-errors`; `:288-306` `error-message`; `:374-390` `humanize` | keyed by `::m/missing-key`, predicate symbols and scalar types; ten-step fallback; `humanize` is shaped like the VALUE, not a sentence — Seon's one-line grammar (goals §2e) is composed from it, not replaced |
| `reference-code/datahike/src/datahike/db/transaction.cljc:903-922` tx-meta expansion; `:1231` default instant; `:998-1015` incoming-ref sweep + component cascade; `:1020-1039` tuple type/size (max eight); `:1153-1154` mid-transaction db | provenance lives in declared tx-meta attributes; a tuple cannot hold a long mixed Malli path (hence `:db.type/any`); a `:db.fn/call` sees the mid-transaction db, not a frozen pre-read |
| `reference-code/datahike/src/datahike/pull_api.cljc:16,315,323` | default 1,000 cut, `:limit nil` bypasses it — removing the limit does not make a scan cheap; the recorder stops scanning instead |
| `reference-code/clojure/src/clj/clojure/core.clj:4924,4933`; `core_print.clj:473` | `ex-info`/`ex-data`; `Throwable->map` `:trace` is a VECTOR of frames; `:seon.error/frame` stores the top one |
| `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:136-142` | `ping` returns the procs that replied within `timeout-ms` (default 1,000) — partial replies, not readiness |
| `src/seon/error.clj` signature, `commit-call`, `recording` and declared-schema inventory | D13 uses the single schema name supplied by the producer through recording custody. Inventory is declaration metadata only; the recorder never infers a name or a set from a value. Historical observations without the name require RESET, not migration |
| `src/seon/instrument.clj` compiled wrapper | Malli owns the complete arity output validation; no separate base-shaped result classification |
| `src/seon/issue.clj:554-564` `subject-id`; `:1028-1064` done | the identity derivation carried into `seon.task`; the tests-vs-detector `cond` the new `done?` makes a conjunction |
| `src/seon/plan.clj:265-305` `derived-frontier` | handles children and missing foreign dependencies — the invariants the parent/needs readiness query must keep |
| `src/seon/schedule.clj:331` `fire-call` | claims one nominal fire idempotently; it does not run the detector — the fired function does |
| `src/seon/call_preparation.clj:14-70` | supplied defaults: how `my.*` receives the environment as data |
| `src/my/program.clj:238-262` | the read idiom a detector follows; `:262` calls `issue/subject-id` today |

## 4. REPL protocol

`eval_clj` mode `jvm`, cluster `default`, `read_only true` for reads. `conn` =
`(seon.operator/connection "default")`; `db` = `(seon.db/db conn)` (a raw
`datahike.api/db` value carries no projection — `carried-projection` is nil on it).

The table names acceptance scenarios. Implementation supplies complete canonical-fixture forms with concrete task/error identities in the landing script; it must not execute an abbreviated request.

| # | Before | After |
|---|---|---|
| 4.1 fault shape | `(seon.db/pull db '[* {(:seon.error/occurrences :limit nil) [*]}] [:seon.error/signature s])` → `data-size 55201`, `data-edn` 1,062 bytes, `capped? true`, blob digest | the same pull returns only declared attributes; `:seon.error.occurrence/shown` ≤ the agent profile; no `data-*`, no blob ref |
| 4.2 tasks | P1: 1,882 `:seon.issue/id`, 0 with agent, 0 with detector | `(seon.task/report db)` lists tasks with a live subject; `(seon.db/pull db [:seon.issue/path] e)` refused: attribute gone |
| 4.3 done cost | `(time (seon.issue/done? db [:seon.issue/id x]))` runs `detector-rows` over the program | `(time (seon.task/done? db [:seon.task/id x]))` — one identity seek + the subject's tests; report the ms and the datoms visited, not only elapsed |
| 4.4 recorder | two same-signature observations in two turns: `commit-call` pulls all occurrences twice | the second write reads only its own occurrence and one existence seek; the occurrence query returns the intended two occurrence identities, `:seon.task/id` for `'seon.error/recurring` present exactly once |
| 4.5 trigger/start | — | `trigger-call` twice with one request → second returns `[]`; `start-call` on a task with neither tests nor detector → typed refusal; `start-call` twice → second refuses the existing assignment; repeated trigger retains the same task/agent |
| 4.6 constructor | the existing seven-key constructor request | `(error.refusal/diagnostic {:seon.error/at (java.util.Date.) :seon.error/layer :x/y :seon.error/operation 'a/b :x/member 1})` → 4-key map; returned through an armed function declaring `:x/y-error` passes; through one declaring only `:x/z-error` → the wrapper's refusal |
| 4.7 dials | `(count (seon.config/dial-attributes (seon.db/carried-projection db)))` = 96 | remaining count with each removal's event/bound proof; 73 only if all 23 pass |
| 4.8 env | `(instance? clojure.lang.IAtom (:seon.sci.eval/projection-state cluster))` true | `(map? (seon.env/of ctx))` true; no atom in `seon.env.edn` |

Every commit's debug probe: `runtime_status` on `default` healthy; probe 4.1 or
4.2 returns; `/agent/root` renders. Adoption is `bin/seon init --dev default
--changed <paths>` after shell writes; the landing note names whether each proof
exercised a hot-reloaded Var or in-place adoption.

## 5. The work, ordered as commits

Each commit loads HEAD (`clojure -M -e "(require …)"` over the namespaces it
TOUCHES, named per row) and hot-reloads on `default`. A held file defers the
commit that needs it; the completion criterion never shrinks to "unheld files".
Only the orchestrator may recover a broken `default` with `bin/seon reset --force` (ruled 2026-09-23, README §7 "Schema change and reset": `reset --force` unlinks the cluster branch and forks a fresh one from the program rows, keeping every cache; `start --head` moves the JVM to committed HEAD keeping the store; `nuke --force` alone deletes the store, for a truly broken store) —
loses recorded turns, results, tasks and in-memory objects; reseeds root.

| # | Commit | Requirers proven to load | Net | RESET |
|---|---|---|---|---|
| 1–3 | ONE constructor/caller slice: replace the seven-key input with supplied `at` and declared members, convert all 275 construction sites (94 owned, five A2 blob sites, the remaining cross-owner sites), preserve each distinct cause, and retire the facade/old keys only with the last caller. Requiring `at` is a breaking input change, not accretion. Prepare the edits by owner, publish/commit them together; a held caller defers this slice | every touched namespace, including `seon.error.refusal seon.error seon.effect seon.config seon.plan seon.issue seon.env seon.bootstrap` and the coordinated callers | provisional −1,540 across disjoint spans | no stored-shape change yet |
| 4 | THE kind/class cut, one commit when `cluster.clj`/`fn.clj` are free: 799 `kind` lines, 172 markers, `error_class_schema_test`; propagation-only guards disappear; a genuine domain decision reads its declared distinguishing member | `seon.turn seon.schema seon.fn seon.cluster seon.db seon.cluster.message seon.cluster.agent seon.schema.edn seon.agent seon.cluster.prompt` + the 27 smaller files | −300 | no |
| 5 | eight inspection unions → `:seon.error/base`; `refusal_test` drift check deleted; output unions untouched | `seon.error seon.error.refusal` | −320 | no |
| 6 | renderer moved by FUNCTION (§2a list) into B2's `seon.render.error`; `error.clj:37-41` delay deleted; dead prose builders (`:1304` etc., alive only through `error_test`) deleted | `seon.error seon.render.error seon.db` | −1,000 (300 moved) | no |
| 7 | `seon.task` ADDED: schema, `trigger-call`, `start-call`, `done?`, `settle-call`, `run-tests!` (B4's `run`), scoped detectors incl. private-contract, render pair, `of-agent`, `report`; `my.task`; no caller yet | `seon.task my.task` | +450 | new optional attributes alone need no reset; incompatible task retirement below is **RESET NEEDED** |
| 8 | callers converted while `seon.issue`/`seon.plan` still load: `turn.clj` thirteen sites (B2), `cluster/agent.clj:174,239-240,312,359`, `cluster/status.clj:116`, `render/transcript.clj` (B2 deletes it), `bootstrap.clj:38,378,544-557`, `my/program.clj:262`, `seon/note.clj:72-108`, `my/{agent,note}.clj` request schemas, `render/value.clj:52,78,358-361`, `instrument.clj:540,578`, `error/refusal.clj:9,91`, `script/seon/dev/issues.clj`; C1 starts directly against the new task writer after this slice | each file's namespace | −250 | no |
| 9 | retirement: `seon.issue`, `issue/*`, `my.issue`, `seon.plan`, `my.plan` (B2 pairs), the five schema resources, `:seon.agent/plan`, note indexing (`cluster/source.clj:340-349,388-392`, `cluster.clj:1842,2062,2090-2091` — B1's held file), `notes_to_tasks.clj` added | `seon.cluster seon.cluster.source seon.cluster.agent seon.turn seon.bootstrap` | −3,900 | **RESET NEEDED** |
| 10 | after the §2a durability decision, stored error shape: `seon.error.edn` fact/occurrence reduced to §2a; `location`/`segment`/`omission`/`key`/`projection`/`evidence`/`basis` resources + `error.clj:2314-2658` deleted; `prepare-result` deleted, `recording` takes prepared `shown`/`result-id`; `max-evidence-bytes` deleted | `seon.error seon.cluster seon.db` | −800 | **RESET NEEDED** |
| 11 | recorder proportional: `recurrence`, `steward`, `message-tx`, `recipients`, `escalate-to`, `recurrence-limit` deleted; one lookup + one seek; `trigger-call` on repeat; refusals throw | `seon.error` | −150 | no |
| 12 | dials: only declarations/rows/readers whose §2c replacement proof passes; `seon.env` value type; `effect.clj` context argument (paired with A1/A2/B1/B2/B4 rows in §2c) | `seon.config seon.env seon.effect` + the paired lanes' files | −450 src, −300 schema | **RESET NEEDED** |
| 13 | effects: synchronous rows dropped, retired spellings deleted, `seon.effect.edn` narrowed | `seon.effect seon.background seon.turn` | −350 | **RESET NEEDED** |
| 14 | `seon.search` deleted with its cluster wiring (B1's file) and `admission.clj` receives `tokens`/`similar-identities` (A1's file) | `seon.schema.admission seon.cluster` | −620 (45 moved) | no |
| 15 | `seon.bootstrap` cut to `seed-tx` + `supervision-tx` (§6.4 decides) | `seon.bootstrap seon.cluster.agent seon.sci.eval` | −800 | no |

The orchestrator batches incompatible stored-shape RESET boundaries (9, 10, 12, 13 can be one
reset); lanes never restart `default`.

## 6. Better than the floor — probes first

| # | Candidate | Probe that decides |
|---|---|---|
| 6.1 | the constructor dissolves: sites write the map literal; one `throwable-members` derives frame/exception-class | count the 275 sites that carry a Throwable; use the smallest form that preserves the full declared diagnostics and all callers; the fraction of Throwable sites is descriptive, not an arbitrary threshold for a new API |
| 6.2 | one render pair on `:seon.error/base` instead of 275 property copies | render one producer-declared error through ordinary pair selection; the base block and that named schema's domain evidence must appear; only then delete the identical copies — no special error dispatch clause |
| 6.3 | `:seon.error/path` as `:db.type/any` | A2 round-trips a mixed path longer than eight elements through the fork admission; if it lands, store it; otherwise retain the current path representation; shown text alone does not preserve structural identity/queryability |
| 6.4 | `seon.bootstrap` (932): `situation`/`next-entry`/intent acquisition are the retired generated opening; `help`/`dir`/`doc` are `sci/eval.clj:1565,1597` and the injection at `:254-267,2010` | is `bootstrap/situation` (`cluster/agent.clj:359`) reached by a live turn on `default` now that `turn/system-turn` (`turn.clj:2103`) stores the opening? A trace on one turn decides; three options in §8 if it is |
| 6.5 | detectors run at publication for changed declarations only, root's schedule only sweeps | measure detector work under one unrelated declaration change: visited datoms must not grow with the program |

## 7. Tests

| File | Lines | Disposition |
|---|---|---|
| `error_class_schema_test` 182, `search_test` 218, `error_write_timing_test` 182 (60 s escape on one write), `error/refusal_test` 90, `issue_test` 455, `issue_generate_test` 195, `issue_settlement_test` 349, `issue_deletion_test` 92, `issue/detect_test` 197, `my/plan_test` 607, `plan_test` 45, `plan_completion_test` 72, `bootstrap_test` 470, `bootstrap_drive_test` 84 | 3,238 | delete; replaced by `seon.task_test` ≈ 200: trigger idempotence, duplicate delivery, start refusals (no evidence · assigned task · missing subject), assigned-agent reuse, done under a retracted test (unknown, not done), scoped detector, dependency cycle refusal, settle |
| `error_test` 1,437 (47 deftests) | | keep the classes: declared/undeclared return, sorted-map input, signature invariance, repeated occurrence writes (one lookup), recording; retain the surviving render/prose regression blocks with current declared inputs; delete tests for retired union and constructor machinery → ≈ 400 |
| `returned_error_test` 62, `blob_error_test` 18, `blob_threshold_test` 16, `background_test` 51, `my/{fs,web,edit,background}_test` | | keep |
| `error_result_test` 160, `long-ms 60000` no reason (`:99-101`) | | measure; if the cost is base acquisition (B4's 3,996 ms) the escape goes with B4's fixture; otherwise the number and reason are declared |
| `config_functions_test` 32, `long-ms 20000` ("13.05 s; the symbol query is 4.70 ms") | | `require-functions!` (`config.clj:737-780`) already uses one query; the escape stays with its number until A2/B4 measure the fixture/writer cost it names |
| `config_application_test` 254, `:seon.test/long` without `-ms` (`:147`) | | a reason without a number is a refusal: measure and declare, or split |
| `config_test` 702, `effect_test` 1,032, `env_test` 384 | | collapse to the surviving mechanisms: manifest difference, request with a carried context, env as a map; ≈ 600 |
| `my/test_test` 78, `long-ms 300000` | | B4's |

Test lines ≈ 8,900 → ≈ 2,100 (provisional). The lane runs only the tests
reaching its change, in process through B4's `seon.test/run`; never a suite.

## 8. Done, size target, landing note, stop rules

| File | Before | Floor (audit) | Target | Why the gap |
|---|---:|---:|---:|---|
| `error.clj` + `refusal.clj` | 2,790 | ≈ 900 | **550** (+300 in `seon.render.error`) | rendering leaves; EDN/blob/cap machinery dissolves into the value renderer; predicates gone; D13 carries the producer's one declared schema name explicitly |
| `issue`+`issue/*`+`my.issue`+`plan`+`my.plan` | 4,169 | ≈ 3,660 | **530** (`seon.task` 450 + `my.task` 80), conditional on §2b's start/done/plan guarantees | one entity, two writers, done as a query, no note pipeline, no tree reconcile |
| `config.clj` | 926 | — | **650** | 146 diagnostic lines → 40 |
| `effect.clj` | 1,053 | ≈ 800 | **500** | sync rows, dynamic var, retired spellings |
| `search.clj` | 571 | 0 | **0** (+45 in `admission`) | |
| `env.clj` | 531 | ≈ 465 | **150** | record/atom/predicates/print-method gone |
| `bootstrap.clj` | 932 | — | **120**, conditional on §6.4 | |
| **owned files** | **10,972** | ≈ 6,900 | **≈ 2,500 original owned scope / at least 2,850 retained, plus newly assigned surviving files** | moved code is charged to its destination; repository net deletion reported separately in the landing note |

Done: every §1 target measured after the orchestrator's fresh reset with exact self-contained §4 acceptance forms recorded at implementation; `rg -c ':seon.error/kind|:seon.error/diagnostic-|:seon.error/class true'
src test script bin resources` = 0 everywhere (not "unheld files"); HEAD loads.

Landing note `docs/prds/agent-platform/landing/lane-b3.md`: the §4 forms with
values, `wc -l` per file, held-file list, RESET commits, and **the exact deletion
list the note audit consumes**: `seon.issue` note attributes (`status opened path
keys files runs issues members unresolved commits class` + the citation component),
`issue/opening.clj`, `issue/detect.clj`'s minting, `seon.search` whole,
`seon.plan`/`my.plan*` and `:seon.agent/plan`, the 23 dials of §2c, the
`diagnostic-*` keys, `kind`/`class`, `data-edn`/`data-size`/`capped?`/`data-blob`/
`dropped-fault-*`/`proc`/`op`/`cid`/`throwable-class`/`regressions`/`issue`/
`resolved-tx` on errors, the `location`/`segment`/`omission`/`key`/`projection`/
`evidence`/`basis` error resources, `receipt`/`face` spellings, `seon.env`'s
record and atom, `*request-context*`.

Stop at: a held file (`cluster.clj`, `fn.clj` today); A1 (wrapper declared-only
validation, `admission.clj`, `default-errors` overlay, `instrument.clj:161,232`);
A2 (`:db.type/any`, `db.clj` delays and env readers, `blob.clj`, c8's 56 guards);
B1 (note indexing in `cluster/source.clj`/`cluster.clj`, search wiring,
operator silence); B2 (`seon.render.error`, the thirteen `turn.clj` sites and
the routed-problem block `turn.clj:2397-2613` it hands to the task family,
`my/plan.clj`, `:seon.agent/plan` readers, result preparation, `render/web`
coalesce, `ai.clj` retry, `sci/eval.clj:2909`, `shell/jvm.clj:401,492`);
B4 (`seon.test/run`, `runner.clj:1513`); C1 (waits for the task writer; no issue adapter);
D1 (conflict identity and sources).

Deferred to the owner (three-option notes in the landing note): (1) manual
`complete!` for authored substeps — recommended: query-completed substeps only,
a parent done when its children are; else a declared completion fact; else keep
`:seon.agent/plan` until decided; (2) the per-request `seon.effect/time-limit-ms`
override — delete (one bound per capability) or keep as an explicit narrowing;
(3) whether the conflict task's two sources are rendered by commit id through
`seon.program/history` (recommended) or copied as text.
