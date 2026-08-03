---
type: prd
status: active
tags: [prd, runtime, operator, boot]
---

# Operator integration: the control surface moves into the JVM

## Decision

The owner ruled (2026-08-03, conversational): **take the ergonomics, not the
machinery.** No Integrant, no clj-reload — both evaluated at source and
rejected on measured grounds
([operator-integration-integrant-2026-08-03.md](../sci-execution-runtime/research/operator-integration-integrant-2026-08-03.md);
[clj-reload-evaluation-2026-08-03.md](../sci-execution-runtime/research/clj-reload-evaluation-2026-08-03.md)).
This ruling supersedes the 2026-07-26 conditional-adoption note for Integrant:
its acceptance condition (deleting ~360 lines of host/writer/web scaffolding)
was met without Integrant, so the condition can never fire again.

What ships instead:

1. **`seon.operator`** — the in-JVM control surface: ordinary namespaced
   functions with namespaced request maps, thin delegations to the owners
   that already perform each operation, callable from the REPL, MCP, and
   (where policy permits) agents. This is the reloaded-workflow developer
   experience implemented on Seon's own mechanisms
   ([reloaded-ergonomics-sweep-2026-08-03.md](../sci-execution-runtime/research/reloaded-ergonomics-sweep-2026-08-03.md) §4).
2. **`bin/seon` becomes a thin foreign-process client.** It keeps only the
   irreducible outside layer — spawn/identify/log/kill a JVM that cannot do
   those things for itself, and whole-root destruction exactly when the
   database cannot be opened — and calls `seon.operator` for everything else
   instead of manufacturing Clojure control forms
   ([operator-integration-2026-08-03.md](../sci-execution-runtime/research/operator-integration-2026-08-03.md)).
