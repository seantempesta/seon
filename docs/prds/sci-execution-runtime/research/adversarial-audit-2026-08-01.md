---
type: research
status: active
tags: [research, sci, repl, database, audit]
---

# Adversarial audit of the 2026-08-01 landing tree

## Verdict

The landing wave contains substantial sound work, but it is not a trustworthy
graduation tree yet. Two defects invalidate safety or recurring proof at the
current boundary:

1. agent evaluation can call the compiled store owner with the ambient live
   connection and commit arbitrary same-cluster facts; and
2. the 88-row REPL-parity gate can silently lose a row and remain green.

Seven friction-class finding groups and one cleanup defect were independently
confirmed in the changed owners. Three of those groups reproduce previously
filed findings: blob GC ignores history, the render page-size attribute has two
defaults, and pod-era readers remain after deletion.

This audit trusts neither landing summaries nor lane counts. Every behavioral
claim below comes from current source plus a fresh JVM or read-only entry-point
probe. No default cluster was mutated. The current default process was used
only by `bin/seon status`; all evaluator/database probes ran in fresh
`clojure -M:dev:test` JVMs with disposable databases or stores.

## Scope and dependency ledger

The inspected wave includes the six caps/blob commits `eed7cf53f`,
`ebfaa4900`, `f5d6d79a9`, `be37aac87`, `e4e576de1`, `67190f050`; parity commits
`b1cd16a0c`, `c4c6859aa`, `b41a90117`, `d112e299d` plus their cited harness
commits; database commits `5599d72b2`, `6b5acdcce`; deletion `98e6ab2a2`; and
MCP/door commits including `c6db32f56` and `d69708a2c`.

The exact dependency owners used by the probes were:

- Clojure 1.12.5 from `deps.edn:15`; vendored source
  `reference-code/clojure@b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`.
- Datahike from `reference-code/datahike` (`deps.edn:26-30`) at
  `9b3be9d59cb0`.
- Konserve from the Git dependency pinned by `deps.edn:31-37` at
  `b5c99bc02a71`. The vendored checkout was already
  dirty at `737697d`; it was not used or touched by this audit.
- SCI from `reference-code/sci` (`deps.edn:46-49`) at
  `937d392a008e`.
- Edamame source at
  `38e627467daa`.
- Malli runtime 0.20.0 (`deps.edn:16`); the vendored source is ahead at
  `80138076960e`, an already-filed dependency
  drift.

The decisive reusable probes live under `tmp/audit-20260801b/src/`.

## Ranked findings

### Blocker 1 — agent eval has unrestricted same-cluster write authority

This is not merely a missing friendly write facade. `acquire!` installs every
loaded core-provenanced first-party namespace as its real compiled Vars
(`src/seon/sci/eval.clj:655-679,726-748`). The render/eval owner binds the live
branch connection to public `seon.db/*conn*`
(`src/seon/render.clj:90-110`; `src/seon/db.clj:10-12`). Public
`seon.cluster.store/transact!` accepts arbitrary transaction data and calls
Datahike directly (`src/seon/cluster/store.clj:427-466`). No effect request,
schema-ownership check, transaction-function constraint, or provenance fence
intervenes.

`tmp/audit-20260801b/src/agent_write_probe.clj` ran this form through an
acquired production fork and the normal evaluator:

```clojure
(do
  (seon.cluster.store/transact!
   (deref seon.db/*conn*)
   [{:seon.cluster.agent/id "audit-illicit-agent"}])
  :audit/committed)
```

The evaluation returned no error and a subsequent Datahike pull returned
`#:seon.cluster.agent{:id "audit-illicit-agent"}`. The blast radius is the
current cluster's live branch rather than sibling branches or the physical
store owner, but inside that branch it includes any installed attribute:
agents, config, messages, runs, receipts, error facts, and program-graph rows.
An agent can assert or retract them without the one effect owner. This confirms
`docs/seon/issues/unlogged-findings-2026-08-01.md` item 2.

### Blocker 2 — parity cardinality is an unaudited printout

