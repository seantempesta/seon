---
type: design
status: proposed; awaiting Astra review; no source changed
created: 2026-09-27
branch: refactor/agent-platform
source-pin: ab4f796ef
---
# Config reads name their cluster — design (2026-09-27)

Lane `t4-b-config-scope-design-opus`. Defect: red-triage-4 platform item 1 (`ab4f796ef`,
`docs/research/agent-platform/red-triage-4-2026-09-27.md:27-45`). Design only; Astra reviews before any source edit.

**What exists.** `seon.config/effective db cluster-name` (config.clj:862-883) is the one owner: it pulls the cluster's
row by `[:seon.config/cluster cluster-name]`, takes each required dial's declared default when an older row lacks it,
and refuses when no row answers. About 25 callers already use it with the name from their cluster handle
(`(:seon.cluster/name cluster)`, turn.clj:3424, 5782) or from the environment (`:seon.boot/cluster-name`, web.clj:3073-3091).
Eighteen other places query dial attributes store-wide, or guess the cluster name with `:find ?name .`. On a branch
holding more than one cluster's row, each of those returns an arbitrary row's value.

**Approach.** Every dial read calls `config/effective` with the cluster name its caller already holds. Agent-facing
calls that omit the name get it by declared schema name from the environment, the same way they get `:seon.agent/id`
today. The ad-hoc queries are deleted. Nothing new is stored and there is no new reader function.

## 1. Every unscoped read (src, HEAD `ab4f796ef`)

The search covered queries on `:seon.config/cluster`, every `[?x :seon.config.*/…]` and `[_ :seon.config.*/…]` clause,
the `[_ ?a ?n]` form with a dial as `?a`, and cluster-name guesses (`[_ :seon.config/cluster ?name]`,
`[_ :seon.cluster/name ?name]`).

| # | Site | Dial / read | Defect | Where the caller's cluster name comes from |
|---|---|---|---|---|
| 1 | turn.clj:2887-2890 `max-episode-runs` | `run/max-episode-runs` (cluster fallback after issue budget and agent overlay) | any row; observed 100 for 3 | callers: `turns-left` :2896, `opening-deferred?` :2918, `episode-exhausted!` :5588 (holds `cluster`), and through `next-agent-work`'s request (production builds it at :6056 beside `cluster`) |
| 2 | turn.clj:69-73 `result-blob-threshold` (used by `stage-reply!` :85) | `eval.result/blob-threshold` | `_` also matches agent overlay rows | `stage-reply!` callers hold the cluster handle |
| 3 | turn.clj:4411-4416 `settings-ref` fallback | any config entity | any row | the enclosing function holds `cluster` (:4409) |
| 4 | turn.clj:4418-4421 threshold | `eval.result/blob-threshold` | `_`, any row | same `cluster` |
| 5 | repl.clj:70-73 `frame` | `run/max-episode-runs` | any row | `cluster.prompt/prompt` :406 (already has `cluster-name`, prompt.clj:62); transcript.clj:2100 |
| 6 | bootstrap.clj:130-134 `situation` | `run/max-episode-runs` | any row | `cluster.agent` :401 render unit; environment |
| 7 | issue.clj:1491-1492 `exhaust-tx` (a `:db.fn/call` tx function, :1518) | `message/max-chain` | any row | caller of :1518 passes the name as a tx-fn argument |
| 8 | cluster/message.clj:681-692 `send-call` (tx function, :746) | `message/max-chain` | **refuses every message** when two clusters' values differ | `send!` caller: environment; pass as a tx-fn argument |
| 9 | blob.clj:66-80 `binary-threshold` (`stage-binary!` :243) | `eval.result/blob-threshold` | `_`, any row | callers: effect.clj:518 (request), shell/jvm.clj:139, web/jvm.clj:203 (environment) |
| 10 | test/runner.clj:1253 `prepare-failures!` | `eval.result/blob-threshold` | `_`, any row | `:seon.test.run/cluster` in the run provenance (optional in seon.test.run.edn:43,119; see §6) |
| 11 | test/runner.clj:1274-1276 `admission-caps` | `eval.result/max-bytes`, `/max-source` via `[_ ?a ?n]` | any entity | same |
| 12 | render/transcript.clj:1169-1170 `turn-kind` | `ai/no-provider` | any row | the transcript render context's `:seon.cluster/name` (web.clj:722 builds it) |
| 13 | render/web.clj:2027-2034 `coalesce-floor` (:2693) | `render/coalesce-ms` | `_`, any row | `current-cluster-name` (web.clj:3073) from the service's environment |
| 14 | plan.clj:645-649 `query-deadline` (:713, :779, :825) | `eval/time-limit-ms` via `[?cluster :seon.cluster/config ?config]` | any cluster (not in the triage list) | `my.plan` is agent-facing: supplied from the environment |
| 15 | problems.clj:363-372 `missing-models` | `ai/model` | `_` scans every cluster and agent overlay | `problems` takes `[db _request]`: the request carries the cluster. Scan the named cluster's row plus overlays |
| 16 | agent.clj:131 `effective-settings`, :184 `render-settings-html` | cluster-name guess `[_ :seon.config/cluster ?name]` | any cluster | agent-facing: supplied; the render unit holds the name |
| 17 | sci/eval.clj:1319-1330 `database-effective-config` (:1340, :2762) | cluster-name guess | any cluster | the ctx's environment `:seon.boot/cluster-name` |
| 18 | cluster/agent.clj:279 `whoami` | `[_ :seon.cluster/name ?name]` | any cluster | agent-facing: supplied |

