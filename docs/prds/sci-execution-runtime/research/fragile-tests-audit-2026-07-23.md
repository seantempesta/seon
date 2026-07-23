---
type: research
status: active
tags: [research, testing, runtime]
---

# Fragile-tests audit — bad implementations protected by fragile tests (2026-07-23)

Read-only audit of the full test surface (228 `*_test.*` files under
`test/**`: writer `.clj`, dual `.cljc`, pod `.cljs`, operator
`test/seon/dev`) against the owner directive: find fragile tests, name
the invariant each was pinning, and propose the ONE constraint or
simplification that dissolves the whole class so the fragile tests can
be deleted. Method: source reading, `rg` sweeps, git history, retained
`tmp/` gate logs. No builds, no test runs, no cluster operations.
Everything marked VERIFIED was read at the cited file:line; SUSPECTED
and PREDICTED are labeled as such. Where a suspected fragility turned
out sound, that is stated explicitly (§5).

## §0 The three known live failures, root-caused

### 0.1 namespace-doc fixture (`seon.index-core-test`) — STALE ARTIFACT, plus a genuinely fragile assertion style

VERIFIED. `tmp/test-cljs-20260723-140301-5967.log:119-127`: the run at
14:04 failed
`namespace-rows-carry-real-docs-independent-of-source-density`
(`test/seon/index_core_test.cljs:249-286`) because `seon.warn`'s
`:seon.ns/summary`/`:seon.ns/doc` were nil. Chain: the test process
reads sources from the digest-checked program-source sidecar
(`src/seon/client.cljs:1206-1244` `load-program-sources`); `ns-row`
probes both `seon/warn.cljs` and `seon/warn.cljc`
(`client.cljs:1281-1298`). U7's rename `warn.cljs → warn.cljc`
(e6a23e37b, same day) left the 14:04 run consuming a sidecar that
contained NEITHER key; the sidecar rebuilt at 15:16 contains
`seon/warn.cljc` (verified directly against
`out/client/program-sources.edn`) and the docstring in
`src/seon/warn.cljc:1-3` matches the expected text exactly. Verdict:
**stale fixture/artifact, not a source defect** — the assertion itself
is sound about the mechanism.

Two real fragilities remain in the test:

- It pins the EXACT docstring prose of two live production namespaces
  (`seon.warn`, `my.kb`, `index_core_test.cljs:260-268,272-276`) —
  every docstring edit breaks the index suite even though the invariant
  is "metadata derives from the real source independent of stored
  density".
- The sidecar digest check (`client.cljs:1223-1228`) proves only
  file-matches-env, never freshness against the running tree, so a
  stale artifact surfaces as a confusing prose mismatch instead of a
  named staleness failure.

**Dissolving constraint:** (i) make the expected value DERIVED, not
pinned — the test already owns a synthetic `extra-sources` namespace
(`example.undocumented`); give it a documented synthetic sibling and
assert summary/doc/stub behavior against it, or compute the expectation
by running `namespace-info-from-source` over the same sidecar bytes the
indexer read (self-referential oracle). (ii) one freshness assertion at
the artifact-admission choke point: the test/pod process asserts its
own compile-time first-party ns set (`compiled-first-party-ns-strs`,
`client.cljs:1195-1204`) is a subset of the sidecar's keys — a
rename-lag artifact then fails loudly as "stale program-source
artifact: missing seon/warn.cljc", once, for every downstream consumer.
**Deletable:** the two exact-prose doc pins (`:260-268` full my.kb
docstring `=`, `:272` seon.warn summary `=`). **One class regression:**
stub-stored ns rows carry doc/summary derived from real source
(synthetic ns) + the freshness subset assertion.

### 0.2 host-registry authored-eval parity fixture — STALE FIXTURE against the landed R30 gate (real behavior change; test encodes the old contract)

