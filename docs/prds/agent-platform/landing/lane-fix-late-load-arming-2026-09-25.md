---
type: landing
status: diagnosed-stopped (fix owner outside this lane's paths)
lane: fix-late-load-arming (Opus 5.5)
created: 2026-09-25
source: docs/prds/agent-platform/landing/lane-fix-instrument-f2-regressions-2026-09-25.md item 3
---

# Late-loaded namespaces run unarmed (2026-09-25)

## Broken first

- **Not fixed.** The owner of the missing arming is the lazy test-namespace load in `seon.test/resolve-test`
  (`src/seon/test.clj:1364`). It is not `src/seon/instrument.clj`. The other candidate owner is boot's program
  selection (`src/seon/cluster.clj:2412-2419`), and `src/seon/cluster.clj` is held by status-counts-followups.
  `instrument/apply!` already has the seam that is needed: `:seon.instrument/changed-identities` arms exactly
  the Vars it is given (`src/seon/instrument.clj:900-920`). So instrument.clj needs no change.
- No code edited, no test run (LOAD RULE). **RESET NEEDED: no.**

## Evidence (read-only JVM probes on default, 2026-09-25)

Armed means the root carries `:malli.instrument/original`. Contracted means `:malli/schema` metadata on a bound fn Var.

| loaded namespaces | namespaces with contracts | contracted Vars | armed |
|---|---|---|---|
| src and other non-`-test` | 111 | 1,952 | 1,908 |
| `-test` | 8 | 46 | 3 |

- `seon.instrument-test` has 12 contracted Vars and 0 armed. For comparison, `seon.instrument` has 32 of 32
  armed and `seon.test` has 45 of 45.
- The 3 armed test Vars are `seon.test-runner-test/throwaway-test-var`, `seon.test.overlay-world-test/admit!` and
  `seon.test.scoped-first-run-test/install!`. All three namespaces changed after they were loaded, so adoption
  re-armed them.
- The published rows list 104 `src/` namespaces, all 104 loaded. They list 347 `test/` namespaces, of which 46
  are loaded. 70 test namespaces declare functions with arities, and 56 of those are not loaded.
- Other non-test Vars that are unarmed are outside the boot selection. They are `script/` and `dev/` namespaces
  such as `seon.operator` (0 of 27), `seon.dev.docstring` (0 of 4) and `seon.dev.markdown` (0 of 6), plus the
  fixture `seon.test-runner-failure-fixture` (0 of 1). `seon.sci.admit` has 11 of 12 armed; that one was not
  investigated.

## Load paths

1. **Boot arms.** `arm-host-program!` (`src/seon/cluster.clj:2423`) requires the `program-namespaces` and then
   calls `apply!` with no changed identities. `apply!` then collects contracts from every loaded namespace
   (`collect-contracts!`, instrument.clj:815). But `program-namespaces` keeps only rows whose file is under
   `src/` (cluster.clj:2419). ed62a3e06 did this on purpose: "test namespaces load when a test request needs
   them."
2. **Adoption arms only namespaces that were already loaded.** `load-development-definitions!` filters its set
   through `reloadable-namespace?`, which needs `find-ns` (cluster.clj:2123-2141). The interns of the reloaded
   namespaces then become arming identities (cluster.clj:2471-2506). A test namespace that changes before it is
   ever loaded is skipped. It stays unloaded until a test request loads it without arming it.
3. **A test request does not arm.** `resolve-test` loads a `:core` test through
   `(with-test-loader loader (fn [] (requiring-resolve test-symbol)))` inside the member body
   (test.clj:1364, called from test.clj:1812). Nothing arms the namespace it just loaded. This is the path that
   left `seon.instrument-test/prefix-contract` unarmed. It also explains panic-print-sink item 2: adoption does
   not load a test namespace that a request names.
4. **A REPL `require` does not arm.** That path is outside adoption by design.

## Fix options (root rules; recommendation marked)

1. **The test request loads and arms before any member body (recommended).**
   - What: in `seon.test/run` (test.clj), after selection and before members start, require each selected
     `:core` member namespace that `find-ns` does not find. Then call `instrument/apply!` once with
     `:seon.instrument/changed-identities` set to those namespaces' interns. Pass the request's `effective`
     policy (test.clj:1960), the default cluster's carried projection and the context's `commit-fault!`, the
     same inputs adoption passes at cluster.clj:2652-2668.
   - Guarantee: a lazily loaded namespace is armed once, outside any body, against the adopted projection. Each
     later request finds it loaded and pays nothing.
   - Cost: about 15-20 lines in test.clj. The first load pays one arm call, proportional to that namespace's
     interns.
   - Sacrifice: `resolve-test`'s `requiring-resolve` stays as a lookup, and a class loader other than the base
     loader (archive snapshots) has to go through the same step.
2. **Boot loads every program namespace, `test/` included.**
   - What: drop the `src/` filter at cluster.clj:2419. Adoption then reloads and re-arms test namespaces like
     any other.
   - Guarantee: "loaded namespaces derive from files" holds with no lazy path. The ruling in ed62a3e06 is
     reversed.
   - Cost: 1 line, but boot gains about 301 namespace loads, with time and memory not yet measured. A load-time
     side effect in any test namespace would run at boot.
   - Sacrifice: ten-second start is at risk. This needs a measured clock row first.
3. **`resolve-test` arms right after its own first `require`.**
   - What: about 10 lines in test.clj.
   - Rejected. It changes JVM-global roots from a member's body thread. The wrapper would capture the member
     branch's projection and install it for the whole JVM, which breaks "one wrapper per adoption"
     (instrument.clj:790) and "no reload may run under an evaluation".

Ownership needed: option 1 needs `src/seon/test.clj` (the ledger shows it free) plus its regression file.
Option 2 needs `src/seon/cluster.clj` (held).

## Regression for root (lands with the chosen fix)

Use a test namespace that no boot path loads, with one contracted function, for example a
`test/seon/instrument/late_load_fixture.clj`.

- The regression runs one `seon.test/run` `:named` request for a test in that namespace.
- It asserts that, after the request, the fixture's contracted Var root carries `:malli.instrument/original`,
  and that calling it with an invalid argument throws `:malli.core/invalid-input` instead of returning.

## Root runs (after the fix lands)

- `bin/test-check default --ns seon.instrument-test`. `prefix-contract` should be armed without
  `arm-prefix-contract!`, and that helper can be retired in the same slice.
- `bin/test-check default --test <regression symbol>`.
- Repeat the census probe above. Expected: armed Vars in `-test` namespaces equal their contracted count for
  every loaded test namespace.

## Out of scope finding

`script/` and `dev/` namespaces, such as `seon.operator` with 27 contracted Vars, are never armed, because no
boot or adoption selection includes them. `docs/seon/issues/` was not searched for an existing issue, and none
was filed from this lane. Root should decide whether those namespaces are program at all.

Net: code +0/−0; this note only.
