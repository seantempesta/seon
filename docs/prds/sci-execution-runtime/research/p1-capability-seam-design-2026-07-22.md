---
type: research
status: active
tags: [research, agent, architecture]
---

# P1 — THE SEAM: one capability/effect boundary (orchestrator design)

Owner rulings bound into this design: same-artifact or same-source are
the only bridges between runtimes (wrapper registries of hand-mirrored
fns are dead); agents just use functions and never know where things
run; effect-class metadata (pure/idempotent/external) is declared at the
same boundary replay uses; package hosts route through the same door;
tiebreaker = easy experimentation + a first-time reader can hold it in
their head.

Grounding read directly by the orchestrator: `src/seon/db.cljs` (1461
lines, the pod session + agent surface), `src/seon/host/context.clj`
(1923 lines, the writer pool + wrapper registry + the hand-mirrored
`db-query`/`db-pull`/`db-transact!` at 706/714/766),
`test/seon/host_surface_writer_test.clj` (the census gate),
`src/seon/db/transport/uds.{cljs,cljc}`, `src/seon/db/internal.cljs`.

## The observed drift (why hand-mirroring is structurally dead)

The host wrappers in `host/context.clj` mirror the pod API by hand and
have ALREADY diverged in every dimension the wiki predicted:

- `db-transact!` (context.clj:766) accepts `{:seon.db/tx-data …}` and
  returns `{:seon.db/ok? true :db-after <3-key summary> :tempids …}`;
  the pod `transact!` (db.cljs:909) accepts `{:seon.db/tx-data …}` under
  its OWN ns keys (`::tx-data`), runs identity-symbol coercion, attr and
  value validation, EDN-slot encoding (db.cljs:930-941), and returns a
  full transaction report. The host path skips ALL of the validation and
  encoding: an agent transacting a set-valued or EDN-slot attribute on
  the host writes a different shape than the same call on the pod.
- `db-pull` (context.clj:714) returns the raw writer result — it never
  runs the ONE decode boundary (`decode-edn-value`, db.cljs:1429), so
  cardinality-many sets come back as vectors on the host and as sets on
  the pod. This is precisely the boot-failure class the wiki records.
- `db-query` carries no resource options (db.cljs:770
  `read-resource-options` has no host counterpart), so host reads are
  unmetered where pod reads are budgeted.

No review process fixes this class; only one source can.

## The design in one paragraph

Each capability family keeps its own vocabulary and gets ONE portable
`.cljc` core (same source, both tiers) that is pure: request builders,
response interpreters, validation, the decode boundary, and retry
DECISION functions. Below it sits exactly one platform LEAF per tier —
the only place async, sockets, pools, timers, and ambient context live.
Above it sit thin agent-facing entry fns (in the same `.cljc`) that
compose build → leaf → interpret; the entry line is the only
reader-conditional site, so async contagion stops there by construction.
One installer (`seon.capability`) binds leaves at boot and enumerates
them; effect classes are declared metadata on the entry fns, read by the
census and by replay. A package host is just a leaf that forwards the
family's own wire maps to another process — routing is leaf
installation, not a new mechanism.

## The boundary contract (normative)

1. **Pure core, no conditionals.** A family's `.cljc` core contains
   zero platform code outside the entry fns. It exposes:
   - request builders → the family's existing wire vocabulary (for db
     this is `seon.db.protocol` maps — protocol.cljc is ALREADY the
     shared same-source contract; the transport pair
     `uds.cljs`/`uds.cljc` already delivers identical Transit maps);
   - response interpreters → the family's public return/error envelopes
     (`response-error` db.cljs:282, report shaping db.cljs:892, the
     decode boundary db.cljs:1429 + the computed predicates in
     `seon.db.internal` 233/242/286, which are schema-registry-derived
     and promote to `.cljc` with ~4 lines of platform residue left in a
     leaf);
   - policy decisions as pure fns over shared failure vocabulary
     (`recoverable-transaction-delivery?` db.cljs:196 — today the pod
     and the host (`writer-call!` recovery, context.clj:579-622) each
     own a divergent copy of this judgment; the core owns it once).
