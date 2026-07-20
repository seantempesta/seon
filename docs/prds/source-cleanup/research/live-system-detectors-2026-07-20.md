---
type: research
status: in-progress
tags: [research, database, health]
---

# Live-system defect detectors — default cluster, 2026-07-20

Read-only interrogation of the LIVE default cluster and its durable data for
defect signals static audits cannot see. Probes run through the repository MCP
eval tools (`eval_cljs` pod, `eval_clj` writer) against the running system.

Sections fill in incrementally as probes complete.

## Headline: a read-only MCP probe crashed the live pod

While running this audit's own probes, one `eval_cljs` form
(`((fn ^:async [] (let [d (await (db/db))] (count (db/installed-schema d))))))`
— a caller mistake: counting a Promise inside a detached async fiber) was
recorded as fault `:core` (datom 3857, 13:40:24 EDT), and because
`config/system.edn:165` sets `:seon.config/on-core-error :crash`, the pod
exited. `bin/seon status` showed `pod drained pid=45513` (pid dead);
`bin/seon restart` reported `pod: forced reason=unexpected-exit`. The
`seon.error/dev-eval!` bracket documents that a dev/MCP REPL mistake is
`:agent` population ("dev probing must not crash the pod",
`src/seon/error.cljs:474-495`), including Promise settlement — but the
rejection from a detached fiber spawned by a Shadow-nREPL MCP eval reached
`record!` as `:core`. Either the MCP/Shadow eval path is not wrapped in
`dev-eval!`, or the AsyncLocalStorage scope does not survive that funnel.
Fault datoms 3689/3700 and 3767/3778 (other lanes' identical `db/pull`
Promise mistakes, 11:38/12:21 EDT) were also `:core`, while 3711–3755
(11:38–11:40) were correctly `:agent` — the classification is path-dependent.
This is the top triage item: any REPL user can kill the live default pod
with a typo.

The stack was restarted at ~14:05 EDT (`bin/seon restart`, watcher/writer
clean, pod forced) and verified ready.

## 1. Fault datoms (`seon.error/record!` projections)

Method (writer, read-only):

```clojure
(datahike.api/q '[:find ?e ?fault ?msg ?kind ?t
                  :where [?e :seon.error/fault ?fault ?tx]
                         [?e :seon.error/message ?msg]
                         [(get-else $ ?e :seon.error/kind :none) ?kind]
                         [?tx :db/txInstant ?t]] probe-db)

```

The default database was reset this morning: 43 total transactions, first
`:db/txInstant` 2026-07-20 10:13:57 EDT. Every durable signal below is from
today; there is no older triage backlog in this store.

**14 fault datoms**: `{:core 9, :agent 5}`; kinds
`{:seon.error.kind/malli-instrument-input 9, none 5}`.

| eid | fault | kind | time (EDT) | message | class |
|---|---|---|---|---|---|
| 3642 | :core | — | 10:14:21 | `boom` | test fixture |
| 3653 | :core | — | 10:15:22 | `boom` | test fixture |
| 3665 | :core | — | 10:37:42 | `reload: agent runtime rehost failed` | genuine live |
| 3682 | :core | — | 10:43:08 | `Verified program generation lost publication ownership` | genuine live |
| 3689,3700 | :core | malli-instrument-input | 11:38:01 | `db/pull` got a Promise | dev-eval, misclassified :core |
| 3711–3755 | :agent | malli-instrument-input | 11:38–11:40 | `db/pull` got a Promise (5×) | dev-eval, correctly :agent |
| 3767,3778 | :core | malli-instrument-input | 12:21:42/51 | `db/pull` got a Promise | dev-eval, misclassified :core |
| 3857 | :core | — | 13:40:24 | `ICounted on [object Promise]` | THIS audit's probe; crashed the pod |

Genuine live `:core` faults needing triage:

- **3665** `reload: agent runtime rehost failed` — data:
  `{:seon.agent/id "fresh-dancers-behave", :seon.agent.runtime/resumed? false,
  :seon.runtime.admission/status :publishing, generation -627501317}` —
  hot-reload rehost lost the runtime program generation.
- **3682** `Verified program generation lost publication ownership` — data:
  `{:seon.runtime.admission/generation -1377843308, state {:status :available,
  :publication 3}}` — admission generation churn during reload.

Cross-cutting defects in the fault-recording mechanism itself:

- **All 14/14 faults lack the Proximum branch head** (no `::store-id`,
  `::branch-name`, `::commit-id`, `::basis-t` on any persisted row) —
  `branch-head-now` never yields a valid head in the live pod, so
  `recorded-branch-head` returns `:missing-branch-head` for every fault and
  the `cluster fork <t>` forensics path has nothing to anchor on.
