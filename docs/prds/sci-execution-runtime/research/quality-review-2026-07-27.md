---
type: research
status: complete
tags: [research, testing, runtime]
---

# Fresh-tree quality review — 2026-07-27

## Scope and verdict

Reviewed the fresh project (`src/`, `test/`, `bin/test`, `deps.edn`, `bb.edn`)
and commits `f25e34594`, `a507f410e`, `4643c4f55`, `7502871d5`,
`16d400b7e`, and `35a5c75a8` against the construction handbook and the
2026-07-27 fresh-tree rulings.

The split is structurally real for the Clojure CLI: fresh source has no
`src-old` require, the default and test classpaths exclude `src-old`, and no
namespace is duplicated across fresh and old source. `bin/test` correctly
derives nested `.clj` and `.cljc` test namespaces. The current default gate
passes 11 tests / 55 assertions in 8.28 seconds.

The fresh tree is not yet a trustworthy base. Two run transitions admit
states their contracts call impossible, the default gate omits most of the
adopted Flow contract surface, and Babashka still exposes quarry source by
default. The result is **not ready to serve as the next runtime rung until the
blockers below are resolved**.

During the review, two parallel fixes landed: `6f7094613` excludes transitive
Konserve copies from Datahike and Proximum, and `258ad32c9` passes the Gemini
prompt correctly. Both are reflected in the cleared checks below; neither
changes the open findings.

## Findings

### Blocker — claim takeover does not enforce open or expired eligibility

**Location:** `src/seon/cluster/run.cljc:176-209`.

**Failing scenario:** a run held by `p1` with a lease through 12:30 accepted a
takeover-shaped `claim-tx` at 12:00 when the caller supplied the exact observed
process and lease. Datahike committed `p2`, epoch 2, and a 13:00 lease. A
second probe closed a run and then reacquired it, producing one entity with
both `closed-at` and live custody.

The function's docstring says a live foreign claim is not stealable and
takeover is for an expired claim, but the branch tests only whether two
observed fields are present. No public claim decision receives `now`, checks
`open?`/`expired?`, or fences the current-run/open facts.

**House rule:** coordination lives in the database transition; a caller-side
pre-read must not be the only admission rule. The invalid state should be
unrepresentable. Tracked in
[[../../../../seon/issues/run-claim-eligibility-is-not-in-the-cas]].

### Blocker — close can strand the actual agent pointer

**Location:** `src/seon/cluster/run.cljc:153-174` and
`src/seon/cluster/run.cljc:241-264`.

**Failing scenario:** a run opened and claimed for its real agent was closed
with a second existing agent id. The close committed, but the real agent still
pointed to the now-closed run. `open-tx` has the symmetric defect: the stored
agent ref and the agent id used by the pointer CAS are unrelated request
fields.

The quarry's `run-fence` asserted the agent pointer and epoch together at
`src-old/seon/agent/run/core.cljc:108-118`; the fresh transition dropped that
part of the CAS.

**House rule:** an entity is its attributes and connections; one transaction
must prove and remove the exact ref it settles. Correlated caller inputs are
not a relational contract. Tracked in
[[../../../../seon/issues/run-close-does-not-fence-the-agent-pointer]].

### Blocker — the default gate omits adopted Flow contracts

**Location:** `src/seon/flow.clj:200-713`,
`src/seon/flow.clj:795-1036`, and `test/seon/flow/loop_test.clj:488-717`.

**Failing scenario:** breakage in bounded submission, wedge/timeout capacity,
error fanout/drop accounting, mailbox bounds, database-proc serialization,
per-flow isolation, indexer/source behavior, or stop containment is invisible
to `bin/test`. The four fresh Flow tests cover only the planning-lineage
prototype.

The surviving recurring suite remains at `test-old/seon/flow_test.clj`. It ran
against the current fresh source and passed 15 tests / 72 assertions, proving
that useful coverage exists but is attached to the wrong gate.

**House rule:** a proof invisible to every owning runner is not covered; fresh
`test/` is the honest list of what the project proves. Tracked in
[[../../../../seon/issues/fresh-flow-source-is-not-covered-by-bin-test]].

### Blocker — run properties do not cover their stated transition classes

**Location:** `test/seon/cluster/run_test.clj:133-179` and
`test/seon/cluster/run_test.clj:223-290`.

**Failing scenario:** expired takeover has zero coverage. The live-foreign
example exercises the fresh/reacquire request, not the takeover branch.
Claims are submitted sequentially inside `mapv`; no generated transition
sequence combines heartbeat, release, takeover, close, and stale work.
Successful close and heartbeat have zero coverage.

The recovery property also does not prove its claim that terminal receipts are
untouched. It checks receipt count and absence of `:running`, so rewriting all
`:done` and `:error` statuses to `:interrupted` would still pass.

**House rule:** generative properties guide state-transition design and
observe durable facts independently; generated parameters around an example
are not a state-machine property. Tracked in
[[../../../../seon/issues/run-acceptance-properties-miss-takeover-and-terminal-preservation]].

### Blocker — core schema admission is a literal process-name trust list

**Location:** `src/seon/schema.cljc:261-300`.

**Failing scenario:** a new legitimate core transaction producer is treated as
agent-authored until its process identity is added to
`core-process-identities`; conversely, the classification grants core
exceptions solely because the provenance process id has one of three names.