2. **One leaf per family per tier.** The leaf's contract is a single
   fn: wire request in, wire response out (plus, where a family needs
   it, an ambient-context accessor — provenance/agent-id — because
   ambient scope is platform: AsyncLocalStorage on the pod
   (`seon.db.internal`), `*agent-id*` binding on the host
   (context.clj:59)). Pod db leaf = the existing multiplexed session
   machinery (db.cljs:179-687, stays `.cljs`); host db leaf = the
   retained writer pool (context.clj:192-622, extracted from
   host/context.clj into its own leaf namespace). Leaves own sleeping,
   scheduling, reconnection — but consult the core's pure decision fns.
3. **Entry fns: same call shape by construction.** The agent-facing
   fns live once in the family `.cljc`. The child (.cljs) signatures,
   option keys, and error envelopes are the authoritative contract
   (wiki law); the port preserves them exactly because both tiers now
   run the same builder/interpreter source. Per-tier ceremony
   (`^:async`/`await` on the pod; plain calls on the JVM) is confined
   to the entry-fn line via reader conditional — the exemplar lane owns
   the exact idiom, the constraint is that NOTHING below the entry
   differs per tier.
4. **Effect classes are entry-fn metadata — FOUR classes (orchestrator
   ruling, 2026-07-22).** The seam-grounding lane flagged that the
   three-word ruling collides with the recovery ruling's READ-ONLY
   class (program-synthesis:1679-1685): a head query, file read, or
   env read is replay-safe but not referentially pure. Ruled: every
   agent-facing effectful fn declares `:seon.capability/effect` ∈
   `#{:pure :read :idempotent :external}` alongside the existing
   `:seon.fn/agent-facing?` marker (the census already computes LEFT
   from positive metadata — host_surface_writer_test.clj:56 — and gains
   one computed assertion: agent-facing ⇒ effect declared).
   `:pure` = referentially transparent given args, including reads AT
   an explicit immutable database value (the value is an argument);
   `:read` = observes the mutable world without changing it (head
   reads, fs/env/process reads) — replay-safe, never receipt-bearing;
   `:idempotent` = mutation with a durable receipt (`transact!` with
   op-id — the writer receipt, context.clj docstring:18-23, is the
   replay mechanism) — replay returns the recorded outcome;
   `:external` = mutation without a receipt — replay is ambiguous and
   recovery must never re-run it. Replay classification (P4 recovery)
   and portability now share one declared surface with one taxonomy.
5. **Provisioning = same source, not mirrors.** The host base loads
   family `.cljc` cores through the existing loader/registry doors
   (`registry-load-fn` context.clj:925, `load-portable-slice!`
   context.clj:1275): registering a family binds its LEAF over the
   writer session and lets the portable core resolve — the
   hand-mirrored wrapper fns (`db-query`, `db-pull`, `db-transact!`,
   and their `register-host-capabilities!` rows context.clj:964-981)
   are DELETED in the same change. The sci registry mechanism itself
   (shared load-fn, fork semantics, var upgrade) is untouched — what
   changes is what gets registered: compiled-from-the-same-source
   portable fns closed over the host leaf, never re-implementations.
6. **Package hosts enter the same door.** WP-K's host routing
   (`19654064`) becomes: a package host process serves a family leaf
   over the same transport discipline; installing that leaf IS the
   routing. WP-B/WP-J land as capability leaves (per the P5 plan line).
   No second registry, no envelope translation layer.

## Orchestrator rulings on the grounding lane's open decisions

The seam-grounding inventory ([[p1-seam-grounding-2026-07-22]]) exposed
eight decisions; ruled here (each traces to the tiebreaker):

1. **Four effect classes** (`:pure :read :idempotent :external`) — see
   contract point 4. Every "ambiguous: read-only/idempotent" row in the
   inventory resolves to `:read`.
2. **Child call shapes freeze; wrapper shapes die** — already contract
   point 3.
3. **Ambient context is acquired ONCE at the platform leaf and passed
   into portable logic as ordinary values.** Portable code never touches
   AsyncLocalStorage or dynamic vars; the leaf's accessor contract is
   part of the family leaf, one per tier (AsyncLocalStorage on the pod,
   `*agent-id*`-derived on the JVM).