**Store-wide by design (unchanged):** cluster/registry.clj:564-566 reads every head's snapshot-window policy for
implicit collection, which is correctly store-wide. The writer's own reads in config.clj (:762-775 `apply-compiled!`
retention, :907 `available` in the refusal) are also unchanged.

## 2. The composition

- **One reader.** Replace each site with `(config/effective db cluster-name)`, then `get` the dial. Where an agent
  overlay applies (rows 1, 5, 6, 12, 14), use `(ai/settings (config/effective db cn) (ai/agent-overlay db agent-id))`,
  the precedence turn.clj:4334 already uses. Issue budget still wins first in row 1.
- **Refusals are values.** `effective` returns a `:seon.config/error` when the row is absent, where today's query
  returns nil and the caller reads it as "unbounded" (`opening-deferred?` nil → true; `send-call` throws). The
  refusal passes back through the declared `[:or … :seon.error/value]` alternatives these functions already declare.
- **Explicit name internally.** JVM-internal callers get no supplied defaults: call preparation fills only
  evaluated agent calls (call_preparation.clj:592-608). Each signature takes the name from its caller's handle.
  - `max-episode-runs`, `turns-left`, `opening-deferred?`: `[database cluster-name agent-id]`.
  - `next-agent-work`: reads `:seon.cluster/name` from its request map (open map, one added key).
  - Tx functions: `[:db.fn/call #'send-call candidate agent-id cluster-name]`; the same for `exhaust-tx`.
  - `blob/stage-binary!`: takes the threshold as an argument. Its three callers hold the environment. Blob storage
    has no reason to know clusters.
- **Supplied default for agent calls.** Supplied-default rows today (probed on default):
  `#{:seon.agent/id :seon.db/connection :my.program/context :seon.db/db}`. **There is no `:seon.boot/cluster-name`
  row.** Add one row to config/default.edn beside :539-543:
  `{:seon.call-preparation/key :seon.boot/cluster-name :seon.call-preparation/schema [:seon.schema/key :seon.boot/cluster-name] :seon.call-preparation/supplier [:seon.fn/sym seon.env/supplied-cluster-name]}`.
  Add a ~12-line `seon.env/supplied-cluster-name` that mirrors `supplied-agent-id` (env.clj:338-353). The environment
  schema requires `:seon.boot/cluster-name` (seon.env.edn:23-25), so the supplier refuses only a malformed
  environment. Agent-facing functions (`turns-left`, `effective-settings`, `whoami`, `my.plan` readers, and
  `config/effective` itself) then work when the agent omits the name. **Astra check:** every contract that
  declares a `:seon.boot/cluster-name` argument becomes suppliable; list them with `supplied-map-entries` and the
  arity plans before arming.
- **What disappears.** 18 ad-hoc reads, about 85 lines:

  | Site | Lines |
  |---|---|
  | turn.clj | 5 + 4 + 6 + 4 |
  | repl | 4 |
  | bootstrap | 4 |
  | issue | 2 |
  | message (with the distinct-count refusal) | 12 |
  | blob | 15 (with its bespoke threshold refusal) |
  | runner | 1 + 3 |
  | transcript | 2 |
  | web `coalesce-floor` | 8 |
  | plan | 4 |
  | problems | ~2 of the query |
  | agent.clj | 2 |
  | sci/eval guess arm | ~12 |
  | whoami | 1 |

  Added: the supplier (~12), one config row (5), and signature and argument threading (~25 lines, mostly one token
  per call). Net is negative. No new cache, reader, registry or schema attribute.

### Cost (measured, JVM mode, default at `ab4f796ef`, read-only)

| Read | Current db value, warm, µs per call (n=200) |
|---|---|
| ad-hoc `:find ?value .` (today) | 60 |
| scoped Datalog `:in $ ?n` | 65 |
| `pull [dial] [:seon.config/cluster n]` | 16 |
| `config/effective` (82 dials) | 135 |
| `config/effective`, first call this db value | 724 |

On an `as-of` value, the first `seon.db/pull` of any kind costs about 25 ms, and a later `effective` costs 0.55 ms.
That 25 ms is per database value in the pull path, not in `effective`; the ad-hoc query paid 0.57 ms there. The
Var is unarmed in JVM mode. `effective` is not cached in `seon.cache`. It recomputes a `pull [*]` plus merges each
call. The hottest converted read is `next-agent-work` → `opening-deferred?`, once per derivation per woken agent. At
135 µs, **no cache kind is justified**. Adding one would buy about 70 µs per derivation for a new cache key
`(commit-id, cluster-name)`. Revisit only if a profile ranks it.

