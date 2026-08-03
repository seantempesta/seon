---
type: prd
status: active
tags: [prd, testing, agent, runtime]
---

# Accretion testing: green-to-install

## Decision

Owner-ruled 2026-08-03 (recorded in
[plan/unsettled.md](../sci-execution-runtime/plan/unsettled.md), the evening
rulings block): when an agent installs a durable function, **its tests gate
the install**. The function and its tests evaluate together in a candidate
context; the install lands only when they pass; failure returns as teaching
feedback carrying the failing case, and nothing installs. This is the
mechanism that makes agent-written code trustworthy by construction rather
than by review.

The complete ruling set this PRD implements:

1. **Green-to-install** — tests gate, never advise, at the install seam.
2. **Test linkage is derived + explicit override** — the test→function call
   edge is an indexed fact (landed: `093670eff`, tests own `:seon.fn/calls`),
   and `:seon.test/subject` declares subjects not reached by calls
   (generative schema properties).
3. **Auto-check: 25 seeded cases** — every PURE function (purity derived
   from capability reachability, never declared) with generatable input
   schemas gets `mg/check`-style contract checking automatically: generate
   inputs, run in the candidate context, validate outputs against the
   declared schema. Seed recorded on the receipt for reproducibility. The
   count is a config dial shipping at 25.
4. **Missing example test: advisory warning** — install proceeds with one
   teaching line; never blocks; bootstrap teaches the habit.
5. **Non-generatable new schema: warn + record** — admission warns
   (teaching) and records the derived `generatable?` fact so "which
   functions escape auto-check" stays a query. Custody/object predicates
   legitimately never generate.
6. **Declared error branches: optional, gate teaches** (from the error-model
   ruling) — output specs MAY declare error schemas; an observed undeclared
   error class yields an advisory warning.

## Dependency ledger — everything landed, nothing hypothetical

| Dependency | State | Evidence |
|---|---|---|
| Candidate contexts are ~free | LANDED | SCI copy-on-write fork (`72150fd44` era; fork ≈ 0.7 µs vs 636 ms rebuild, measured 2026-08-02) |
| Test→function call edge | LANDED `093670eff` | tests own `:seon.fn/calls`; reachability query in the program graph |
| Contract installation seam | LANDED | `install-function-contract!` at the one ctx-install seam (`src/seon/sci/eval.clj`) |
| Guarded invocation kernel | LANDED | one kernel, two entrances (`04fe5f247`); candidate evaluation arms through it |
| Schema admission gate | LANDED `981fac9ea` | `seon.schema.admission/admit` — entrance 2 is THIS pipeline's declaration seam |
| Generatability | PROBED | plain data schemas generate; predicate schemas refuse; `test.chuck` missing from classpath (add it); `mg/generator` success/failure is the derivable fact |
| Test facts as data | STANDING | `:seon.test` rows carry source; evaluation is deferred, so TEST-FIRST works natively (an unresolvable subject is a *pending* fact, never an error) |

Read before implementing: the rulings block in
[plan/unsettled.md](../sci-execution-runtime/plan/unsettled.md); the
candidate-context probes in
[custody-isolation-design-2026-08-02.md](../sci-execution-runtime/research/custody-isolation-design-2026-08-02.md)
(fork semantics: redefinition leaks to parent — candidate contexts must use
the copy-on-write path, NEVER plain `sci/fork`, for redefinition testing);
[test-call-edge-design-2026-08-03.md](../sci-execution-runtime/research/test-call-edge-design-2026-08-03.md).

## The install flow (one seam, ordered)

At the definition-install seam, for a durable `defn`:

1. **Schema gate** (exists): complete `:malli/schema` required; declarations
   through admission (entrance 2 — the same `admit` function the hook
   calls, now fed parsed declarations).
2. **Assemble the gate set** by query: tests reaching this function
   (direct `:seon.fn/calls` edge, or transitive within a bounded depth),
   plus tests declaring it via `:seon.test/subject`, plus PENDING tests
   whose unresolved subject matches the new name (test-first arrivals).
3. **Candidate context**: copy-on-write fork of the cluster context;
   install the candidate definition (and its contract) there.
4. **Auto-check** (pure + generatable only): 25 seeded generated cases
   against the declared contract, inside the candidate, under the kernel's
   guard (deadline and caps apply — a spinning generated case is cut like
   any eval).
5. **Run the gate set** in the candidate. All green → the REAL install
   proceeds (the candidate is discarded; the true install is the ordinary
   existing path — the candidate proves, it never becomes the cluster ctx).
   Any red → NOTHING installs; the reply carries the failing test name,
   the failing case (seed + generated input for auto-check failures), and
   the expected/actual — rendered as ordinary agent feedback.
6. **Advisories** (never block): no example test; observed error class
   undeclared in the output spec; subject schema non-generatable.
7. **Receipt facts**: the install receipt records the gate-set identities,
   auto-check seed and case count, and outcomes — "what proved this
   function" is a query forever.

## Implementation order

1. **`test.chuck` on the classpath** + the `generatable?` derived fact +
   admission advisory (ruling 5). Small, independent.
2. **The gate-set query** — one contracted function: function ident →
   gate set (edge tests + subject tests + pending test-first facts), from
   one database value. The in-server runner and impacted-test selection
   share it.
3. **Candidate-context evaluation** — evaluate test facts + candidate
   definition in the copy-on-write fork through the kernel; result shape
   is the runner's structured report (shared with in-server tests).
4. **Auto-check** — the 25-case generative contract check for derived-pure
   candidates; config dial; seed on the receipt.
5. **Wire the install seam** — green-to-install becomes the behavior;
   failures render as teaching feedback; advisories attach.
6. **Pending-subject test facts** — test admission marks unresolvable
   subjects pending instead of erroring; the gate-set query picks them up
   when the subject arrives. Test-first becomes a documented agent flow in
   the bootstrap.

## Falsifiers

- A function whose linked test fails does NOT install; the reply names the
  failing assertion; the cluster ctx is byte-identical to before the
  attempt (candidate leaked nothing — probe var identity and count).
- A REDEFINITION tested in a candidate does not alter the parent's
  behavior until the real install (the copy-on-write guarantee, probed
  through the running cluster).
- `(defn add [a b] 1)` with int schemas: auto-check FAILS it only if an
  example test exists (contract-shape passes; the advisory teaches) — the
  honest boundary between shape and meaning stated in the ruling.
- A test-first test fact with an unresolved subject sits pending, then
  gates the function when it arrives; red-first then green-to-install.
- The same seed reproduces the same auto-check failure.
- An effectful function (derived capability reachability) gets NO
  auto-check and its receipts say so.
- Install latency: candidate + 25 cases + typical gate set lands under a
  measured budget on the live cluster (record the number; the 0.7 µs fork
  and 4 ms evals say sub-second is realistic — MEASURE, do not assume).

## What not to build

- no second runner: candidate evaluation reuses the kernel and the
  structured report shape shared with in-server tests (two substrates,
  one result shape — the recorded conflation warning);
- no naming-convention test discovery, no hand test registry;
- no required example tests, no junk-test incentive (ruling 4's reasoning);
- no per-agent gating policy — one cluster-wide behavior, config-dialed;
- no auto-check for effectful or non-generatable functions — their gating
  is their linked tests, honestly recorded.

## Graduation

An agent in a live cluster: writes a failing test (pending), writes the
function, watches the install refuse on the red case with readable
feedback, fixes the function, sees green-to-install land it, and a later
turn queries the receipt to see what proved it — all through ordinary
evals, no operator intervention. The full gate green with the pipeline
active for every durable install in the suite's agent-shaped tests.
