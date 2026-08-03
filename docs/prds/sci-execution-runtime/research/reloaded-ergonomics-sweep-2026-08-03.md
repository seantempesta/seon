---
type: research
status: active
tags: [research, operator, runtime]
---

# Reloaded-workflow ergonomics sweep (2026-08-03)

Follow-up to
[operator-integration-integrant-2026-08-03.md](operator-integration-integrant-2026-08-03.md),
whose verdict is "do not adopt Integrant" and whose owner amendment is "take
the ergonomics, not the machinery." This report asks what the reloaded-workflow
developer experience actually consists of, which parts Seon already owns, and
what the residual gaps are.

Sources read end to end: `reference-code/integrant-repl/src/integrant/repl.clj`
(141 lines), `.../repl/state.clj` (5 lines), its `README.md`, `deps.edn`, and
`project.clj`; the prior Integrant report; `src/seon/cluster.clj` boot/stop/
refork/readiness sections; `script/seon/fresh_operator.clj` command table;
`resources/seon/operator/runtime.clj`; `docs/seon/architecture/observability.md`.
Web sources are cited inline with URLs.

## 1. Integrant-REPL mechanics, and the Seon mechanism for each verb

### Correction to the brief's premise

Integrant-REPL 0.5.1 **does not use `clojure.tools.namespace`**. It uses
Tonsky's clj-reload: `reference-code/integrant-repl/src/integrant/repl.clj:5`
requires `clj-reload.core`, and `deps.edn:1-3` / `project.clj:6-7` pin
`io.github.tonsky/clj-reload 1.0.0`. The switch is a deliberate commit,
`c006f27 "Replace tools.namespace with clj-reload"` in the vendored history.
Both libraries are already vendored here (`reference-code/clj-reload`, pinned at
`61c6fa7 1.0.0`).

### Where the state lives

Three vars in one namespace marked `^:clj-reload/no-reload`
(`reference-code/integrant-repl/src/integrant/repl/state.clj:1-5`):
`config`, `system`, `preparer`. Every verb mutates them with
`alter-var-root`. That is the entire state model — process-local, invisible to
any other process, and not queryable.

Seon's equivalent holder is `resources/seon/operator/runtime.clj:11-15`:
`running-instances`, `root-store-holder`, `held-flocks`, all `defonce`. It gets
the same reload immunity structurally rather than by metadata — it lives under
`resources/`, which is on `:paths` (`deps.edn:8`) but is not one of Seon's
`source-roots` (`src/seon/cluster.clj:646-649`), so a source refresh never
touches it. Unlike Integrant's vars, Seon *additionally* publishes each live
instance as a file advertisement (`src/seon/cluster.clj:1690-1695`, written by
`write-advertisement!` at `:383`, called at `:1706`) whose validated shape is
`resources/seon/schemas/seon.boot.edn:1-13` — cluster name, prepl host/port,
pid, start-instant, optional web url/port. That is the part Integrant has no
analogue for: another process can discover the running system.

### Verb-by-verb

