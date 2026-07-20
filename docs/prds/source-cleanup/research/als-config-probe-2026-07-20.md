---
type: research
status: complete
tags: [research, database, architecture]
---

# ALS per-operation config probe (2026-07-20)

Executable derisk for the config-through-aero PRD's async-context question
([[../config-through-aero]]). Owner design ruling under test: configuration
stops being a once-at-boot `enterWith` snapshot and joins the SAME
per-operation context entry the identity/provenance ambient already uses —
each operation boundary acquires one database value and enters
{identity + config-at-that-basis} together; descendants inherit; the next
operation acquires anew; no live-context mutation is ever needed.

## Verdicts

- **(a) Refresh-in-place is NOT viable.** Two pre-existing independent async
  fibers never observe a later `enterWith` replacement performed from another
  context. The aero research's option (b) ("refresh the installed context
  from committed-transaction delivery") is dead — falsified by execution on
  both Bun 1.3.14 and Node v26.4.0, identical output.
- **(b) Per-operation entry is sound.** `als.run` at the boundary is
  inherited by every descendant: awaited chains, `.then` chains, nested
  async functions, and `setTimeout` continuations. Boundary `enterWith` also
  reaches all descendants but additionally leaks into the caller's
  synchronous frame and its post-await continuation — one more reason the
  design uses only `.run` (Seon's `run-with-tx-context`).
- **(c) `read-resource-options` works unchanged**, proven live in the
  running pod with zero code edits: a query inside
  `db/with-tx-context {:seon.config/configuration <singleton>}` observed the
  entered ceiling; a query with no operation context rode
  `config/default-database-query-policy`. The boot `enterWith` snapshot
  never even reached the MCP REPL fiber — operator probes have been on the
  default-policy fallback all along, so per-operation entry removes a
  snapshot most fibers never saw.

## Existing mechanism (read before the probe)

- `src/seon/db/internal.cljs:16-18` — the tx-context `AsyncLocalStorage`.
- `src/seon/db/internal.cljs:58-61` — `run-with-tx-context` =
  `(.run tx-context (merge (current-tx-context) context) f)`. The **merge**
  means any nested `with-tx-context` (e.g. typeahead's `{::db/db db}`)
  automatically carries an outer boundary's configuration forward.
- `src/seon/db/internal.cljs:63-67` — `enter-tx-context!` (`.enterWith`),
  whose sole production caller is `db/install-configuration-context!`
  (`db.cljs:702-706`), called once at boot (`client.cljs:2190`).
- `src/seon/db.cljs:745-766` — `read-resource-options` reads
  `(:seon.config/configuration (internal/current-tx-context))`; absent →
  `config/default-database-query-policy` / `default-database-pull-policy`
  (`config.cljs:810-820`).
- **Existing per-operation precedent**: `execution/runtime.cljs:589-604`
  (`eval-batch!`) already enters `:seon.config/configuration` through
  `with-tx-context` plus `error/with-configuration` — the validated design
  is this exact idiom applied at every operation boundary.

## Probe code and observed output (verbatim)

Scratchpad `als-probe.cjs` (pod-representative form: CJS require, all work
inside async functions — the shadow-cljs bundle shape):

```js
const { AsyncLocalStorage } = require('node:async_hooks');
const als = new AsyncLocalStorage();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const read = () => als.getStore()?.v ?? null;

async function fiber(name, iters, delay) {
  const readings = [];
  for (let i = 0; i < iters; i++) { await sleep(delay); readings.push(read()); }
  return { name, readings };
}

async function main() {
  als.enterWith({ v: 'boot' });                  // boot install analog

  const f1 = fiber('fiber-1', 6, 20);
  const f2 = fiber('fiber-2', 6, 20);
  setTimeout(() => {                             // committed-delivery analog
    als.enterWith({ v: 'refreshed' });
    console.log('(a) refresher fiber after its own enterWith:', read());
  }, 55);
  const a = await Promise.all([f1, f2]);
  console.log('(a) fiber-1:', JSON.stringify(a[0].readings));
  console.log('(a) fiber-2:', JSON.stringify(a[1].readings));
  console.log('(a) main continuation after refresh:', read());

  const b = await als.run({ v: 'op-7' }, async () => {
    const direct = read();
    await sleep(10);
    const afterAwait = read();
    const timeoutHop = await new Promise((res) => setTimeout(() => res(read()), 10));
    const nested = await (async () => { await sleep(5); return read(); })();
    const chained = await sleep(5).then(() => read());
    return { direct, afterAwait, timeoutHop, nested, chained };
  });
  console.log('(b) inside als.run op:', JSON.stringify(b));
  console.log('(b) caller after run:', read());

  await (async function op() {
    als.enterWith({ v: 'op-enterWith' });
    await sleep(5);
    const afterAwait = read();
    const timeoutHop = await new Promise((res) => setTimeout(() => res(read()), 5));
    console.log('(b2) enterWith-at-boundary descendants:', JSON.stringify({ afterAwait, timeoutHop }));
  })();
  console.log('(b2) caller continuation after enterWith op:', read());
}
main().then(() => console.log('probe done'));

```

Output — **byte-identical under `bun 1.3.14` and `node v26.4.0`**:

```text
(a) refresher fiber after its own enterWith: refreshed
(a) fiber-1: ["boot","boot","boot","boot","boot","boot"]
(a) fiber-2: ["boot","boot","boot","boot","boot","boot"]
(a) main continuation after refresh: boot
(b) inside als.run op: {"direct":"op-7","afterAwait":"op-7","timeoutHop":"op-7","nested":"op-7","chained":"op-7"}
(b) caller after run: boot
(b2) enterWith-at-boundary descendants: {"afterAwait":"op-enterWith","timeoutHop":"op-enterWith"}
(b2) caller continuation after enterWith op: op-enterWith
probe done

```

Reading: the refresh `enterWith` was visible only to the refresher fiber
itself; both pre-existing fibers and the main continuation kept `boot`
through and after the refresh. Per-operation `run` was seen by every
descendant hop and did not leak to the caller. Boundary `enterWith` covered
descendants but leaked into the caller's continuation.

### Bun surprise: `enterWith` segfault (ESM top-level continuation)

The first probe run (`als-probe.mjs`, ESM with top-level `await`) crashed
Bun 1.3.14 with `panic(main thread): Segmentation fault` — "This indicates a
bug in Bun, not your code". Minimal repro (crashes deterministically):

```js
import { AsyncLocalStorage } from 'node:async_hooks';
const als = new AsyncLocalStorage();
als.enterWith({ v: 'boot' });
setTimeout(() => { als.enterWith({ v: 'refreshed' }); }, 20);
await new Promise((r) => setTimeout(r, 60));   // <- segfault resuming this

```

Also crashes: `enterWith` after a top-level `await`. Does NOT crash: the
same `enterWith` inside an ordinary async function (CJS or ESM), or `.run`
anywhere — so the pod bundle shape is unaffected today. Recorded as
[[../../../seon/issues/bun-enterwith-toplevel-segfault]]. The validated
design deletes Seon's only `enterWith` caller, removing the exposed class
entirely.

## Live pod proof (task 3)

MCP `eval_cljs` against the running default-cluster pod, no source edits:

1. `(some? (:seon.config/configuration (seon.db/current-tx-context)))` →
   `false` — the boot `enterWith` install does not reach the nREPL fiber;
   this fiber's queries already ride the default policies.
2. A `[:find ?e ?a ?v :where [?e ?a ?v]]` query with no operation context
   and explicit `:seon.db/max-results 5` → budget error
   `:datahike.budget/allowed 5` (explicit request option wins, fallback path
   healthy).
3. The same query inside
   `(seon.db/with-tx-context {:seon.config/configuration tight} …)` where
   `tight` = the acquired, `decode-edn-values`-decoded singleton with
   `:seon.config.database.query/max-results 3` → budget error
   `:datahike.budget/allowed 3`. **Per-operation configuration reached
   `read-resource-options` and governed the ceiling with today's code.**
4. Instrumentation guard observed: passing a *partial* map
   (`{:seon.config.database.query/max-work 1}`) as the configuration fails
   `seon.config/database-query-policy`'s `:seon.config/singleton` input
   schema (`:malli.core/missing-key :seon.config/id`). Boundaries must
   enter the full decoded singleton, never a fragment.

## Operation-boundary inventory

Every current `with-tx-context`/`with-agent` operation owner, whether a
fresh database value is already acquired there, and the exact change.
Nested scopes (merge semantics carry config from the outer boundary) need
no change and are listed separately.

| # | Boundary | File:line | Fresh db acquired? | Change |
|---|---|---|---|---|
| 1 | Turn open (prompted) | `agent/turn.cljs:963-976` (`with-agent` + `with-tx-context` user/process) | yes — prompt acquisition; `:seon.db/db database` rides the `open-turn!` input | add `:seon.config/configuration` (decoded singleton from the same acquired value; the prompt path already resolves config) |
| 2 | Scheduled turn | `agent/loop.cljs:1078-1090` | yes — `acquire-agent-state` `::db/db` | add config; `schedule.cljs:350-376` `acquire-schedule-facts` already pulls the stored configuration at the same value |
| 3 | Wake/renew/re-drive | `agent/loop.cljs:706-719` (`with-agent-repl`) | no — the drive loop acquires per iteration (`acquire-loop-state`) | enter config where the loop-state projection is acquired; the beat scope `loop.cljs:421-423` already enters `{::db/db projection}` — add config from the same projection |
| 4 | Retired-child recovery | `agent/loop.cljs:349-362` | recovery acquires internally | add config to the entered context |
| 5 | Ticker pass | `agent/loop.cljs:1225-1241` (`install-ticker!` captures **boot** configuration; `run-tick!` :1201-1223 reuses it every tick) | tick work acquires per pass | acquire config per tick instead of the boot capture — this is a second boot snapshot to delete |
| 6 | Web request | `web/serve.cljs:384` (POST /agents, `with-agent` only); GET handlers acquire per-request values (`serve.cljs:820-840`, `1105-1125`) but never enter tx-context | yes per handler | enter `{config}` at the request boundary from the request's acquired database value (one wrap at dispatch), so handler queries stop riding the ambient/default ceilings |
| 7 | Execution child invocation | `execution.cljs:944-961` (`with-read-evidence` → `with-agent` → `with-tx-context` run-fence + pinned db) | pinned `:seon.db/db` in the invocation | add config to the invocation context (`prepare-eval-program!` already carries configuration) |
| 8 | eval-batch! | `execution/runtime.cljs:589-604` | yes | **none — already enters `:seon.config/configuration` + `error/with-configuration`; the precedent** |
| 9 | Boot reconcile/recovery/initial-agent/restore | `client.cljs:1944`, `2175`, `2194`, `2237` | `selected-configuration` in hand at `client.cljs:2188` | add `:seon.config/configuration` to those context maps; **delete** the `install-configuration-context!` call at `client.cljs:2190` |
| 10 | Shadow build handlers | `client.cljs:637-680` | `acquire-configuration!` per event | already per-operation via `error/with-configuration`; add tx-context config around `open-database-session!` work |

Deletions once every boundary enters config:

- `db.cljs:702-706` `install-configuration-context!` (and its call,
  `client.cljs:2190`);
- `internal.cljs:63-67` `enter-tx-context!` — sole caller gone; `seon.db`
  then contains no `enterWith` at all (sidesteps the Bun bug class and the
  caller-frame leak).

Nested/consumer sites requiring **no change** (merge inheritance or plain
reads): `turn.cljs:469-471` (`::current-id`), `ai/typeahead.cljs:824-827`,
`my/kb/shared.cljs:64`, the `agent/ctx/*` renderers, `ai.cljs:857`,
`execution/runtime.cljs:430,583`, `instrument.cljc:143-149` (the
`::context-only` configuration resolver reads the same key and is source-
agnostic), and `db.cljs:745-766` itself.

Inventory size: 10 operation boundaries; 8 require a change; 1 is already
the target idiom; plus 2 deletions.

## Resulting implementation spec

1. At each boundary in the table, extend the existing `with-tx-context` map
   with `:seon.config/configuration <full decoded singleton acquired at that
   boundary's database value>`. Most boundaries already hold both the value
   and the singleton (turn prompt resolution, schedule facts, execution
   prepare, boot); the web request boundary adds one `db/entity` +
   `decode-edn-values` per request against its already-acquired value.
2. Keep `error/with-configuration` as-is (separate error-scope ALS, already
   per-operation); where a boundary newly acquires config it should wrap
   both, exactly as `eval-batch!` does today.
3. Delete `install-configuration-context!` and `enter-tx-context!`.
4. `read-resource-options`, `instrument.cljc`'s resolver, and every nested
   `with-tx-context` are untouched — proven live above.
5. Acceptance: transact a new `:seon.config.database.query/max-work`, then
   observe the next operation's query ceiling change with no restart (the
   per-operation acquisition delivers it); operator probes with no boundary
   still succeed under the default policies.

## Risks

- **Full-singleton requirement**: a partial configuration map fails the
  `:seon.config/singleton` instrumentation (observed live). Boundaries must
  pass the decoded entity, never a hand-built fragment.
- **Per-operation acquisition cost**: one `db/entity` read per boundary that
  does not already hold the singleton (web requests, ticker). All others
  already acquire it; no new caching layer is warranted.
- **Bun `enterWith` segfault** (issue above): currently unexposed by the
  pod bundle shape, and the design removes Seon's only `enterWith`; any
  future `enterWith` reintroduction must retest the minimal repro.
- **Boundary completeness**: a fiber with no boundary rides the generous
  default policies. That is the intended fallback (early boot, operator
  probes — already true today); it is not silent misconfiguration because
  the defaults exist only to stop runaway work.