4. **Operation identity is minted at the entry boundary, not inside
   leaves.** The entry fn mints (or accepts) the op-id/request-id and
   threads it; clock and UUID generation are leaf services the entry
   calls once, so a replayed operation carries the SAME identity into
   the receipt mechanism instead of minting a fresh UUID after a crash.
   This is what makes `:idempotent` honest for transact!/blob-put!/
   package-reconcile.
5. **One response shape: the flat child error contract**
   (`:seon.error/message`/`kind`/`data`). The host's nested
   `{:seon/error {…}}` shaping and the unused third envelope in
   internal.cljs:578-585 are deleted, not translated.
6. **Package boundary fns carry per-function effect metadata** in the
   operation descriptor; `row->host` selects platform only. Default for
   unclassified package calls is `:external`.
7. **`seon.agent.fs/home-dir` is a contract bug** (throws instead of
   the family error envelope, fs.cljs:537-546) — fixed by the P2
   fs/shell lane as part of its port, not silently preserved.
8a. **Operation identity is a PUBLIC optional entry key (ruling 9,
   2026-07-22, resolves the P1c early stop).** The P1c falsifier
   exposed a real contradiction: the child `::transact-request`
   (db.cljs:71-80) is closed with no identity key, yet the op-id
   replay gate requires a second call to address the first call's
   receipt — and ruling 4's "mints (or accepts)" cannot be satisfied
   by minting alone across invocations. Ruled: `:seon.capability/op-id`
   is added to the child request schema as an OPTIONAL public key — an
   intentional, recorded contract extension (not drift): absent → the
   entry mints; present → the entry threads it to the receipt
   mechanism, and a repeated op-id returns the recorded outcome with
   `:seon.capability/replayed? true`. This is the one idempotency-key
   vocabulary for every `:idempotent` capability fn (transact!,
   blob-put!, package-reconcile) on both tiers, and it is what P4
   crash recovery uses: replay is a NEW invocation addressing the SAME
   receipt. Strictly distinct from `::protocol/request-id`, which
   stays internal per-roundtrip transport identity and must never
   become public (the grounding rule stands). Gate: two-call replay
   asserted on BOTH tiers in the dual-tier test file.

8. **Missing Malli schemas on public db vars** (`query-with-evidence`,
   `pull-many`, `entity`, `installed-schema`, `execute-many`,
   `index-page`) are added by the exemplar — schema is part of the
   frozen contract, never inferred from bodies.

The inventory's falsifier is adopted as the exemplar's falsifier: if
one same-source capability definition cannot expose the child
signatures unchanged while routing to Bun or JVM leaves with one
response/error shape, the seam has failed and the lane must stop.

## Exemplar (P1c lane): the seon.db core

Port order inside the exemplar:

1. Promote `seon.db.internal` decode predicates + value encode/decode
   to `.cljc` (leave fiber-context in a pod leaf).
2. Split `seon.db` into `src/seon/db.cljc` (schemas, builders,
   interpreters, validation, decode, entry fns, retry decisions) and
   the pod session leaf (existing machinery, unchanged behavior).
3. Extract the host writer pool from `host/context.clj` into the host
   db leaf; bind the portable core over it; delete the mirrored
   wrappers and their registry rows.
4. ONE `.cljc` test file exercising the same agent-facing call shapes
   (transact!/query/pull round-trip incl. set-valued + EDN-slot decode,
   error envelopes, op-id replay) run by BOTH `bin/test-cljs` and
   `bin/test-writer` — dual-tier proof by construction, plus the census
   gate flipping seon.db family rows without touching the seed list
   semantics.

Success measure: the census `:w5-0b` seon.db pending rows resolve
through computed dispositions; zero shape drift possible because no
second implementation exists to drift.

## Open questions routed to the grounding lanes

- The seam-grounding lane's drift enumeration may surface more
  divergences than the three above — each becomes an exemplar test.
- The JVM-rationale lane's verdicts decide whether any host-leaf
  behavior (preemptive interrupt hooks, instrumentation) must be part
  of the leaf contract now or stays a JVM-only upgrade.
- Listener/`listen!` surface is pod-only today; the seam leaves it in
  the pod leaf and does NOT add it to the leaf contract until a
  consumer exists (no speculative parity).