| Integrant-REPL verb | What it does (file:line) | Seon mechanism |
|---|---|---|
| `set-prep!` | `alter-var-root` on `state/preparer` with a 0-arg config-producing fn (`repl.clj:9-13`) | No analogue and none wanted: the manifest is selected per cluster and reconciled into database facts (`config/compile-manifest` at `src/seon/cluster.clj:1668-1670`), not held in a var |
| `prep` | calls the preparer, stores result in `state/config`, returns `:prepped` (`repl.clj:18-24`) | `seon.cluster/resolve-bootstrap` (`src/seon/cluster.clj:283`) + `config/compile-manifest`, both pure and both inside `start!` |
| `init` | halts any existing system, then `ig/init` in dependency order; result into `state/system` (`repl.clj:56-65`) | `seon.cluster/start!` (`src/seon/cluster.clj:1639`) — REPL layer first, then `stack-tower!` (`:1538`) stacks store → source commit → fork → connection → config, republishing the instance at every layer |
| `go` | `prep` then `init` (`repl.clj:67-73`) | `start!`; there is one call, because prep is not a separate held step |
| `halt` | `ig/halt!` in reverse dependency order, then nils `state/system` (`repl.clj:82-87`) | `seon.cluster/stop!` (`src/seon/cluster.clj:1846`) — reverse unwind, *instance-addressed not name-addressed*, idempotent, and it restores the exact instance to the registry if a release fails so the REPL stays up for diagnosis |
| `clear` | `halt` plus nils `state/config` (`repl.clj:75-80`) | `stop!` alone. There is no held config to clear — the config lives in database facts |
| `suspend` / `resume` | `ig/suspend!` / `ig/resume` — a second pause lifecycle (`repl.clj:89-106`) | core.async.flow's own `pause`/`resume` on graphs and procs (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:76-163`). Adopting Integrant's would be a second mechanism over the one Flow already owns |
| `reset` | `suspend` → `clj-reload/reload {}` (changed only) → `resume` (`repl.clj:112-118`) | **Mostly unnecessary.** Graph definitions reference transforms as vars, so re-evaluating a `defn` changes proc behavior with no stop/start. `reset` is needed only for a *topology* change, where it is `stop!` + `start!`, or for a corpus change, where it is `refresh-source!` + `refork!` |
| `reset-all` | as `reset` with `{:only :loaded}` (`repl.clj:120-125`) | `clj-reload/reload {:only :loaded}` would work identically if adopted; today the whole-JVM equivalent is `bin/seon down` then `start` |
| `set-reload-options!` | `clj-reload/init` with `:dirs`/`:files` (`repl.clj:127-141`) | `seon.cluster/source-roots` (`src/seon/cluster.clj:646-649`) already declares the roots that identify `current-src` — one authority, not a settable var |
| — | (no Integrant analogue) | `seon.cluster/refork!` (`src/seon/cluster.clj:1910`) — destroy the branch and refork the published commit while holding the root store open |
| — | (no Integrant analogue) | `seon.cluster/refresh-source!` (`src/seon/cluster.clj:923`) — publish the source tree onto `current-src`, incremental when safe |
| — | (no Integrant analogue) | `seon.cluster/readiness` (`src/seon/cluster.clj:1763`) — the banner *derived* from the instance and its database, including `seon.problems/problems` |

The shape of the finding: Seon already has every verb Integrant-REPL supplies,
under different names, split across `src/seon/cluster.clj` and
`script/seon/fresh_operator.clj`. What it lacks is not lifecycle machinery — it
is **one namespace where a person at a REPL can find them**. That is the whole
ergonomics gap, and §4 proposes the surface.

Notable divergences worth keeping:

- Integrant's `init` implicitly halts a running system first (`repl.clj:62-64`);
  Seon's `start!` **refuses** a second start for a cluster this JVM already
  runs (docstring, `src/seon/cluster.clj:1639-1657`). Refusing is the better
  behavior — the implicit halt is how a reloaded-workflow user loses a running
  system by typing `(go)` twice.
- Integrant's init failure carries the partial system in `ex-data` and
  integrant-repl halts it (`repl.clj:29-38`). Seon's `start!` also carries the
  degraded instance in `ex-data` under `:seon.boot/instance`, but deliberately
  does **not** unwind it: the REPL and advertisement survive so the failure can
  be diagnosed over the live socket (`src/seon/cluster.clj:1733-1745`).

## 2. Component pattern catalog

The recurring component kinds people write Integrant lifecycles for come from
Duct's module ecosystem (index: <https://github.com/duct-framework/duct/wiki/Modules>).

