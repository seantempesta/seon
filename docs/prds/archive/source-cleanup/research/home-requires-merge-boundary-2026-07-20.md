---
type: research
status: complete
tags: [research, architecture, agent, capability, schema]
---

# Home-requires merge boundary (2026-07-20)

## Decision summary

The issue-triage FOLD row for
[[../../../seon/issues/root-context-replaces-base-capability-requires]] is not
remaining configuration implementation. The additive manifest and root merge
landed in `3c08c176`, and HEAD retains it. `config/acme.edn` now declares only
`acme.brand` and `acme.widget`; ordinary agents inherit the system toolbelt,
and root adds its one `seon.agent` refer edge over the resolved downstream
vector.

Do not rebuild `merge-home-requires`, add another composition layer, or move
this work into the Stage-4 ambient-configuration unit. The note remains open
only because its final proof and documentation were never reconciled after
the later compact-card implementation `e187284f`:

- no focused current test names the decisive marker-free `set-purpose!`
  compact-card behavior;
- no coordinated ACME database/prompt observation proves the persisted
  ordinary, downstream, and root edges at one frozen revision; and
- the issue/current compact renderer and the architecture disagree about
  whether `:seon.fn/agent-facing?` selects compact-card functions.

That last item is a design-authority conflict, not permission to delete the
marker globally. Resolve it before closing the note.

## Dependency ledger

| Dependency or mechanism | Selected revision | Source grounding | Existing Seon evidence |
|---|---|---|---|
| Aero manifest reader | `aero/aero` `1.1.6`; vendored checkout `c47a10fa5f6a52084d04769af06d5e04d6603e13` | `reference-code/aero/src/aero/core.cljc:100-102` defines shipped `#merge` as `(apply merge values)`; `deps.edn:136` selects `1.1.6` | `src/seon/config.cljs:590-679` overrides Aero's public `reader` multimethod at the one manifest seam |
| Home require data | Seon HEAD audited at `2becf6f0909a34db48ef3884b81344ff6574190b` | `src/seon/agent/home.cljs:12-24,78-166,201-232`; `src/seon/eval.cljs:1715-1734` | `home/require-spec`, `home/require-specs`, `home/require-edges`, and the mixed-`or` `:seon.eval/home-requires` database attribute |
| Manifest/root composition | implementation commit `3c08c176` | `src/seon/config.cljs:619-657,1540-1571`; `config/system.edn:215-366,406-421`; `config/acme.edn:1-42` | `test/seon/config_test.cljs:295-384` proves namespace-identity replacement, append order, sparse inheritance, and explicit-tree replacement |
| Namespace-card selection | implementation commit `e187284f` | `src/seon/agent/ctx/namespaces.cljs:165-209,680-687,1097-1140` | persisted `:seon.ns/require-edges` select whole cards for aliases or exact public schema-complete functions for refers; the renderer does not read `:seon.fn/agent-facing?` |
| Execution wiring | `e187284f` plus current HEAD | `src/seon/eval.cljs`; `test/seon/eval/auto_refer_test.cljs:123-172` | root's resolved `start!`, `delegate!`, and `set-purpose!` refers install as real self-host uses and execute as functions |
| Database semantics | `org.replikativ/datahike` `0.8.1681`; vendored checkout `6f2569087ed3` | `reference-code/datahike/`; the repository `datahike` skill | home requirements are one cardinality-one EDN value; durable dependency selection is the component require-edge data committed with the namespace entity |

No dependency source is missing for this boundary.

## Current landed behavior

### Manifest composition

`merge-home-requires` folds additions over a vector using the required
namespace symbol, the first element of each require spec, as identity. A
repeated target replaces its base spec at the same index; a new target appends.
This makes the output stable and prevents duplicate `:as`/`:refer` declarations.

`combine-agent-context` applies that fold only to a sparse
`:seon.config/agent-context` overlay. An overlay that explicitly declares
`:seon.agent/ctx` remains a wholesale replacement and deliberately drops the
base home requirements. The custom Aero `#merge` reader preserves shallow
merge semantics for every other top-level manifest key.

After manifest composition, `context-config-for` resolves the ordinary agent
context and applies root's `:seon.eval/home-requires` by the same namespace
identity rule. Thus root inherits both the system vector and downstream ACME
additions. A launch-time per-agent override remains an exact override through
`resolve-agent-context`; it is not another additive manifest layer.

### Durable data and execution

The accepted require item is exactly one of:

- `[namespace-symbol :as alias-symbol]`; or
- `[namespace-symbol :refer [function-symbol ...]]`.

`seon.agent.home/initial-ns-entity` projects the selected vector directly into
`:seon.ns/require-edges` in the same creation transaction as the starting
namespace. Alias edges carry `:seon.ns.require/alias`; refer edges carry a set
in `:seon.ns.require/refers`. The complete vector is also stored in the
mixed-`or` `:seon.eval/home-requires` string slot and decoded once at the read
boundary. Missing per-agent data falls back to the canonical home vector;
empty and absent are not interchangeable, and nil is never stored.

The manifest schemas currently use `[:vector :any]` as a cycle-breaking leaf,
while the database attribute and home namespace own the strict require-spec
shape. This audit does not authorize widening that `:any` or duplicating the
strict shape into `seon.config`. If the source-cleanup schema rule is applied
to this leaf later, the one-mechanism repair is to expose a portable shared
require-spec shape without introducing a second schema definition.

