---
type: issue
status: open
severity: defect
created: 2026-09-23
tags: [issue, hook, instrument, replaced-roots, default, agent-platform]
---

# The edit hook `load-file`s the contract checker into default; `replaced-roots` then reports 15 false replacements

This note owns the finding "15 `seon.contracts-compile-test` Vars have replaced
roots" from the plan status audit
(`docs/research/agent-platform/plan-status-audit-2026-09-23.md`, "Live defects").

## Evidence (default pid 90963, read-only MCP)

- `runtime_status` at about 04:19Z listed 15 `:seon.dev.mcp/replaced-roots`, all in
  `seon.contracts-compile-test`. In every entry `:root-class` equals
  `:expected-class` (for example `seon.contracts_compile_test$check`), and
  `:loaded-file` is the absolute path
  `/Users/sean/src/seon/test/seon/contracts_compile_test.clj`.
- `logs/hook-debug.log` shows `CONTRACT_COMPILE | pre|post | <path> | available`
  for every Clojure Edit or Write by a Claude lane since boot. There were six
  lines between 04:07:51Z and 04:19:35Z, for `namespace_agent_loop_test.clj`,
  `test_support.clj` and `save_gate_test.clj`, at 98–149 ms each.
- At 04:29Z, `(frequencies (map (comp :file meta) (vals (ns-interns (find-ns 'seon.contracts-compile-test)))))`
  answered `{"seon/contracts_compile_test.clj" 19}`, and `replaced-roots` over
  its rows answered 0 entries. A later ordinary `require` (a test-check run or
  an adoption) had reloaded the namespace from the classpath, which cleared the
  report.

## Cause (verified in source)

- No test replaced these roots, and none were replaced. The cause is the Claude
  Code edit hook:
  - `bin/seon-hook` `run-contract-compile` (bin/seon-hook:557-600) sends
    `(load-file "<abs>/test/seon/contracts_compile_test.clj")` (line 577) to the
    live root through `operator/live-root-value!`.
  - It sends it on every PreToolUse and PostToolUse edit of a contract source
    (`validate-contract-edit` and `contract-compile-feedback`, lines 947-1000).
  - `load-file` compiles the same source, but records the Var's `:file` as the
    absolute path.
- `seon.instrument/replaced-roots` (src/seon/instrument.clj:1062-1095) tests
  `(.endsWith relative-path loaded-file)`. A relative path never ends with an
  absolute path, so every Var loaded this way is reported, even though its class
  is the namespace's own compile.
- This is still a `load-file` into default. AGENTS forbids that for default's
  own Vars, and the test namespace is a program row of default. The hook's
  docstring gives the reason as "the live JVM may run a published snapshot whose
  classpath has no copy of it". That reason is obsolete: default runs from the
  checkout, and the namespace resolves on its classpath as
  `seon/contracts_compile_test.clj`.

## Live or historical

This recurs intermittently. The report appears after each Claude edit of a
Clojure file and clears at the next ordinary reload of that namespace. The
hook's publication step is off (owner, 2026-09-23), but this contract-compile
step still runs.

## Cost is proportional to

Each edit recompiles the whole checker namespace (about 100 ms, twice per edit).
Work proportional to one file is repeated for every edit, whether or not the
checker changed.

## Smallest fix at the owner (not implemented)

1. In `bin/seon-hook:577`, replace `load-file` with
   `(require 'seon.contracts-compile-test)`, then resolve `check`. The checker's
   freshness belongs to the one hot path (`bin/seon init --dev default --changed`,
   which uses `require :reload`), not to the hook. The obsolete snapshot sentence
   goes with it.
2. In `replaced-roots`, compare files the way Clojure records them: a Var whose
   class is its own compile and whose `:file` names the same source, whether
   classpath-relative or absolute, is not replaced. Either normalize the
   absolute `:file` against the classpath roots, or accept
   `(.endsWith loaded-file relative-path)`.
3. Add one regression: a namespace `load-file`d from its own path reports no
   replaced roots, and an `alter-var-root` still does.