| Ecosystem pattern | What the module provides | Seon owner, or GAP |
|---|---|---|
| HTTP server — `:duct.server.http/jetty` (<https://github.com/duct-framework/server.http.jetty>) | init starts a Ring adapter from options + a `:handler` ref; halt stops it | `seon.render.web/start!` via the tower's last layer `serve!` (`src/seon/cluster.clj:1160-1213`); port derived from cluster name, explicit dial wins |
| Router/handler — `:duct.router/reitit` (<https://github.com/duct-framework/router.reitit>) | init builds a Reitit ring handler from `:routes`/`:middleware` | `seon.render.route/routes` + `router` (`src/seon/render/route.clj:5-33`), one static table, conflict-checked at namespace load |
| Connection pool — `:duct.database.sql/hikaricp` (<https://github.com/duct-framework/database.sql.hikaricp>) | init builds a pooled DataSource boundary record | `seon.cluster.store/open-branch!` plus the process-root store holder + lifetime flock (`resources/seon/operator/runtime.clj:13-15`; `src/seon/cluster.clj:401-456`). Seon's fence is stronger: process-root-wide, not per-component |
| Migrations — `:duct.migrator/ragtime` (<https://github.com/duct-framework/migrator.ragtime>) | init applies pending SQL migrations at boot under a `:strategy` | `accrete-schema-population!` (`src/seon/cluster.clj:560`) and `declaration-changes` (`:479`) — accretive schema reconcile, not ordered migration scripts. The `:rebase`/`:raise-error` strategy vocabulary has no Seon analogue and should not acquire one: accretion is the law |
| Meta-module — `:duct.module/sql`, `:duct.module/web` (<https://github.com/duct-framework/module.sql>, <https://github.com/duct-framework/module.web>) | one key expands into several component keys | `stack-tower!` (`src/seon/cluster.clj:1538`) is the fixed expansion, and it is a function, not a data expansion. No gap |
| Scheduler — `:duct.scheduler/simple` (<https://github.com/duct-framework/scheduler.simple>) | init builds a thread pool and schedules `:jobs` with `:delay`/`:interval`; explicitly not cron | **GAP.** No scheduler exists in `src/`; a search for `schedul|cron` finds only prose and Flow's own scheduling vocabulary. Note the standing law that a clock is a last-resort backstop, so any future timer component must be justified against an observable event first |
| Cache | Duct has no first-party cache; Redis via `:duct.database.redis/carmine` (<https://github.com/duct-framework/database.redis.carmine>) | Seon's caches are process-local `defonce` atoms with an explicit justification (e.g. `source-analysis-cache`, `src/seon/cluster.clj:657-661`). No component lifecycle is wanted |
| Queue / worker | third-party `dev.gethop.pubsub/{mqtt,amqp}` (<https://github.com/magnetcoop/pubsub>) | `seon.flow/start-work-launcher!` / `stop-work-launcher!` (`src/seon/flow.clj:418,449`) and per-agent mailbox procs (`src/seon/flow.clj:958`). Owned by Flow |
| Logging — `:duct.logger/timbre` + appender keys (<https://github.com/duct-framework/logger.timbre>) | init returns a `duct.logger/Logger`; appenders are separate keys | See below |

### Logging assessment

Seon currently ships timbre and slf4j-simple as dependency routing only
(`deps.edn:77,79`) and calls them **twice in the entire `src/` tree** — both in
`serve!`, one `log/warn` and one `log/info` about the web port
(`src/seon/cluster.clj:1205,1210`). The durable failure story is entirely
database facts: `:seon.error/fact` with identity, instant, process identity,
kind, message, content signature, bounded data projection, and optional
class/Flow/run/agent refs (`docs/seon/architecture/observability.md:129-146`),
committed through Flow's error channel and the fault committer
(`:145-146`). Recurrence is already a query over `/signature` (`:136-137`).

The ecosystem's best practice is worth taking, and it is narrower than "adopt a
logging library." Duct's protocol, read verbatim from
<https://github.com/duct-framework/logger/blob/master/src/duct/logger.clj>:

```clojure
(defprotocol Logger
  "Protocol for abstracting logging. Used by the log macro."
  (-log [logger level ns-str file line id event data]))
```

and the macro captures `*ns*`, `*file*`, `(:line (meta &form))`, a delayed
UUID, then an **event keyword plus a data map** — `(log/log logger :info
::starting-server {:port port})`, contrasted in that README against
`(log/info "Starting server on port" port)` with the justification that
keywords and data structures "produce logs that can be queried more efficiently
and consistently than logs written in English"
(<https://github.com/duct-framework/logger>).

That justification is Seon's own law arriving from outside, which is the
strongest possible corroboration: an operational log line is a fact with a
producer-owned namespaced kind and a data map, and it is valuable exactly to
the degree it is queryable. So the grounded position is:

- Seon's `:seon.error/fact` **already is** the Duct-style structured event for
  the failure half, with strictly more provenance (transaction, process,
  signature) than any appender chain provides.
- The genuine gap is the **non-failure operational event** — "the view bound on
  port N", "the branch forked from commit C", "recovery interrupted K
  receipts". Today those are either a timbre string (the two `serve!` calls) or
  a derived banner field (`readiness`, `src/seon/cluster.clj:1763-1806`).
- A Duct-shaped answer that respects Seon law would be **one function that
  commits a boot/operational event fact** with the same namespaced-kind
  discipline as `:seon.error/fact`, not a `Logger` protocol with pluggable
  appenders. A protocol with swappable backends is a second mechanism for
  something the database already owns.
- Where the pattern conflicts: Duct's appender configuration (`brief`,
  `println`, `spit` keys) is a per-destination component graph. Seon's
  per-cluster log file already exists at
  `data/clusters/<name>/logs/...` (`script/seon/fresh_operator.clj:78-80`,
  tailed by `logs!` at `:2426-2446`) and is stdio capture, not a configured
  appender. Keeping it that way is correct: a file that captures whatever the
  JVM printed cannot lie about the system, while a configured appender set is
  another thing to keep in sync.

Recommendation: do not adopt a logging library or protocol. Add the missing
operational-event fact under the existing `:seon.error/fact` discipline (a
sibling attribute family, not a second one), keep stdio capture as the
crash-and-stacktrace floor, and treat the two `serve!` timbre calls as the
first callers to convert.

## 3. REPL attach story

Seon serves exactly one endpoint per cluster: a `clojure.core.server` socket
server whose `:accept` is `seon.cluster/mcp-io-prepl`
(`src/seon/cluster.clj:1682-1688`), which is `clojure.core.server/io-prepl`
with a cluster-side `:valf` projector (`:209-216`).

Editor reality, each cited:

- **CIDER** — nREPL only, by explicit policy; its FAQ says supporting other
  REPL servers "is no longer a goal"
  (<https://docs.cider.mx/cider/faq.html>). Its maintainer prototyped prepl
  support, abandoned it, and shipped a separate minimal prepl client, **Port**
  (<https://github.com/clojure-emacs/port>). Emacs users can also reach a plain
  socket REPL through inf-clojure
  (<https://github.com/clojure-emacs/inf-clojure>).
- **Calva** — nREPL required; its docs describe nREPL as what "gives Calva a
  structured connection", with cider-nrepl for IDE features
  (<https://calva.io/connect/>).
- **Cursive** — supports both: the Remote REPL configuration lets you "select
  whether your REPL server is a socket REPL or an nREPL server"
  (<https://cursive-ide.com/userguide/repl.html>). Plain socket REPL, not prepl
  specifically.
- **nREPL does not bridge prepl.** Its own Alternatives page frames socket REPL
  and prepl as "pure REPLs" against nREPL as a tooling protocol
  (<https://nrepl.org/nrepl/alternatives.html>); no prepl support appears in
  its docs.

Cost of adding nREPL, grounded in the vendored source
(`reference-code/nrepl`, pinned `0e75a27`; cider-nrepl also vendored):
`nrepl.server/start-server` is a kwargs fn taking `:port` (0 autoselects),
`:bind`, `:socket`, `:handler`, `:transport-fn`, and returns a record handle
closable via `stop-server`, `.close`, or `with-open`, with the bound port in
its `:port` slot (`reference-code/nrepl/src/clojure/nrepl/server.clj:180-213`).
It is explicitly designed for embedding in an already-running application
(<https://nrepl.org/nrepl/usage/server.html>). Basic eval needs no middleware;
`cider-nrepl` and `refactor-nrepl` are optional and only buy IDE features.

Serving both is mechanically trivial — `clojure.core.server/start-server` is
already called with an explicit `:name` per cluster (`server-name`,
`src/seon/cluster.clj:353`), and an nREPL server is just another listener on
its own port. I found no single canonical "run both" walkthrough, only the
composition of two independently documented features; report that honestly
rather than as a blessed pattern.

**Recommended minimal path.** Do not add nREPL yet. The stated goal — "a person
attaches from their editor or terminal and calls `(reset!) (start!) (status)`"
— is satisfied for the *terminal* today by `rlwrap nc host port` against the
advertised prepl port (<https://lambdaisland.com/guides/clojure-repls/clojure-repls>)
once §4's verbs exist, and that is the change with real leverage. The verbs are
the missing piece; the transport is not. If and when an editor attach becomes a
real requirement:

1. add `nrepl/nrepl` and start a second listener inside `start!`'s layer 0,
   beside the prepl server, with the bound port added to the advertisement
   schema (`resources/seon/schemas/seon.boot.edn:1-13`) as an optional key —
   accretion, so no existing reader breaks;
2. carry no middleware initially; add `cider-nrepl` only when a specific
   editor feature is asked for;
3. keep prepl authoritative, because the MCP `:valf` projection
   (`src/seon/cluster.clj:198-216`) and the stored-value drill
   (`mcp-get-value`, `:218`) hang off it. Two transports must not become two
   *semantics*.

Conflict to name: an nREPL session is process-local mutable state that nothing
in the database records, so a second transport weakens "everything is
queryable" unless its advertisement carries the port. That is why item 1 puts
the port in the advertisement rather than in a `.nrepl-port` file.

## 4. Proposed `seon.operator` control verbs

Ordinary namespaced functions in one namespace, each a thin delegation to the
existing owner. No new lifecycle engine, no held config var, no preparer.
Every one is callable from the prepl, from MCP `eval_clj`, and from an agent,
because it is just a function.

| Verb | Contract | Delegates to |
|---|---|---|
| `seon.operator/start!` | Start one named cluster in this JVM and return its instance; refuse if this JVM already runs that cluster | `seon.cluster/start!` (`src/seon/cluster.clj:1639`) |
| `seon.operator/stop!` | Stop the addressed instance, reverse-unwinding the tower; idempotent | `seon.cluster/stop!` (`:1846`) |
| `seon.operator/restart!` | `stop!` then `start!` the same cluster name — the topology-change verb, since a `defn` edit needs neither | the two above |
| `seon.operator/status` | Ordinary data for every cluster this JVM runs: name, pid, prepl port, web url, agent count, current problems | `seon.cluster/readiness` (`:1763`) over `running-instances` (`resources/seon/operator/runtime.clj:11`) |
| `seon.operator/banner` | `status` rendered as the block a person reads at a terminal | `seon.cluster/banner` (`:1808`) |
| `seon.operator/clusters` | The roster of branches in the process-root store, live or dormant | `seon.cluster.registry/roster` (`src/seon/cluster/registry.clj:104`) |
| `seon.operator/publish!` | Publish the current source tree onto `current-src`; incremental with paths, complete without | `seon.cluster/refresh-source!` (`:923`) |
| `seon.operator/refork!` | Destroy this cluster's branch and refork the published commit — the destructive reset | `seon.cluster/refork!` (`:1910`) |
| `seon.operator/reload!` | Reload changed first-party namespaces in this JVM, returning what was reloaded | `clj-reload.core/reload` (`reference-code/clj-reload/src/clj_reload/core.clj:334-357`) if adopted; otherwise omit the verb rather than hand-roll one |

Design notes and the laws they respect:

- **No `go`, no `prep`, no `clear`.** `go` exists in Integrant only because
  `prep` is a separate held step; Seon's config is a compiled manifest
  reconciled into facts, so `start!` is the whole of it. `clear` clears a var
  Seon does not have.
- **No `suspend`/`resume`.** Flow owns pause/resume
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:76-163`);
  a second pause verb at the operator layer would be a second mechanism.
- **`reset` is deliberately absent as a name.** The reloaded workflow's `reset`
  conflates three different Seon operations — var reload (free), topology
  change (`restart!`), and corpus change (`publish!` + `refork!`). Naming one
  verb `reset` would hide which one happened, and `bin/seon reset --force`
  already means the destructive root-wide operation
  (`script/seon/fresh_operator.clj:2494`). Keeping the words distinct is the
  point.
- **`status` returns data, `banner` returns the string.** `readiness` is
  already derived from the instance and its database rather than assembled from
  boot-time variables (`src/seon/cluster.clj:1763-1772`), so the verb cannot
  claim something the system does not.
- **This namespace must own nothing.** If a verb needs state, the state belongs
  in `resources/seon/operator/runtime.clj` or the database. A `seon.operator`
  that acquires its own atom has become the second lifecycle engine this report
  exists to avoid.

### Open question for the owner

`bin/seon` (`script/seon/fresh_operator.clj:2478-2503`) and this proposed
namespace would name overlapping operations from two sides of the process
boundary — the CLI reconciles process records and advertisements across JVMs
(`reconciled-truth!`, `:1289`), while `seon.operator` acts inside one JVM.
That is a real distinction, not duplication, but the vocabulary should be
settled deliberately before both surfaces exist, or `bin/seon status` and
`(seon.operator/status)` will drift into meaning different things.