3. **Stale-var detection as a query.** The one genuine weakness of
   var-indirect hot reload (a deleted `defn`'s Var lingering in the image)
   becomes a derived `seon.problems` finding over program-graph facts —
   never a reload framework.

## Authority and dependency ledger

Read before implementing; every claim below carries its source:

| Authority | What it settles |
|---|---|
| [operator-integration-2026-08-03.md](../sci-execution-runtime/research/operator-integration-2026-08-03.md) | The Option 1 target, the irreducible outside layer, the flock-stays-in-JVM custody answer, the migration rule and acceptance evidence. |
| [reloaded-ergonomics-sweep-2026-08-03.md](../sci-execution-runtime/research/reloaded-ergonomics-sweep-2026-08-03.md) | Verb-by-verb mapping of integrant-repl onto existing Seon functions; the component-pattern catalog; the deliberate divergences to keep; the nREPL deferral. |
| [clj-reload-evaluation-2026-08-03.md](../sci-execution-runtime/research/clj-reload-evaluation-2026-08-03.md) | Why no namespace-reload engine can run against the live runtime (273 host Vars in the shared SCI ctx; 138-of-162-namespace blast radius), and the stale-var-query replacement. |
| `src/seon/cluster.clj` (tower/stop/refork/readiness sections) | The functions every verb delegates to. The sweep's Q1 table names exact lines at its read; re-derive at implementation time. |
| `script/seon/fresh_operator.clj` | The Babashka operator being demoted to client; its reconciliation/fallback branches are the deletion inventory. |
| `resources/seon/operator/runtime.clj` | The process-root holder (running instances, store/flock custody, executors) — deliberately outside source roots and outside this PRD's edits. |

## The verb table

Each verb is one ordinary function in `seon.operator`, one-map-in/one-map-out
or fully named positional arguments, errors as flat `:seon.error` values,
readiness always derived, nothing stored. Delegates are the existing owners —
a verb that grows its own logic has failed this PRD's conversion test.

| Verb | Contract (one line) | Delegate |
|---|---|---|
| `start!` | Boot or add a named cluster in this JVM; REFUSES a double start (kept divergence — never implicit halt). | `seon.cluster/start!` |
| `stop!` | Stop one addressed cluster instance; joins launcher completion before branch release. | `seon.cluster/stop!` |
| `restart!` | `stop!` then `start!` of the same name — the honest "reset the world" (~17 ms refork path); never a namespace refresh. | both above |
| `status` | Derived readiness for this root: instances, database value, Flow ping observations; never a stored map. | `seon.cluster/readiness` + MCP's flow observation |
| `banner` | One human-oriented orientation string: clusters, ports, web URLs, published commit/digest. | derived from `status` |
| `clusters` | The recorded/advertised cluster census for this root. | registry + advertisements |
| `publish!` | Publish current source to `current-src` (complete, or `--changed`-shaped incremental). | the publication owner `bin/seon init` calls today |
| `refork!` | Destroy and refork a named cluster from the published commit (destructive; same confirmation posture as today). | `seon.cluster/refork!` |

**Deliberately absent**: `reload!` (var-level hot reload is already automatic
and needs no verb; the sweep's §4 anticipated omission and the clj-reload
verdict confirms it), `reset` (conflates three different operations and
`bin/seon reset --force` already names destruction), `go`/`prep`/`clear` (no
held config var exists to prep or clear), `suspend`/`resume` (Flow owns proc
lifecycle).

## Implementation order

1. **`seon.operator` slice.** The namespace with the eight verbs as thin
   delegations, complete Malli contracts, schema declarations in
   `resources/seon/schemas/seon.operator.edn`. Prove each verb from a bare
   prepl attach (`rlwrap nc` against the advertised port) — that attach flow,
   documented in the namespace docstring, IS the editor/terminal story for
   this slice; nREPL is explicitly deferred.
2. **`bin/seon` client conversion.** Replace each Babashka branch that
   manufactures control forms with one `seon.operator` call over prepl. The
   outside layer keeps: process spawn/identity/log capture, TERM/KILL
   recovery, offline flock proof, and whole-root destruction. The before/after
   inventory must NAME the deleted Babashka branches; relocation without
   deletion fails the simplification test.
3. **Stale-var problem query.** A derived `seon.problems` finding: Vars
   present in the loaded image whose `[namespace name]` no longer exists in
   the published program-graph facts. Computed, never a hand list; rendered
   through the ordinary problems surface so agents and humans both see it.
4. **Doc alignment.** `docs/seon/architecture/` operator/boot text, the
   AGENTS.md operator section, and retirement of the superseded conditional
   ruling — same wave, not a follow-up.

## Falsifiers and graduation

- The existing degraded-store proof still shows an answering REPL and a
  stoppable partial instance, unchanged.
- The existing active-pass proof still shows stop joining proc completion
  before connection release, unchanged.
- A terminal attach (`rlwrap nc`) performs `status` → `start!` (second
  cluster) → `publish!` → `restart!` → `stop!` end to end with only verb
  calls.
- `start!` on a running name refuses loudly; nothing halts implicitly.
- `bin/seon status` and `(seon.operator/status)` derive from the same
  functions and agree on the same root (modulo the vocabulary ruling below).
- The before/after operator source inventory names deleted Babashka lines;
  net operator-owned lifecycle code SHRINKS.
- No `integrant.core`, `clj-reload.core`, or namespace-refresh call appears
  anywhere in `src/`, `resources/`, or `script/`.

## Open owner decision

**Cross-boundary vocabulary** (flagged by the sweep): `bin/seon status` (a
foreign process reconciling records, advertisements, and a possibly-dead JVM)
and `(seon.operator/status)` (an in-JVM derivation) are different operations
that will share a name. Settle whether the client command and the in-JVM verb
keep one name with documented scope difference, or the client grows a
distinct word for reconciliation-from-outside. Until ruled, implementation
proceeds with one name and a docstring stating the scope difference.

## What not to build

- no Integrant, integrant-repl, clj-reload, or tools.namespace engine — the
  rejections are measured and recorded; overruling them requires new
  evidence, not preference;
- no second lifecycle vocabulary, component DAG, or system map beside the
  tower, Flow, and database facts;
- no stored status, health flag, or system-map-as-truth — readiness stays
  derived per call;
- no nREPL server in this PRD (deferred; later accretion adds an optional
  advertisement key if editor demand is real);
- no new counter/clock in any verb — refusals and joins ride the existing
  event-driven mechanisms; and
- no `seon.operator` logic that its delegate does not already own.

## Out of scope, named successors

- **Operational-event facts** (the logging story: Duct-style event keyword +
  data map as queryable facts beside the existing fault facts) — its own
  slice after this PRD, per the sweep's Q2 recommendation.
- **A scheduler** — the one component kind with no Seon owner; design waits
  for a real recurring-work need (background-work PRD may produce it).
- **Production jar / babashka launcher packaging** — the owner directed
  production deployment be used as a design guide only for now.