- **Frames are noise**: every fault's parsed frames are
  `{:index 0, :file "new"}` + `cljs.core.js` ExceptionInfo-constructor
  coords — `parse-frames` captures the constructor, not the throw site, so
  the Datalog-queryable frame design ("every :core fault whose top frame is
  in render/sci") currently answers nothing.

## 2. `logs/pod-events.log` frequency table

Method: `cat logs/pod-events.log.1 logs/pod-events.log | rg -o
':seon.log/source (:[^ ,]+).*:seon.log/level (:[a-z]+)' -r '$2 $1' | sort |
uniq -c`. Retained window: 2026-07-03T00:37Z → 2026-07-20T17:41Z, 14,687
lines.

| count | level | source |
|---|---|---|
| 8909 | :error | :seon.ai/complete |
| 3073 | :warn | :seon.client/var->fn-row |
| 1423 | :warn | :seon.render.sci/invoke-bounded |
| 720 | :debug | :seon.render.sci/require-info |
| 222 | :info | :seon.client/prune-core-ghosts! |
| 188 | :warn | :seon.agent.turn/blob-capture |
| 95 | :warn | :seon.client/log-replay-failure! |
| 29 | :debug | :seon.ai.openai-compat/empty-completion |
| 27 | :error | :seon.eval/record-eval |
| 2 | :warn | :c55.probe |

Error-level sources:

- **`:seon.ai/complete` (8909 — 61% of the whole retained log)** is
  dominated by deterministic test fixtures repeated per test run: "DeepSeek
  HTTP 500: 500 boom" (631), "DiffusionGemma generate error: CUDA OOM"
  (617), "no chat-completions URL" (633), "missing
  :seon.ai/config-resolution" (~1100 across adapters), plus genuine live
  failures interleaved (Anthropic/DeepSeek timeouts and connection errors,
  ~1200). Provider-failure tests log at `:error` through the production
  path, so the error channel is ~7:1 noise and unusable for live triage
  without timestamp forensics.
- **`:seon.eval/record-eval` (27, ALL today 14:06–17:41Z)** — every line is
  `tx FAILED: program row rejected — source: (+ 1 2)`-shaped. The last five
  (17:36–17:41Z) correlate 1:1 with this audit's own MCP evals: **every
  dev/MCP REPL eval's program-row persist is currently rejected**, at error
  level, silently to the eval caller. Live defect, not test noise.
- `:c55.probe` (2, 2026-07-11) — leftover probe logging, stale noise.

Warn-level notables: `:seon.client/var->fn-row` (3073) is the pure-data
Malli spec complaint for the same ~6 core fns on every reload
(`seon.instrument/coverage-gaps`, `seon.state/reconcile!`,
`seon.render.canvas/error-response`, `seon.db/transact!`,
`seon.eval/race-timeout` — real spec defects, logged repeatedly);
`:seon.agent.turn/blob-capture` today is 94× "test capture omitted" + 94×
"This process has no open database session"; `:seon.client/log-replay-failure!`
shows `my.*` namespaces failing replay with ``schema/register!` is not
defined`` (14× :my.expense, 13× :my.team, 4× :my.depot) plus 12×
`:seon.render.live-tile` failing on its own `error-request` schema.

## 3. Schema drift (registry vs installed)

Method: pod dumped all 1,958 registered form-strings
(`seon.schema/form-string` per `current-keys`) to
`tmp/detector-pod-forms.edn`; the writer derived each installed attribute's
expected Datahike declaration with
`seon.db.datahike.schema/malli-form->datahike-attribute` against that full
registry and compared `:db/valueType`/`:db/cardinality`/`:db/unique`/
`:db/isComponent` with the live `(:schema db)`.

**Result: clean.**

- Installed but not registered: **0** (all 360 application attrs have a
  registered schema; the 8 remaining installed idents are Datahike
  internals — `:db/ident`, `:db/txInstant`, `:db.entity/*`, `:db.valid/*`,
  `:dh.ref/*`).
- Type/cardinality/unique/component mismatches: **0 of 360**.
- Registered but not installed: 1,598 registered keys have no installed
  attribute — expected (request/response shapes, fn contracts, enums);
  not decidable as drift without transacting. No automated detector exists
  for this direction; first transact of a never-installed attr is the
  current failure point.

## 4. Program-graph anomalies (`:seon.fn` / `:seon.ns`)

Method (writer):

```clojure
(datahike.api/q '[:find ?e ?sym :where [?e :seon.fn/sym ?sym] (not [?e :seon.fn/ns])] probe-db)
(datahike.api/q '[:find ?e ?sym :where [?e :seon.fn/sym ?sym] [?e :seon.fn/ns ?n] (not [?n :seon.ns/name])] probe-db)
;; plus frequencies over ?sym / :seon.ns/name for duplicate identities

```

**Structurally clean**: 1,012 `:seon.fn/sym` rows, 158 `:seon.ns/name`
rows, 1,958 `:seon.schema/key` rows (matches the pod registry's 1,958
registered keys exactly). 0 fns without an ns ref, 0 dangling ns refs,
0 duplicate fn syms, 0 duplicate ns names, 0 `:seon.fn/schema-error` rows,
0 fns without `:seon.fn/source`.

Observations, not defects until ruled:

- 28 `:seon.ns/name` rows have no `:seon.ns/source` — `datahike.*` doc
  pages plus attribute-only namespaces (`seon.config.render`,
  `seon.error.frame`, `seon.fn`, `seon.ns`, `seon.typeahead`, …). If these
  are schema-domain rows, source is legitimately absent; worth one ruling.
- `:seon.fn/created-at` and `:seon.schema/created-at` are installed
  attributes — repository provenance rule says provenance lives in
  transaction metadata (`:seon.db/user`/`:seon.db/process`), not copied
  onto domain entities as `created-at`. Possible rule violation carried
  by the program graph itself.

## 5. Warning-check census (`seon.warn`, installed pre-edit behavior)

Method: pod eval at **2026-07-20T17:51:51Z** (after the restart; lane A's
`seon.warn` edits were NOT in `git status` at probe time, so this is the
committed/installed behavior):

```clojure
(seon.agent.ctx.warnings/warnings-block
  {:seon.agent/id "root" :seon.agent/entity ent
   :seon.render/node {:seon.warn/ns :seon.warn/all} :seon.db/db db} nil)

```

Whole-core scope (`:seon.warn/all`). Five clusters fire; every runtime
check (failed-evals, bad-ref, fs-denied, hop-exhausted, slow-evals,
failing-tests, canvas-unresolved, parallel-attr, unmarked-entity-kinds)
is currently clean.

| check | affected | notable members |
|---|---|---|
| no-malli-schema | 233 | whole `my.plan.internal`, `seon.agent.{fs,search,shell,web}.internal`, `seon.db.internal`, `seon.schema.internal`, web/ui fns |
| arg-is-any | 128 | `seon.eval/*` (heavy), `seon.error/*`, `seon.log/*-console!`, `seon.render.value/*` |
| no-input-spec | 100 | 0-arg fns missing `[:cat]` — `seon.config/*`, `seon.schema/*`, adapters |
| return-is-any | 89 | `seon.db/pull`, `seon.db/query`, `seon.eval/eval`, adapters, lifecycle fns |
| uses-maybe | 67 | `seon.error/->map`, `seon.schema/current-projection`, `seon.handlers.*` render fns |

These 617 spec-hygiene defects are the live-derived counterpart of the
static spec audits; the census proves the derivation path itself works and
that agents currently see this exact wall of warnings when scoped to all.

## 6. Database hygiene (identity attrs, orphans, blobs)

Method (writer): identity attrs from installed `:db/unique
:db.unique/identity`; per-attr entity counts; every `:db.type/ref` datom's
target checked for existence via `(datahike.api/datoms probe-db :eavt v)`;
nil scan over all datoms.

- **0 dangling refs** across all ref attributes; **0 stored nils**.
- Identity census (non-zero): `:db/ident` 360, `:seon.schema/key` 1958,
  `:seon.fn/sym` 1012, `:seon.ns/name` 158, `:seon.db.protocol/request-id`
  45, `:seon.route/name` 7, `:seon.agent/id` 3, `:seon.db.process/id` 3,
  `:my.kb.shared/id` 1, `:seon.config/id` 1, `:seon.user/id` 1. All other
  identity attrs (turn/run/eval/message/testrun/typeahead/…) are 0 — the
  post-reset store has no agent activity yet.
- Agents: `root`, `fresh-dancers-behave` (hosted, advertised) and
  `tame-shoes-raise` (`:seon.agent/terminated-at` 16:28:08Z — correctly
  not hosted; not a defect).
- **Blob store**: `data/clusters/default/blobs` holds **8,413 files,
  681 MB**, while the current database references **0** blob hashes
  (blob-bearing attrs: `:seon.agent.turn/prompt-blob`, `reply-blob`,
  `:seon.runtime.recovery/diagnostic-blob`). No dangling datom→blob refs
  (the defect direction is empty); the entire store is orphaned content
  from pre-reset database generations — 681 MB of unreclaimed garbage
  with no GC mechanism.

## 7. Cluster/process residue (tmp/, ports, sockets)

Method: `bin/seon status` vs `ps`/`lsof`; `find tmp -type s`; mtime sweep.

- **Pod crash mid-audit**: at session start `bin/seon status` reported
  `pod drained pid=45513` with the pid dead (see Headline). After
  `bin/seon restart`, watcher/writer/pod alive and the web UI serves.
- **Status disagreement**: `bin/seon status` persistently reports
  `watcher alive … not-ready` (and top-line "Seon degraded") while the
  Shadow endpoint is up, `:client` build compiles green, and the pod
  runtime is advertised. Observed both before the crash (old watcher) and
  after the restart (new watcher, 16+ minutes up). Either the readiness
  probe is wrong or "ready" means something status does not explain;
  status cannot currently be trusted to say the system is healthy.
- **`locks/` directory**: present (untracked) at session start with
  `cljs-watch database-server diffusion-server pod prep supervisor-stack`
  entries; vanished mid-session before the restart and did not reappear.
  Transient supervisor state is being created at the repo root and left
  behind on abnormal exit.
- **~120 dead UDS sockets in `tmp/`** from writer/transport test runs
  (Jul 10–19): `seon-writer-*-{publish,request}-<uuid>.sock`,
  `seon-interest-*`, `seon-query-admission-*`, `mesh-density-*`,
  `fork-cert-*`, `probe-*.sock`, `docker-bun-ready-test-*`. Only
  `seon-cluster-default-req.sock`, `tmp/seon-port`, and
  `tmp/seon-writer-repl-port-default` are live (MCP resolves the writer
  through the port file successfully). Tests do not clean their sockets.
- **tmp/ inventory**: 2,234 entries, 1,160 older than 14 days, including a
  280 MB `bun-pod-hot-reload.heapsnapshot` and probe artifacts back to
  March. `logs/` similarly holds ~700 files including per-scenario `.eval`
  artifacts and one-off probe logs.

## Needs-triage ranking

Genuine live defects (ranked):

1. **Dev/MCP REPL mistakes can crash the live pod** — dev-eval `:agent`
   scope does not cover at least the detached-async and some synchronous
   MCP eval funnels; with `:seon.config/on-core-error :crash` a typo'd
   probe exits the pod (proven live twice-over today: datoms 3689/3700,
   3767/3778, and 3857 which took the pod down at 13:40 EDT).
2. **Every dev/MCP eval's program-row persist is rejected**
   (`:seon.eval/record-eval` "tx FAILED: program row rejected", 27×,
   1:1 with REPL evals, all today) — eval history is silently not being
   recorded; error-level log is the only symptom.
3. **Fault datoms carry no Proximum branch head** (14/14) —
   `branch-head-now` never validates in the live pod; the recorded-fault →
   `cluster fork <t>` forensics chain is severed.
4. **Fault frames are constructor noise** (`file "new"` + cljs.core.js
   coords on every fault) — the queryable-frames design answers nothing.
5. **Runtime rehost/admission faults during hot reload** (datoms 3665,
   3682: "agent runtime rehost failed", "Verified program generation lost
   publication ownership") — real `:core` faults from live reload churn,
   untriaged.
6. **`bin/seon status` watcher not-ready / "degraded" while the system is
   demonstrably healthy** — health reporting cannot be trusted.
7. **`:seon.ai/complete` error-channel flood** — provider-failure test
   fixtures log at `:error` through the production path (~7,700 of 8,909
   lines are fixture-shaped); live triage requires timestamp forensics.
8. **Pure-data Malli spec defects logged on every reload** for the same
   core fns (`seon.instrument/coverage-gaps`, `seon.state/reconcile!`,
   `seon.render.canvas/error-response`, `seon.db/transact!`,
   `seon.eval/race-timeout`) — rows persist WITHOUT `:seon.fn/spec`.

Hygiene/garbage (not blocking, worth one sweep):

- 681 MB fully-orphaned blob store (no GC after cluster reset);
- ~120 dead test UDS sockets + 1,160 stale tmp/ files + 280 MB
  heapsnapshot; `locks/` transient dir left at repo root;
- `:c55.probe` leftover log source; `my.*` replay failures
  (``schema/register!` is not defined``) and 12× `seon.render.live-tile`
  self-schema failure in the retained log window (pre-reset vintage);
- `:seon.fn/created-at` / `:seon.schema/created-at` provenance-copy
  question (§4).

Today's-test-noise (no action): "boom" faults (10:14/10:15), blob-capture
"test capture omitted"/"no open database session" pairs (bin/test-cljs
runs at 10:13–13:47), malli-instrument `db/pull`-Promise faults from
lanes' probes (except their `:core` misclassification, item 1), and this
audit's own fault datom 3857.

## Verification state

- Probes 1, 3, 4, 6 ran on the live writer (read-only Datalog/pull over
  `(d/db conn)`); probe 5 on the live pod; probe 2 over the retained log
  files; probe 7 over the filesystem/process table.
- One unintended side effect: the pod crash described in the Headline,
  repaired with `bin/seon restart` and re-verified (web UI serving, both
  agents advertised, MCP eval green).
- `tmp/detector-pod-forms.edn` (schema-drift working file) is disposable.
