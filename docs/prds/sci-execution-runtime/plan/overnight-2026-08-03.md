---
type: prd
status: active
tags: [prd, orchestrator, runtime]
---

# Overnight plan — 2026-08-03

Written 2026-08-02 night, after a session that landed 100+ commits. This
is the ordered work for an overnight run where lanes can go SLOWER and
take their time: deeper reading, more falsification, no rushing to a
commit. Everything here is either owner-ruled or explicitly marked as
needing a decision.

Standing conditions for every lane below, so each spec need not repeat
them: suite runs isolate per operator root (concurrent runs are fine,
keep to two); no CPU stress tools; use your OWN operator root under
`tmp/` for anything live and take it down after; never touch the
default operator root or the live `default` cluster; commit
path-limited and DO NOT PUSH; when a claim is stated, cite `file:line`;
mark anything unverified as unverified. Stop and report at a real
boundary rather than editing another lane's file.

## The state this starts from

Landed and verified tonight: the reachability fix (agents no longer
receive private vars, live-proven with a real model turn), ctx-derived
custody, the write-door custody fence (`:seon.db/foreign-connection`),
`seon.db` as the one database namespace with dual interfaces, stream
integrity (a malformed chunk fails the completion instead of splicing),
suite isolation and liveness, the `disarm!` readiness protocol, bounded
Flow submission, the analysis-gate fix, 14 `.cljc`→`.clj` conversions,
the store anatomy validated to 0.05%, seven `:db/noHistory` attributes,
and the `:seon.schema/created-at` deletion.

Full suite at the last quiet-tree checkpoint: **840 tests / 4,164
assertions / 0 failures**. Re-run it before trusting anything below.

Rulings #41-#48 in `README.md` are binding. #46-#48 are from tonight and
have not yet been fully implemented — most of the work below is their
implementation.

## Dependency order

Later items assume earlier ones. Within a tier, lanes are independent.

### Tier 1 — unblockers (start here)

**1A. The union read-decoding codec.** OWNER-RULED, not started. Mixed
`:seon.render/ai` / `:seon.render/html` unions ride Datahike's
EDN-string fallback: writes encode, production reads do NOT decode, so
a stored qualified-symbol producer returns as a STRING and would render
literally instead of being invoked. Treat this as a CORE SEAM, not a
render fix — every attribute flows through that codec. Establish the
encode and decode sites with `file:line` first (`src/seon/schema/
datahike.clj` plus the read projection and the pinned Datahike
boundary), then determine the BLAST RADIUS: what else is stored through
that fallback and read back as a string today? This is the second
round-trip failure found tonight (the first was print-node versus
semantic value in the MCP work), so check rather than assume it is the
last. Falsifier must FAIL first: store a qualified symbol, read it
back, assert a symbol not a string; then both literal arms; then a
mixed population proven distinguishable. One mechanism — no
render-specific decode path beside the general one.

**1B. Land the `:any`/`:some` audit as a durable issue note.** The
render-vocabulary lane produced it and it currently exists only in a
lane summary, which by this repository's own rule means it did not
happen. 18 schema keys carrying 19 `:any` leaves; 58 `:any` and 22
`:some` in active source; verdicts per instance (delete / replace with
a named schema / tightening-changes-behaviour / genuinely
polymorphic). Named concrete defects: `render.hiccup/raw` returns
`:any`, instrumentation returns `[:set :any]`, database inputs typed
`:any`, Var inputs typed `:any`, and 19 `:some` transaction-data
returns that should reference the existing
`:seon.store/transaction-data`.

### Tier 2 — the render model (needs 1A, and the research map)

**2A. Read the research map first.** The `renderer-kernel` lane is
producing `research/render-model-2026-08-02.md`: every affected area,
what is a rename versus a behaviour change, what must land together,
and its open questions. Nothing in tier 2 starts before that document
is read and its behaviour-change list is ruled on.

**2B. The vocabulary collapse.** ~200 references: `:seon.render/unit`
(83, an umbrella `[:map-of :qualified-keyword :any]`),
`:seon.render/output` (77, `:any`), `:seon.render/hiccup` (39 across 33
functions). Zero functions currently declare `:seon.render/html`
output. Deletions: `unit`, `output`, `literal`. Collapse: hiccup
becomes the DEFINITION of `:seon.render/html`, not a second key. This
is one sequenced wave — a half-renamed tree is the failure mode.

