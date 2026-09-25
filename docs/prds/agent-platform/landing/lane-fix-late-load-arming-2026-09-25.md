---
type: landing
status: landed-unverified (root ruled option 1; src/seon/test.clj granted)
lane: fix-late-load-arming (Opus 5.5)
created: 2026-09-25
source: docs/prds/agent-platform/landing/lane-fix-instrument-f2-regressions-2026-09-25.md item 3
---

# Late-loaded namespaces run unarmed (2026-09-25)

## Broken or unproven first

- **No test was run** (LOAD RULE). The arming branch of `arm-first-loads!` was not exercised on default,
  because exercising it arms default's Vars. Root's runs below are the proof.
- **Already-loaded, unarmed test namespaces stay unarmed.** On default today that includes
  `seon.instrument-test`. The fix arms only the namespaces a request loads for the first time. An adoption of a
  changed namespace or a restart covers the rest. So `arm-prefix-contract!` in `test/seon/instrument_test.clj`
  stays for now.
- Profiling cells armed on this path carry no definition digest. Adoption's `definition-digests` is private in
  `cluster.clj`, which is held.
- The wrapper compiles against the request database's carried projection. That is default's database for a
  default request. For a candidate-branch request it is the candidate's projection.
- **RESET NEEDED: no.**

## Fix (root ruling: option 1)

`seon.test/arm-first-loads!` (`src/seon/test.clj`) is called in `run` after exclusions and before the reach
read. So it runs once per request and never inside a member body.

- It keeps the runnable `:core` test symbols whose namespace `find-ns` does not find, and requires those
  namespaces.
- It then makes one `instrument/apply!` call with `:seon.instrument/changed-identities` set to exactly those
  namespaces' interns, and nothing else.
- The call passes adoption's inputs: `:seon.config/on-core-error`, caps and max evidence bytes from the
  request's `effective`, the carried projection under `schema/call-with-projection`, and the context's
  `commit-fault!` (the same expression as `cluster.clj:2654`).
- A registration error is returned as the request's answer. `run`'s output schema adds
  `:seon.instrument/registration-error`.
- Cost when nothing is freshly loaded: 543 loaded-namespace members took 0.50 ms, and an unknown symbol 0.45 ms
  (one pull). Measured with a scratch-namespace copy (`lane-late-load`, removed afterwards).
- Net: src +33/−1 (docstring and contract included). New regression `test/seon/test/first_load_arming_test.clj`
  (+19). It is a namespace that no boot or adoption loads, and its body asserts its own contracted Var is armed
  and that an invalid input refuses with `:seon.instrument/check :input`.

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

## Root runs

1. Publish and adopt `src/seon/test.clj` and the new test file.
2. Before running anything else, check that `(find-ns 'seon.test.first-load-arming-test)` is nil.
3. `bin/test-check default --ns seon.test.first-load-arming-test`. Expect green. Before the fix, `identity-int`
   returns the string.
4. `bin/test-check default --ns seon.instrument-test` shows the change caused no regression there.
5. Repeat the census probe from the evidence section. Expect `seon.test.first-load-arming-test` to show 1 of 1
   armed.

## Out of scope finding (root records script/dev arming separately)

`script/` and `dev/` namespaces, such as `seon.operator` with 27 contracted Vars, are never armed, because no
boot or adoption selection includes them. `docs/seon/issues/` was not searched for an existing issue, and none
was filed from this lane. Root should decide whether those namespaces are program at all.