VERIFIED from source; PREDICTED red at the next full writer gate (no
retained full-gate log postdates the commit). Commit 12269fd57
("Enforce complete durable schema contracts", 10:14) lands R30 at the
JVM admission choke point: `seon.host.preflight/durable-defn-admission`
(`src/seon/host/preflight.clj:230-250`) terminally rejects any durable
`defn` lacking a parseable `:malli/schema`; only
`record/transient-ns-syms` (`src/seon/host/record.clj:410-415` —
`#{user cljs.user seon.dynamic result}`) is exempt. The parity fixture
at `test/seon/host_registry_writer_test.clj:591-599` still authors
`(defn parity-double "Double x." [x] (* 2 x))` — schema-less, into
`my.agent.parity-agent` (not transient) — then asserts
`(= 3 (:seon.eval/n-ok result))` (`:604`). The SECOND authored batch in
the same deftest (`:664,:672`) already carries `:malli/schema` — the
suite was half-updated. The R30 commit touched five test files
(its own `host_preflight_writer_test.clj` +24 rejection regression
included) but missed this sixth fixture.

**Verdict:** stale fixture; not a defect and not an
implementation-detail assertion — the gate is right, the fixture
predates it. **The class:** authored-corpus fixtures are inline source
strings scattered across N writer suites, so every admission-contract
strengthening breaks an unknown subset. **Dissolving constraint:** one
authored-fixture composer in the shared support namespace
(`test/seon/db/writer_test_support.clj`) — e.g. `authored-defn` that
always emits the currently-required metadata — so an admission change
edits ONE producer. **Deletable:** nothing; fix the fixture (add the
schema). **One class regression:** the existing R30 rejection test in
`host_preflight_writer_test.clj` (landed with 12269fd57) is the class
regression; keep exactly it.

### 0.3 `my.plan_test.cljs:167` — test asserts the DELETED pre-U7 dispatch path; also exposes a real design smell

VERIFIED, deterministic (identical failures in three separate full runs:
`tmp/test-cljs-20260723-{134734,135920,151520}-*.log` — actual error:
`"block render failed: Missing custom renderer my.plan.internal/plan-html."`).
Anatomy: U7 replaced global var resolution with (a) a literal compiled
trusted-renderer table (`src/seon/render/core.cljc:16-32` — no
`my.plan.internal/*` entries) and (b) the guarded authored door for
everything else. `seon.render/invoke-custom-render`
(`src/seon/render.cljc:743-756`) classifies the stored symbol with
`err/agent-authored-sym?` (`src/seon/error.cljc:211-225` — TRUE for any
non-`seon.*/clojure.*/cljs.*/sci.*/goog.*` namespace, so `my.plan.internal`
counts as agent-authored) and therefore demands `::invoke-authored!` in
the render input. The test (`test/my/plan_test.cljs:149-183`) supplies
neither a trusted table nor an authored door, `with-redefs`es the
compiled `internal/plan-html`/`plan-ai` vars (which a literal
value-captured table could never see anyway), and counts invocations in
atoms — [1 1] expected, [0 0] observed.

**Verdict:** the test asserts an implementation detail (direct compiled
var dispatch + redef interception + call counts) of a path U7
deliberately deleted. Not a stale fixture in the ordinary sense, and not
(only) a defect — but it DOES surface a real smell worth the owner's
eye: the compiled first-party `my.*` toolkit's render symbols
(`src/my/plan.cljc:188-189`) are classified "agent-authored" by a
NAMESPACE-PREFIX heuristic, contradicting both R34 (provenance is
DERIVED from the asserting transaction, never name inference) and the
standing computed-rules-over-name-lists preference. Production plan
rendering now depends on the corpus-loaded SCI door resolving a
compiled toolkit fn; whether that is intended (my.* sources are
full-source corpus rows, so it can work) or an accident of the prefix
rule deserves an explicit ruling.