## 3. Rejected and alternative compositions (priced)

1. **Constraint: one cluster row per branch.** Readers refuse when the branch holds more than one row, as
   message.clj does. It costs about 5 lines. It fails in practice: every fixture branch and every cluster forked
   from another holds the inherited row. Retracting the inherited row is refused because default's
   `:seon.cluster/config` is a required ref (red-triage-4:208-212). The price is either no fixtures on branches of
   default or a retired required ref. Not viable.
2. **Recommended: explicit name through `config/effective`, plus the environment supplier (§2).** It keeps the
   guarantee "each reader sees its own cluster's row" with no stored addition. Cost: about 3k tokens of edits across
   10 owners, signature changes on 3 turn.clj functions, and 2 tx-function arguments. It gives up nothing.
3. **Store agent membership as a fact:** `:seon.agent/cluster` ref, written by `cluster.agent/creation-tx`
   (agent.clj:217; the creation request already requires `:seon.cluster/name`, seon.agent.edn:89). Agent-scoped
   readers derive the cluster from `(db, agent-id)` and keep their signatures and tx functions. It costs a schema
   attribute, a creation-writer change, fixture `agent-row` helpers, and a reset of default: the ~30 live agents
   lack the fact. It does not cover rows 2, 9-11, 13 and 17, which need option 2 anyway. Only worth it if the owner
   wants cluster membership queryable. The triage's phrase "the agent row's cluster reaches its
   `:seon.cluster/config`" is **false today**: probed, root's agent row carries only `:seon.agent/branch`, `id`,
   `namespace`, `plan`, `runtime` and `settings`, and `:seon.agent/branch` is custody (`:cluster-default` for root,
   `:<name>` for each MCP agent), not membership.

## 4. Regression (one class test)

`config-read-scope-test`, in a new `test/seon/config_scope_test.clj` or in config_test (root decides ownership).
Use `test-support/with-database`. Through the canonical writer (`config/compile-manifest` `:seon.config/desired-row`,
as agent_test.clj:185-193 does), write two clusters' rows with **different** values for `run/max-episode-runs`,
`message/max-chain`, `eval.result/blob-threshold`, `eval/time-limit-ms`, `render/coalesce-ms` and `ai/no-provider`.
For each converted reader, called with each cluster name, assert it returns **that** cluster's value. Include
`send-call` with differing chain bounds: it must deliver, not refuse. Add a source-scan assertion that no `src` query
outside `seon.config` and `cluster/registry` joins a `:seon.config.*` dial without a bound cluster. Deleted-path
reds: agent-test `episode-cap-refusal-test` and turn-test `generated-opening-preserves-one-provider-turn-budget`
become green. Their requests or handles name the fixture cluster, which is the test update (their `request` gains
`:seon.cluster/name`).

## 5. Slicing (each commit loads)

- **Slice B1, the supplier.** env.clj `supplied-cluster-name` plus the config/default.edn row. It stands alone and
  enables agent-facing omission. Compile the call-preparation snapshot with its consumers.
- **Slice B2, the turn family, one commit.** turn.clj rows 1-4 (`max-episode-runs`, `turns-left`,
  `opening-deferred?`, `next-agent-work` request key, `result-blob-threshold` deleted, `stage-reply!` threshold
  argument, the :4409 site), plus every caller of the changed signatures in the same commit: issue.clj (:1068,
  :1488, `exhaust-tx` argument), render/transcript.clj (:1240 `turns-left`, :1169 `turn-kind`, :2100 `frame`),
  repl.clj, bootstrap.clj, cluster/prompt.clj :406, cluster/agent.clj :401, and the two tests above.
  turn.clj is hot and shared, so this needs a turn.clj ownership window; take the probe times of `next-agent-work`
  on the parent and on the change (AGENTS hot-path rule).
- **Slice B3, messaging.** cluster/message.clj `send-call` and its tx-call site. It is independent of B2 except that
  issue.clj's delivery passes the limit; B2 already reads the limit via `effective`.
- **Slice B4, leaves, file-disjoint.** Each of blob.clj plus its three callers (effect.clj, shell/jvm.clj,
  web/jvm.clj), render/web.clj, plan.clj, problems.clj, agent.clj, sci/eval.clj, cluster/agent.clj `whoami`, and
  test/runner.clj can go in parallel after B1.

## 6. Open questions for Astra and root

- `:seon.test.run/cluster` is optional in the run provenance. When it is absent, should `prepare-failures!` refuse,
  or read the tested branch's cluster name from somewhere else? Recommendation: make it required on completion. Its
  producer already knows the cluster.
- `problems` today reports models missing from **any** cluster's row. With scoping, a branch's problems report only
  its own cluster's models. That matches "Decide at the authority". Confirm no consumer relies on the store-wide
  scan.
- Does supplying `:seon.boot/cluster-name` change the arity plan of any existing agent-called contract that declares
  it (for example `config/effective`, `cluster/*` helpers)? This must be checked before B1 is armed.