**2C. The guarded render kernel** (ruling #46). One guarded SCI
invocation for every agent-driven render, resolving the declaration in
the cluster's SCI ctx rather than `requiring-resolve`; definitions
installed once, live Var per cache miss; every result through bounded
admission and kind validation. `raw` dissolves — admitted output cannot
carry HTML authority — so remove the false safety assumption at
`src/seon/render/hiccup.clj:68-77`. Delete `namespace-declaration`'s
`render-<kind>` string-building (ruling #47: no naming conventions).
Budget to respect and re-measure: guarded trivial render 10.250 µs p50,
250-event Hiccup 2.448 ms p50, infinite loop interrupted at 13.25 ms
under a 10 ms limit.

**2D. Declared defaults and overrides.** A schema declares its default
render as Malli properties; a thing overrides with its own attribute.
Precedence: the thing, then its schema, then the floor. Both levels are
DECLARED — the contract query over `:seon.fn.arity/input-refs` /
`output-refs` finds and VALIDATES candidates but never silently
selects. No declared default plus multiple candidates is a MISSING
DECLARATION to surface loudly. A declaration must name a function that
actually qualifies, validated against the program graph at declaration
time rather than failing later on a page.

**2E. Render failure surface.** LOADING to the human only while a
repair is genuinely in flight; LOUD and unignorable in
`:seon.render/ai`; the agent is MESSAGED. One repair episode per
CHANGED failure signature; an unchanged failure never loops; then an
honest unavailable state. Closes
`render-resolution-and-feed-swallow-failures.md` and
`error-render-puts-its-own-failure-in-agent-context.md`. Needs
`src/seon/cluster.clj` wiring.

**2F. Remove `421612c26`'s render-specific matcher** once the
open-maps work lands and extra-attribute matching is proven to work
through open maps. Sequence it so we never have neither.

### Tier 3 — completion of settled rulings

**3A. The `seon.db` call-site sweep** (ruling #41's remaining
acceptance). 34 namespaces, 16 direct `d/transact` write sites each
classified individually (runtime / boot / fixture) with its failure
semantics stated, ~58 test files. ONE lane, quiet tree, nothing else
running — it touches nearly every namespace. A full suite afterward is
the proof.

**3B. MCP value chain** (ruling #44). Blocked on a protected owner:
`seon.sci.admit` must publicly expose its print-node→semantic
derivation and retain `:seon.sci.admit/print-node`, and the artifact
declaration needs `resources/seon/schema.edn`. The corrected design is
ONE SOURCE — store the print node, derive both the drill data and the
result EDN from it. Do not store both; the fidelity falsifier proved
the semantic projection cannot reconstruct the printed form (687,341
characters original versus 302,086 reconstructed). Also needs `/data`
blob selection in `render/web.clj`'s private `data-response`, and the
`valf` projector installed in `cluster.clj` (a bridge-side wrapper
would put the projection into `*1` and break the stateful-session
contract).

**3C. Per-cluster history** — in flight tonight; if unfinished,
continue. Owner wants the dial genuinely per cluster (eval clusters
off, user clusters on). Blocked historically by boot-critical
`d/history` calls. Establish first whether `:keep-history?` can differ
per BRANCH in our pinned fork or is store-wide; if store-wide, that is
a finding and the mechanism becomes different (a separate store for
eval roots), to be designed rather than forced.

### Tier 4 — open, lower priority

- The custody residue decisions already made are landed; what remains
  is the standing census catching a function returning a connection
  inside a `:map` contract — a known gap in the check, worth a property.
- `alter-meta!` is process-global from agent code and instrumentation
  reads var metadata: restore immutability or record it as an explicit
  accepted residual with the consequence named.
- The definition-seam accretion feature (`definition-seam-design-
  2026-08-02.md`): candidate contexts now work via the SCI
  copy-on-write change (fork SHA `72150fd44`), so test-before-install
  is viable. The prerequisite remains a test→function call edge;
  `:seon.test` rows carry only `sym`, `ns`, `source`.
- The store's write-amplification options and GC cutoff, both filed and
  measured but unadopted.

## What needs the owner, not a lane

- The render research map's BEHAVIOUR-CHANGE list (tier 2A) — renames
  are safe, behaviour changes are not, and only the owner should rule
  on those.
- Whether the union codec fix (1A) reveals other affected attributes
  that change semantics.
- Whether `alter-meta!` immutability is restored or accepted.

## The epistemic warning for whoever runs this

FIVE recorded figures were disproven tonight, four of them in storage
alone, and one of those by our OWN fresh measurement hours later:
"86× amplification" was a misreading (growth is linear in payload,
quadratic in commit count); "42 MB per sample" summed overlapping
intervals (real: 9.793); "1.5 MB per transaction" was false; and a
per-attribute census attributed 187,360,394 B to `created-at` while
deleting it saved 9,661,654 B — a nineteenth — because ATTRIBUTION IS
NOT A COUNTERFACTUAL when persistent-set nodes are shared.

Three of the orchestrator's own claims were also wrong: a per-cluster
injection design (refuted — one compiled Var per JVM), a flock deadlock
diagnosis (refuted — `tryLock` never blocks), and a derive-don't-store
correction (refuted — the printed form is not derivable). Each was
caught by someone reading source or running a probe.

So: re-derive before repeating, prove the counterfactual before
promising a saving, and treat every inherited number in this repository
as suspect until reproduced.