## Remaining authority conflict

The open issue's latest ruling and current `namespaces.cljs` say a require edge
is the compact presentation authority:

- `:refer [f g]` renders exactly public, non-private, schema-complete `f` and
  `g`, including `set-purpose!` even without marker metadata;
- `:as alias` renders the namespace's complete public, non-private,
  schema-complete callable and schema surface; and
- explicit compact selection renders the same whole compact surface.

`docs/seon/architecture/toolkit.md`, however, still states that compact cards
and function menus include only functions carrying the positive
`:seon.fn/agent-facing?` fact. The marker remains a live, intentional selector
for `seon.agent.ctx.menu`, analyzer export, and database program reconstruction.
Deleting its schema, metadata, persistence, or all source annotations as part
of this small FOLD would expand into multiple owners and contradict that
architecture.

The smallest coherent ruling is one of:

1. Ratify the newer require-edge rule for compact cards, keep the marker for
   the separate function-menu/export concern, and update `toolkit.md` to state
   that distinction. This matches current source and the issue's concrete root
   failure.
2. Restore marker-gated compact cards and explicitly mark every referred root
   function. This contradicts the issue's no-parallel-allowlist ruling and
   would make home requirements insufficient presentation authority.
3. Retire the marker everywhere in a separately owned program-graph/menu
   refactor. This is not a Stage-4 home-requires FOLD and needs its own source
   inventory and architecture ruling.

Option 1 is the dependency-minimal completion path. The top-level orchestrator
must make that architecture judgment; this audit does not silently choose it.

## One closure owner and exact paths

After the ruling, one bounded config/context proof owner should close this row.
Its maximum path set is:

- `test/seon/agent/ctx/namespaces_test.cljs` — add the missing marker-independent
  narrow-refer regression using a public, schema-complete `set-purpose!`-shaped
  row with no `:seon.fn/agent-facing?` fact; prove it renders while an
  unmentioned public sibling does not;
- `test/seon/config_test.cljs` — retain the existing additive merge assertions;
  change only if the focused proof exposes a real missing case;
- `docs/seon/architecture/toolkit.md` — reconcile compact-card selection with
  the chosen ruling;
- `docs/seon/issues/root-context-replaces-base-capability-requires.md` — record
  the current commits and frozen proof, then archive on success; and
- the owning source file `src/seon/agent/ctx/namespaces.cljs` only if the new
  regression falsifies HEAD.

There is no planned production deletion for option 1. In particular, do not
delete `merge-home-requires`, `home-ns-require-specs`, the durable
`:seon.eval/home-requires` slot, require-edge facts, or
`:seon.fn/agent-facing?`. `src/seon/config.cljs`, `config/system.edn`, and
`config/acme.edn` are read-only inputs to the closure proof unless the test
finds an actual regression.

## Falsifiers and acceptance evidence

### Focused source proof

Run the existing focused selector containing:

- `seon.config-test`;
- `seon.agent.home-test`;
- `seon.eval.auto-refer-test`; and
- `seon.agent.ctx.namespaces-test`.

It must prove all of the following, not merely output text size:

- sparse ACME additions preserve every base target and append `acme.brand` and
  `acme.widget` exactly once;
- root contains those targets plus its one `seon.agent` edge;
- a repeated namespace deterministically refines in place;
- an explicit block-tree replacement still drops the base vector;
- persisted data wins over fallback and every related read uses one immutable
  database value;
- the real root refers install in the self-host compiler; and
- the compact-card test renders marker-free `set-purpose!` solely because the
  persisted refer edge selects it, while excluding an unselected sibling.

The current tests already cover every item except the final explicit compact
assertion. Historical green counts cited in the issue are supporting evidence,
not sufficient proof for current HEAD.

### Frozen live ACME proof

After Stage 4 has a coherent source freeze and ACME ownership is released:

1. apply `config/acme.edn` through the normal operator and create one fresh
   ordinary agent plus root at the same frozen database value;
2. query the persisted home vector and `:seon.ns/require-edges`, rather than
   inferring them from manifest text;
3. prove the ordinary agent has the complete system targets plus only
   `acme.brand` and `acme.widget` as downstream additions;
4. prove root has that same set plus the exact `seon.agent` refers;
5. render the namespaces block from that immutable database value and observe
   `set-purpose!`, `acme.brand`, and `acme.widget` compact contracts, with no
   helper/notes fixture namespaces and no function-menu/typeahead blocks; and
6. run a second converged config apply and prove it writes no transaction.

The proof must record the frozen source revision, database basis transaction,
commit ID, focused test result, and the observed edge/card sets in the issue
before archive. A default-only render or a manifest-value assertion cannot
substitute for the downstream database/prompt observation.

## Dependency order

1. Keep the already-landed additive merge; do not place it behind the pending
   per-operation configuration spine.
2. Resolve the compact-card marker architecture conflict.
3. Land the one focused marker-free refer regression and any source correction
   it actually requires.
4. Run the focused source proof.
5. At the coordinated Stage-4 frozen checkpoint, run the ACME database and
   prompt proof after config reconciliation is available and before the issue
   is archived.
6. Close the FOLD row only after the issue contains both commit evidence and
   frozen live evidence.

This boundary is therefore source-built but not graduated. It does not block
earlier A-F program units; it remains a Stage-4 proof/authority exit.