The parity execution path itself is real: it derives effective config, forks
and acquires the program, and calls `sci.eval/evaluate`
(`test/seon/repl_parity_test.clj:23-65`). Row identities and known-divergence
state are metadata emitted by `defparity` (`:112-120`); executable discovery is
derived from `ns-interns` (`:163-167`), not a separate known-divergence list.
`check-row!` correctly requires a known divergence to continue failing
(`:89-110`).

The inventory is exactly:

| Family | Rows |
|---|---:|
| A | 10 |
| B | 11 |
| C | 8 |
| D | 11 |
| E | 16 |
| F | 6 |
| G | 10 |
| H | 8 |
| I | 8 |
| Total | 88 |

There are 69 executable rows: 34 known divergences and 35 passing rows. There
are 19 explicitly pending rows. This independently reproduces the corrected
88-row count and rejects the historical 59-row undercount.

The fixture only prints the derived known and pending counts after execution
(`test/seon/repl_parity_test.clj:179-191`). The parity probe proved both arms:
a simulated known divergence that passed incremented `:fail` to one, while
temporarily removing one row's metadata and running the report fixture left
`{:fail 0 :error 0}`. A deleted executable or pending row is absence of signal,
which the gate currently reads as health. Filed as
`docs/seon/issues/parity-gate-has-no-row-cardinality-sentinel.md`.

### Friction 1 — GC protects current blob references but destroys history

`seon.blob` stores content-addressed bytes in the Datahike connection's own
Konserve store and verifies the digest on read (`src/seon/blob.clj:11-53`).
`collect!` derives a union across roster branches and extends Datahike's
Konserve reachable set (`src/seon/cluster/registry.clj:286-330`). That union is
real and protects the one current result-blob attribute.

The real file-store probe wrote two blobs, asserted both result-blob datoms,
then retracted one reference. Before GC:

- the current query contained only digest `d4ec…e955`;
- `d/history` and the earlier `d/as-of` value contained both `d4ec…e955` and
  `5966…0fd`.

After `registry/collect!`, the current blob remained readable, one object was
swept, and the history-only blob was gone. The reachability owner reads only
the current database and names `:seon.cluster.eval/result-blob` literally
(`src/seon/cluster/registry.clj:286-303`). This confirms the existing
`docs/seon/issues/blob-reachability-names-one-attribute-by-hand.md`; it will
break time travel as soon as a blob reference is superseded.

### Friction 2 — the Inst hotspot optimization changed overlapping semantics

The common Date and Instant fast paths are sound, but every structural
collection now wins before the fallback protocol lookup
(`src/seon/sci/admit.clj:233-238,281-294`). The test covers only a leaf-like
reified Inst (`test/seon/sci/admit_test.clj:247-257`).

The probe constructed an object satisfying both `clojure.core/Inst` and
`java.util.Collection`. Admission reported both predicates true and projected
`[1 2]`; before the ordering change, the generic Inst arm normalized it to a
Date at 43 ms. Filed as
`docs/seon/issues/admit-inst-overlap-prefers-collection-shape.md`.

### Friction 3 — MCP structured messages are not generally bounded

The ordinary value path is good: event `:val` fields are capped, event count is
bounded while the terminal event is retained, and structured JSON trims the
largest values first (`script/seon/dev/mcp.clj:46-121,485-573`). The focused
MCP suite proves those common shapes.

The general claim is false because both bounding passes can trim only `:val`.
With a 20,000-character terminal `:form` and requested output 128 tokens, the
probe measured a 512-character estimate and a 20,150-character encoded
response. Filed as
`docs/seon/issues/mcp-structured-output-only-bounds-event-value.md`.

### Friction 4 — MCP first-party frames are a hand list and drop agent code

`first-party-frame?` is four string prefixes
(`script/seon/dev/mcp.clj:497-503`). The test enumerates precisely those
spelling classes (`test/seon/dev/mcp_bridge_test.clj:118-153`). A synthetic
trace containing `my.agents.audit$explode`, `seon.audit$explode`, and
`user$eval42` retained the latter two and dropped the authored agent frame.
Filed as
`docs/seon/issues/mcp-first-party-frame-hand-list-drops-agent-namespaces.md`.

### Friction 5 — the contract printer is bounded only when optional caps exist