**Dissolving constraint:** trust classification derives from
provenance/corpus facts (the R34 machinery that already exists for
schema admission), or — cheaper and local — the `my.*` toolkit's
render symbols join the one static trusted table where toolkit blocks
register (the table's own docstring reserves exactly this: "Context
block entries join this table in the portable context acquisition
executor"). **Deletable:** the `with-redefs` interception, both
call-count atoms, and the [1 1]/[0 0] assertions. **The replacement
test:** render `:my.plan/plan-value` through the PRODUCTION resolution
path (real table or real door) and assert the outcome — hiccup contains
the root title; malformed value falls to the generic renderer; already-
projected value renders without re-entry. Behavior, not invocation
counts. **Stale wiki note:** the conversion-wiki "Env-coupled cljs
tests … (my.plan-test precedent)" entry now misattributes this failure —
it is deterministic in full runs, not load-order coupling; correct the
entry when the wiki is next appended.

## §1 Signature sweep — findings ranked by payoff

### F1 (HIGH) Fixture-vs-boot genesis divergence across every host writer suite

VERIFIED. Live fresh boot now seeds through PAGED initialization
(`protocol/initialization-pages`; proven with crash-mid-seed +
forced-restart in `test/seon/db/writer_initialization_test.clj:151-233`
and the initpage lane's acceptance). But every host writer suite
constructs genesis through its own raw path:
`host_registry_writer_test.clj:161-175` (`register-runtime-schemas!` +
`seed-schema-rows!`, "the fresh database's one deliberately unattributed
genesis"), duplicated variants in `program_plan_writer_test.clj`,
`host_instrument_writer_test.clj`, and the shared
`writer_test_support.clj:22-30` `guard-schema-rows` (raw rows, not
pages). This is exactly the shape that produced the schemagate scar
(desired-program validation fed through committed-row provenance
admission → live boot death that no fixture could see) — the wiki
already rules "neither substitutes for the live boot ordering".
**Invariant at stake:** state reachable in a test is state reachable
through the one live initialization mechanism. **Constraint:** ONE
fixture entry in `writer_test_support` that seeds through
`protocol/initialization-pages` over the suite's desired rows — the
identical builder live boot uses — so fixture genesis is by
construction the boot path; suites contribute pages, never bespoke
genesis transactions. **Deletable:** the per-suite
`seed-schema-rows!`/`register-runtime-schemas!` copies (3+ suites) and
any future per-suite genesis helper. **One class regression:**
`writer_initialization_test.clj`'s crash-mid-seed/out-of-order-page
suite (already exists) plus the standalone-artifact scar test
`test/seon/writer_standalone_schema_test.clj` — both retained, nothing
new to write.

### F2 (HIGH) Validation fail-open when the committed projection is absent

VERIFIED. The C4 "claimant `(constantly false)`" defeat is gone
(`src/seon/agent/driver/host.clj:39-53` now derives
`:seon.db.leaf/schema-validation?` from the retained committed
projection; `test/seon/db/claimant_validation_test.clj:36` proves the ON
case). But the choke point `src/seon/db.cljc:483-488` still SILENTLY
skips `validate-attrs!`/`validate-values!` whenever the leaf's
`schema-validation?` returns false, and both production producers
(`src/seon/host/context.clj:277-281`, `driver/host.clj:48-53`) return
false exactly when the projection cache is EMPTY. The bootstrap escape
is a ruled design (wiki: an empty bootstrap database disables domain
validation until its declaration transaction) — but in production the
same shape means "claimant with an unpopulated cache persists
unvalidated, silently". The class of C4 stays representable; the
regression suite can only pin instances. **Constraint (C4
completion):** make validation-off unrepresentable outside the explicit
bootstrap constructor: at the one `db.cljc` choke point, projection
present → validate; projection absent AND context not explicitly
bootstrap-constructed → flat `:core-bug` refusal, never a silent skip.
The leaf-supplied boolean dies; presence of the projection (plus one
explicit bootstrap marker) IS the switch. **Deletable:** nothing yet —
`claimant_validation_test` stays as the ON regression. **One new class
regression:** non-bootstrap transact with an absent projection returns
the flat refusal (extend `claimant_validation_test.clj`). Inventory of
the other switches examined for completeness: `SEON_INSTRUMENT`
(instrument kill-switch — documented emergency recovery, sound),
per-class repair kill-switch map (config facts, sound),
`SEON_NO_AUTO_BOOT` (boot toggle, sound). No other validation opt-out
found in `src/**`.

### F3 (MEDIUM-HIGH) Exact-prose error/status assertions — 56 occurrences in 28 files

VERIFIED count (`rg 'is \(= "<five-plus words>"'` over `test/**`,
excluding legitimate serialization oracles — see §5). Representative:
`test/seon/client_quiescence_test.cljs:142,184,228`,
`test/seon/host_authored_invocation_writer_test.clj:189-200`,
`test/seon/dev/process_test.clj:1027,1038,1210,2075`,
`test/seon/agent/run_test.cljs:106`,
`test/seon/db_remote_contract_test.cljs:891`,
`test/seon/web/datastar_test.cljs:151`,
`test/seon/db/id_test.cljc:583` (a docstring pin, same class as §0.1).
**Invariant actually at stake in every case:** the failure has the
right `:seon.error/kind` and names the governing key/symbol/config
fact. The prose is presentation. This class is about to get expensive:
the accepted Malli plan (research/malli-root-enforcement §0.6) wires
`humanize` + `with-spell-checking` into the steering explain paths —
message wording WILL change, and these 56 pins all break at once.
**Constraint:** the standing rule, enforced as a review gate on new
tests and burned down as files are touched: assert kind + the named
token (`str/includes?` of the key/symbol ONLY — the pattern most suites
already follow, e.g. `my/plan_test.cljs:1518-1520` asserting the
misspelled vs. corrected keyword tokens, which is sound). **Deletable:**
each prose `=` collapses to kind+token; no coverage lost. **One class
regression per envelope family:** one test per steering family asserts
the envelope SHAPE (kind present, config key present, message
non-empty) — several already exist (e.g.
`host_guard_policy_writer_test.clj`).

### F4 (MEDIUM) Point-fenced codec-shape tests that the queued C1 generative round-trip subsumes

VERIFIED. `test/seon/db/transport_uds_test.clj` carries six
hand-enumerated transit-stability tests
(`:356 transit-roundtrip-preserves-native-protocol-values`,
`:379 transit-decodes-aggregate-query-lists…`,
`:431 database-acquisition-is-…-transit-stable`,
`:455 transaction-branch-head-…-transit-stable`,
`:482 ensure-request-roundtrip…`, `:498 lifecycle-requests-are-closed-
and-transit-stable`), and the dual `protocol_test.clj`/`protocol_test.cljs`
pair repeats shape enumerations per family. Exactly one generative
schema test exists today (`schema_projection_writer_test.clj`).
**Invariant:** every registered protocol request/response family
round-trips the one codec. **Constraint:** the already-accepted Malli
rec §0.4 — `mg/generate` → `uds/encode` → `uds/decode` → `=` +
`ordinary-wire-value?` as a standing property over the registered
protocol families, landed WITH the C1 schema-driven total encoder
(both queued design, same owner). **Deletable after it lands:** the six
transit-stable point tests plus the duplicated shape enumerations in
the `.cljs` twin (F5). **One class regression:** the generative
property itself, plus the ALREADY-LANDED session-survives leak
regression
`test/seon/host_eval_wire_safety_writer_test.clj:28` (keep exactly
that one enumerated instance — it pins the drill's real-world seed).
The concurrency/backpressure tests in `transport_uds_test.clj` (the
other ~34) are behavior tests and are NOT subsumable — do not delete
them.

### F5 (MEDIUM) Twin `.clj`/`.cljs` suites duplicating pure-data invariants

VERIFIED. `test/seon/db/protocol_test.clj` and `protocol_test.cljs`
share near-identical deftests over pure data
(`tempid-receipts-name-string-and-integer-alternatives` in both;
`generated-candidates-remain-closed-and-uniquely-keyed` in both;
`ordinary-wire-values-reject-…` variants; database-value shape tests).
Same pattern in `transport_uds_test.clj`/`.cljs` for the codec subset.
Two files asserting one invariant drift independently — the repo's own
recipe ("One `.cljc` assertion is the cross-runtime byte oracle") and
the widened discovery (a `.cljc` runs on BOTH runners since bbecdfc03)
make the split obsolete for the pure subset. **Constraint:** merge the
pure-data assertions into one `_test.cljc` per family; tier-specific
residue (JS host-object rejection cases, JVM lazy-seq cases) stays in
small platform leaves. **Deletable:** the duplicated deftests in one of
each twin. **One class regression:** the merged dual file itself; R28
caution — do the merge when the family is next touched, not as a
standalone sweep during the conversion window.

### F6 (LOW-MEDIUM, DEFERRED) `set!` monkeypatching of the one `seon.db` API in 55 CLJS test files

VERIFIED count (`rg 'set! db/'`). Pattern (e.g.
`test/my/plan_test.cljs:205-215` replacing `db/execute-many` and
`db/transact!` process-wide, restored later): the test mutates the sole
database API's vars rather than installing a leaf. The landed injection
seam is `db/bind-leaf` (`src/seon/db.cljc:196`; modeled correctly in
`test/seon/db/portable_test.cljc:321` with the wiki's
Promise-lifetime scar). A leaked `set!` poisons every later namespace in
the shared Shadow run — the historical "env-coupled cljs tests" class.
**Constraint:** portable/surviving tests fake the database ONLY via
`bind-leaf`. **Explicitly deferred:** most of the 55 are pod suites
whose fate is decided by U9's deletion inventory (R28: do not invest in
dying surfaces); apply the constraint to survivors as they are touched,
never as a pre-U9 sweep.

### F7 (LOW) deftest-bearing files invisible by naming

VERIFIED mechanics; deliberate instances. Five support files carry
`deftest` without the `_test` suffix
(`test/seon/test/{runner_probes,runner_timeout_probes,async_fixture_probes,fixture_support_probes,async}.cljs`)
— these are the runner's own probe fixtures, invoked by their `_test`
siblings, so their invisibility to direct discovery is intentional.
`test/seon/agent/driver_process_probe.clj` contains no deftest (manual
probe). The residual class: a FUTURE real test misnamed without
`_test` is invisible to all three surfaces AND to the orphan gate
(which enumerates `*_test.*` only, `script/seon/dev/test_roots.clj:7-19`).
**Constraint (cheap, computed):** extend the orphan gate's enumeration
to flag any `deftest`-containing file that is neither `*_test.*` nor
required by a discovered test namespace. No hand list; one assertion in
the existing `test_roots_test.clj`. **New orphans since bbecdfc03:**
none — all 18 test files added since are `*_test.clj[c]` under
discovered roots (verified against the widened predicate; `.cljs` ones
match Shadow's `-test$`).

## §2 Determinations requested by the mission, in one place

| Known failure | Determination |
|---|---|
| (a) namespace-doc / seon.warn (§0.1) | STALE ARTIFACT at that run (sidecar now contains `seon/warn.cljc`; docstring intact); test additionally fragile by exact-prose pinning of live namespaces |
| (b) host-registry parity (§0.2) | STALE FIXTURE vs the landed R30 admission gate; gate correct; fixture needs the schema; class = scattered inline authored-corpus fixtures |
| (c) my.plan dispatch [1 1]→[0 0] (§0.3) | TEST ASSERTS A DELETED IMPLEMENTATION PATH (pre-U7 var dispatch + redefs + call counts); plus a real design question — my.* toolkit renderers classified agent-authored by name prefix (vs R34 derived provenance) — needs an owner ruling |

## §3 Top 5 recommended constraints for the owner

1. **Fixture genesis rides the paged initialization builder.** One
   `writer_test_support` entry seeds every host writer suite through
   `protocol/initialization-pages` — the identical mechanism live boot
   uses — replacing every per-suite raw genesis transaction. The
   schemagate class ("fixture passes, boot dies") becomes structurally
   unrepresentable because there is no second seeding path to diverge.
   Acceptance: the per-suite seeders are deleted, the full writer gate
   is green through the shared entry, and the retained
   crash-mid-seed + standalone-jar scar tests still pass unchanged.

2. **Validation-off is unrepresentable outside an explicit bootstrap
   constructor.** Delete the leaf-supplied `schema-validation?` boolean;
   at the one `seon.db` transact choke point, an absent committed
   projection in a non-bootstrap context is a flat `:core-bug` refusal,
   never a silent skip. Acceptance: `claimant_validation_test.clj` gains
   the OFF-refusal case (absent projection → refusal), the ON case stays
   green, and `bin/seon up` on a fresh reset still boots (the bootstrap
   constructor is reachable only from initialization).

3. **Schema-driven total wire encoder + one generative round-trip
   property** (already the accepted C1/Malli design — this audit adds
   the deletion payoff). Acceptance: the generative
   encode→decode→equal property is green over every registered protocol
   family; the six transit-stable point tests in `transport_uds_test.clj`
   and the duplicated shape enumerations in the protocol `.cljs` twin
   are deleted; the one retained enumerated instance is the
   session-survives wire-safety regression.

4. **Steering assertions are kind + named token, never prose.** Adopt
   as a review gate now (it is already the standing rule) and burn the
   56 exact-prose pins down as files are touched — BEFORE the
   humanize/spell-checking wiring lands, which will otherwise break all
   of them at once in one unrelated diff. Acceptance: the
   humanize-wiring commit changes zero test files.

5. **Trust classification for stored render symbols is derived, not a
   name-prefix heuristic.** Rule whether compiled `my.*` toolkit
   renderers are trusted-table members (registered where toolkit blocks
   join the one static table) or corpus rows resolved through the
   authored door — then rewrite `my.plan_test.cljs`'s dispatch test
   against that production path (outcome assertions, no redefs, no call
   counts). Acceptance: the plan-value dispatch test is green in the
   full CLJS run via the same resolution path production uses, and
   `err/agent-authored-sym?`'s role shrinks to fault attribution (or is
   replaced by the provenance-derived classification per R34).

## §4 What this audit did NOT find (honest negatives)

- No new orphaned test files since bbecdfc03; the computed discovery +
  orphan gate is doing its job (§F7 covers the one residual naming gap).
- No remaining `(constantly false)` validation defeat in production —
  the C4 fix landed as claimed; the residual is the fail-open empty-
  projection shape (§F2), which is a design gap, not a regression of
  the fix.
- No tests asserting private atom internals of production namespaces
  were found beyond the call-count/`set!` patterns already covered
  (§0.3, §F6); writer-side tests consistently assert datoms, receipts,
  and envelopes.
- The wiki's env-coupled-test entry needs correction (my.plan-test is
  now a deterministic failure, §0.3), but the underlying advice
  (verify a focused-run failure in the full run) remains sound.

## §5 Suspected fragilities that turned out SOUND (do not touch)

- `test/seon/ui/html_test.cljc` (44 exact strings): the subject IS an
  HTML serializer — exact output is the behavior (byte/DOM-identity
  oracle). Sound.
- `test/seon/repl/parse_test.cljc` (+repair): parser/repair output
  equality is the codec contract. Sound.
- `test/seon/agent/fs/match_test.cljc` exact file-content strings:
  edit-primitive semantics. Sound.
- The widespread `str/includes?` assertions on error values that name
  ONLY a key/symbol/config token (e.g. `db_remote_contract_test.cljs:45-52`,
  `my/plan_test.cljs:1518-1520`): this is the compliant pattern the
  prose pins should converge to. Sound.
- `test/seon/db/transport_uds_test.clj`'s ~34 concurrency/backpressure
  tests: behavior tests of real session semantics, not shape
  enumerations — NOT subsumed by the generative property. Sound.
- `test/seon/host_registry_writer_test.clj`'s exact-source corpus
  assertions (`:642-646`): verbatim source fidelity IS the corpus
  invariant. Sound (only the schema-less fixture input is stale).

## §6 Deletion ledger (nothing deleted without its replacing constraint)

| Deletable tests/assertions | Replacing constraint | Retained class regression |
|---|---|---|
| index_core exact docstring pins (`:260-268,:272-276`) | derived/synthetic doc oracle + sidecar freshness assertion (§0.1) | stub-row-carries-derived-metadata on a synthetic ns |
| my.plan redefs + call-count assertions (`:155-183`) | production-path dispatch after the trust ruling (§0.3) | plan-value renders via production resolution; malformed falls to generic |
| per-suite writer genesis helpers (registry/program-plan/instrument) | shared paged-initialization fixture entry (F1) | writer_initialization crash-mid-seed + standalone-jar scar |
| 6 transit-stable point tests + protocol `.cljs` shape twins | schema-driven total encoder + generative round-trip (F4) | the generative property + wire-safety session-survives |
| 56 exact-prose steering pins (as touched) | kind+token assertion rule (F3) | one envelope-shape regression per steering family |