The asserting transaction is the correct provenance source, and unknown
provenance correctly fails closed. The defect is the next step: turning that
provenance into trust through a maintained keyword set.

**House rule:** trust/privacy/placement classification is computed from facts,
provenance, or artifact inventory, never a namespace prefix or literal name
list. Tracked in
[[../../../../seon/issues/schema-core-admission-uses-a-process-identity-allowlist]].

### Friction — Babashka keeps quarry source ambient

**Location:** `bb.edn:1`.

**Failing scenario:** plain `bb` successfully requires the quarry-only
`seon.time`, while plain Clojure correctly refuses it. A hook or tooling test
can therefore acquire an old dependency and pass even though the fresh
project cannot load the same namespace.

**House rule:** the fresh tree is the default project; the old system is
disabled and available only through explicit old-facing entry points. Tracked
in [[../../../../seon/issues/babashka-default-classpath-exposes-src-old]].

### Friction — Gemini review backlog loses concurrent edits

**Location:** `bin/seon-hook:338-380`.

**Failing scenario:** two PostToolUse processes can read the same pending
vector and overwrite one another's path. A same-file edit arriving during the
model call is also erased when `clear-reviewed-pending!` removes the old
snapshot by path instead of generation.

The other requested rails passed inspection: the review path has an outer
never-throw catch; a direct process probe showed `destroy-tree` killed a timed
out shell and its child; and feedback retains at most one character per
configured token, including its truncation marker.

**House rule:** absence of signal is never health, and multiple lanes sharing
one checkout are normal. Tracked in
[[../../../../seon/issues/gemini-review-pending-state-loses-concurrent-edits]].

### Cleanup — Flow config dials still have two schema owners

**Location:** `src/seon/flow.clj:408-425`,
`src-old/seon/config/resolve.cljc:341-365`, and
`src/seon/schema.cljc:739-813`.

**Failing scenario:** `-M:writer:host:writer-test` loads both source trees.
Both namespaces register the same queue-depth and concurrency keys, while
`register!` uses last-write `assoc`. The shapes are byte-equal today, so the
probe loads successfully; any future drift silently makes load order the
schema authority.

**House rule:** one shape has one owner; duplicate mechanisms do not remain
live merely because they currently agree. Tracked in
[[../../../../seon/issues/flow-config-dials-have-two-registration-owners]].

## Organization verdict

- **Fresh/old source boundary:** passes for Clojure CLI source and tests. No
  fresh file requires `src-old`. `clojure -Spath` shows `src-old` only for
  explicit old aliases.
- **Namespace uniqueness:** passes across `src/` and `src-old/`. The only
  duplicate source namespace found is the pre-existing old-tier pair
  `seon.db.transport.uds` in `.cljc` and `.cljs`, not a cross-tree duplicate.
- **Test discovery:** `bin/test:19-26` correctly handles nested paths,
  underscores, `.clj`, and `.cljc`, and fails loudly if discovery is empty.
  Its problem is ownership coverage, not namespace derivation.
- **Dependency closure:** passes after `6f7094613`. `clojure -Stree` selects
  exactly one Datahike, Konserve, and Proximum coordinate under both default
  and old writer/host test aliases.
- **Babashka:** fails the split because `src-old` remains ambient.

## Datahike bridge verdict

`seon.schema.datahike:11-150` preserves the quarry's Malli alias resolution,
value-type mapping, enum/ref/collection handling, cardinality derivation,
secondary-index validation, uniqueness/index/component/no-history facets, and
ordered schema projection from `src-old/seon/db/internal.cljc:41-186`.

Two quarry features were not copied:

- `tx-meta-datahike-schema` has no fresh caller; no current caller was dropped.
- The dynamic `*schema-projection*` override is unnecessary for the only fresh
  caller, which derives module-registered run attributes. Activation also
  installs the projection's forms as the current candidate population.

The removal is safe for the current tree, but it is a boundary constraint for
the upcoming database owner: deriving schema for an arbitrary immutable
projection must take that projection explicitly rather than reviving a dynamic
ambient binding.

## Quality-bar census

- No fresh runtime requires quarry source.
- No unexplained runtime timeout/default literal was found in the scoped
  fresh namespaces. Flow's queue/concurrency defaults carry units and
  provenance in their owning schema descriptions; the large seeded-outcome
  integers are deterministic mixing constants, not runtime dials.
- The visible `:any` schemas in `seon.schema` and `seon.schema.form` are on the
  Malli-form/value introspection boundary or exact state snapshot helpers. No
  new domain attribute uses `:any`.
- Flow's `escalated?` and `admitted?` booleans are invocation-local prototype
  inputs, not stored database twins of absence.
- `seon.flow` throws only at core/configuration or internal execution
  boundaries in this scope; no fresh agent-facing capability surface exists
  yet. The run transitions return transaction data as required.
- One prohibited name-based classification remains: core schema admission.

## Proof record

- `bin/test` — 11 tests / 55 assertions, green, 8.28 seconds.
- Focused current-source `seon.flow-test` through the old alias — 15 tests /
  72 assertions, green.
- Default, `:dev`, `:test`, `:writer`, `:writer:host:writer-test`, combined
  test, and historical `:cljs` classpaths inspected.
- Datahike live-lease steal, closed-run reacquire, and mismatched-close probes
  reproduced as described above.
- Old/fresh Flow dial definitions loaded in one JVM and compared equal.
- Timed-out Babashka process-tree destruction left root and descendant dead.