The caps-supplied production path uses the one admission printer and is
bounded. However, the public request schema makes caps optional
(`resources/seon/schema/instrument.edn:13-16`) and `violation` uses raw `pr-str`
for the humanized problems when they are absent
(`src/seon/instrument.clj:156-188`). Tests check no-caps argument omission but
measure headline size only with caps (`test/seon/instrument_test.clj:93-145`).

The no-caps probe produced a 5,002-character headline from 200 closed-map
problems. The arguments were correctly omitted and the full problem count was
preserved, but the docstring's bounded-value claim is not total. Filed as
`docs/seon/issues/instrumentation-headline-unbounded-when-caps-absent.md`.

### Friction 6 — deletion did not chase all live readers

The fresh live entry points survived commit `98e6ab2a2`: every current dev
namespace loaded under Babashka, `bin/seon status` returned the roster, and
`printf '{}' | bin/seon-hook` returned `{"continue":true}`.

Several tracked consumers still name the deleted world:

- `shadow-cljs.edn:63,100,131` invokes deleted
  `seon.dev.program-artifact/publish-inventory!`.
- `docker/seon-entrypoint:101-123` launches the deleted Bun pod and defaults to
  deleted `config/system.edn`.
- `bin/acme:11-16,98-130` advertises and delegates the deleted command language.
  Read-only `bin/acme status --edn` failed because fresh `status` takes no
  arguments; bare `bin/acme status` merely printed the global roster.
- MCP reconstructs a pod-era writer endpoint when the authoritative fresh
  advertisement is absent (`script/seon/dev/mcp.clj:275-298,321-343`).

Root Shadow/Docker rot is already recorded in
`docs/seon/issues/unlogged-findings-2026-08-01.md` item 6. The distinct broken
ACME interface and stale MCP fallback are filed in
`docs/seon/issues/acme-wrapper-speaks-deleted-operator-command-language.md` and
`docs/seon/issues/mcp-old-writer-port-fallback-survives-pod-deletion.md`.

### Friction 7 — render page size still has two defaults owners

The admission caps and blob threshold each have one shipped values owner in
`config/default.edn:14-33`; the schema resources declare shapes only. The
exception is `:seon.render.value/max-collection`: config ships 8 at
`config/default.edn:35-39`, while its registration separately carries
`:seon.render.value/default 8` at
`resources/seon/schema/render_value.edn:1-13`. Changing one does not change the
other. This confirms `docs/seon/issues/unlogged-findings-2026-08-01.md` item 7.

### Cleanup 1 — MCP polls an observable parent-exit event

`start-parent-watchdog!` sleeps 5,000 ms and repeatedly resolves/polls the
parent PID (`script/seon/dev/mcp.clj:763-781`). The fresh operator already uses
`ProcessHandle.onExit` (`script/seon/fresh_operator.clj:1168-1171,1226,1727`).
The clock has no test or external-state justification. Filed as
`docs/seon/issues/mcp-parent-watchdog-polls-processhandle.md`.

## Required surface-by-surface conclusions

### Caps, blob settlement, and transcript floor

- The four admission caps and blob threshold changed at the single config
  values owner (`config/default.edn:14-33`) and match ruling #25's documented
  measured knees.
- Settlement records the full serialized size, moves the full result into a
  content-addressed blob above threshold, and keeps a bounded inline window
  (`src/seon/cluster/loop.cljc:295-316`).
- Receipt schema stores `result-edn`, `result-blob`, and `result-size`, not
  capped state (`resources/seon/schema/run.edn:25-45`).
- Transcript derives capped state from `result-size > count(result-edn)` at
  render time (`src/seon/render/transcript.clj:222-248`). The in-memory
  `:seon.sci.admit/capped?` evaluation signal and durable
  `:seon.error/capped?` error fact are different contracts; neither is a
  stored receipt projection.
- The transcript deliberately supplies admission caps as value-floor options,
  so the schema's small page default does not silently clip the REPL result
  (`src/seon/render/transcript.clj:291-312`).

### REPL parity

- Production evaluator path: confirmed.
- Known-divergence classification: derived from row Var metadata, not a second
  list.
- Promotion behavior: confirmed failing.
- Independent inventory: 88 total, 69 executable, 19 pending, 34 known
  divergences, 35 passing.
- Deletion/cardinality behavior: false-green; blocker filed.

### `seon.db` q, pull, and pull-many

`q` resolves the ambient database value once, aligns explicit/default sources,
uses Datahike's evidence-producing read, and catches dependency failures into a
flat error (`src/seon/db.clj:40-51,92-168`). `pull` and `pull-many` share the
same immutable-value/evidence/error pattern (`:170-239`); pull-many returns the
dependency's input-aligned vector.

The database probe ran ambient `seon.db/q` through the acquired evaluator with
`max-collection` reduced to four. It returned no error,
`:seon.sci.admit/capped? true`, and a bounded result containing three rows plus
the elision marker. A malformed pull entity id returned flat
`:seon.db/invalid-read` with operation, exception class, and Datahike data.
Thus the read results are bounded at the production admission boundary rather
than by a second database-specific cap. The unrestricted write blast radius is
the separate blocker above.

### Reader tags

`accepted-reader` delegates only tags present in
`clojure.core/default-data-readers` and refuses every other tag
(`src/seon/sci/reader.cljc:20-34`). In the pinned Clojure 1.12.5 JVM, the
independent set was exactly `#{inst uuid}`. The focused reader suite confirmed
custom-reader precedence, unknown-tag refusal, `#=` refusal, hostile SCI
context isolation, and built-in Inst/UUID reading. No other tag became readable
in the pinned runtime.

### MCP fixes and REPL-native door edges

The fixes for live-first roster order, store-directory exclusion, exception
projection, terminal retention, multiple-form position, and bounded ordinary
event values are present and covered. The remaining failures are the general
structured bound, the frame hand list, the deleted endpoint fallback, and the
polling watchdog described above.

The contract reporter has no second literal problem limit: with caps, every
problem goes through admission and `::problem-count` preserves the full count.
The defect is the optional arm that bypasses that owner entirely.

## Verification

Focused recurring gates, run without starting a second full suite:

- `bin/test seon.sci.admit-test seon.render.value-test seon.blob-test
  seon.blob-settlement-test seon.cluster.registry-test
  seon.render.transcript-test seon.db-test` — 47 tests, 211 assertions, zero
  failures/errors.
- `bin/test seon.repl-parity-test seon.sci.reader-test seon.instrument-test
  seon.dev.mcp-bridge-test` — 107 tests, 372 assertions, zero failures/errors.

Read-only live/operator checks:

- all eight current dev namespaces plus `seon.fresh-operator` loaded;
- `bin/seon status` succeeded;
- `bin/seon-hook` returned `{"continue":true}`;
- `bin/acme status --edn` failed against the fresh command contract;
- bare `bin/acme status` returned the global roster.

Fresh-JVM falsifiers and their decisive outputs are preserved in
`tmp/audit-20260801b/src/`.

## Calibration — what is genuinely in good shape

The caps/blob wave is materially simpler than the prior inline-only result
path. It has one content-addressed blob owner, digest verification, one
settlement seam, and primitive receipt facts from which capped presentation is
derived. The real-store probe proves the current reachability union actually
protects current result blobs; the defect is precisely history and schema
derivation, not a nonfunctional union.

The parity harness now genuinely crosses the production evaluator and its
known-divergence promotion rule is excellent: a fixed divergence cannot remain
quietly classified as broken. Its 88 rows are present today. Adding the missing
identity/cardinality assertion will convert that present fact into recurring
proof.

The new `seon.db` read facade is coherent Clojure: ambient custody at one
boundary, immutable database values within each call, Datahike's concrete
evidence vocabulary, flat errors, and no database-specific result printer.
`q`, `pull`, and `pull-many` passed both focused tests and production-door
probes.

The reader change is narrow in the pinned runtime: exactly Inst and UUID defer
to Edamame's built-ins, explicit custom readers still win, unknown tags refuse,
and read-eval remains closed.

Finally, the MCP work improved model-legible failure envelopes and ordinary
output containment substantially. The reported defects are boundary holes in
otherwise useful mechanisms, not evidence that those mechanisms should be
replaced.
