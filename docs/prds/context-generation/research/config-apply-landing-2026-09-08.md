---
type: research
date: 2026-09-08
status: incomplete
tags: [config, operator, test]
---

# Config apply landing — 2026-09-08

Verification stopped at the concurrent runner boundary, as the assignment requires.
The implementation is in the working tree for review; the issue remains open. No protected
turn-cut path was edited and no protected-path hunk is required.

Read `AGENTS.md` and
`docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md`
end to end, including the binding default-cluster and lane sections. Applied the
data-oriented Clojure, config, and testing skills.

## Change and dependency ledger

- `script/seon/fresh_operator.clj`: config apply sends a path string to
  `seon.config/apply!`, with public `seon.operator/connection` supplying the
  connection. No manifest value is emitted as executable source. Both config
  apply and the start command's manifest reader resolve paths from the working
  directory. `bin/seon` preserves that directory.
- `src/seon/config.clj`: adds a contracted file arity to the existing `apply!`.
  Its existing EDN reader admits exactly one map. A document equal to the shipped
  document selects the existing defaults compiler, including initialization;
  other files use existing sparse-overlay validation. The one-argument API stays
  intact. This deliberately does not admit arbitrary initialization in overlays.
- `test/seon/dev/fresh_operator_test.clj`: a relative/absolute manifest read
  regression and an extension of the existing real init/start operator drill.
  The drill uses an isolated root and repository-relative `config/default.edn`,
  reads back all expected config decisions, then verifies adopted/published commit
  equality. It runs in the explicit subject namespace, including its long tests.
- Path semantics: `reference-code/babashka/fs/src/babashka/fs.cljc:179`,
  `absolutize` delegates to Java `Path.toAbsolutePath`.
- Quoted data semantics: `reference-code/clojure/src/jvm/clojure/lang/Compiler.java:2456`,
  `ConstantExpr` retains quoted values. The final design removes manifest values
  from source entirely instead of quoting the expanded manifest.
- Existing authorities: `src/seon/config.clj` owns `read-edn-map`,
  `validate-layer`, `default-document`, `compile-manifest`, `apply-compiled!`;
  `src/seon/operator.clj:154` owns public connection selection.

## Evidence and tallies

Initial `git status --short` was empty. `bin/seon status` reported default alive,
PID 85105, web `http://127.0.0.1:7994`, store footprint 0.35 GiB. MCP JVM evaluation
with no root/cluster returned 2 for `(+ 1 1)`.

A live JVM probe evaluated quoted private/public symbol data and returned:

```clojure
{:source "(quote #:probe{:private seon.web.search/organic-results, :public clojure.core/inc})"
 :equal? true}
```

An intermediate quoted-manifest implementation reached `seon.config/apply!`
but refused its input contract at
`[:seon.config/manifest :seon.config.flow.compute/concurrency]`:
`should be an integer`, offending `:seon.config/available-processors`.
That explains why file admission belongs in the config owner and why the final
file arity recognizes the shipped document separately from a sparse overlay.
No successful final live config apply is asserted.

The edit hook published current-src commit
`6aa04a50-36c5-5310-bad8-1816bdb4cae1`, digest
`6285b86dea7f5f20f45d8e662f8dba294b76b01b8e22d5a71f9104f6829ed9bd`.
The modified files have no blocking clj-kondo findings; existing shadowed-var and
unused-binding warnings remain. `git diff --check` passed for the three code files.

`bin/test seon.config-test seon.dev.fresh-operator-test` exited **1** during
dependency preparation, before test launch: **0 tests ran; no pass/fail assertion tally**.
Its root was `tmp/test-runs/run.dlkHIE`, launcher PID 92827, start
`2026-09-08T17:48:17Z`, HEAD `42f1fcc6b2c3447971ecf1532a1c256d75f0549d`.
The final output was exactly:

```text
ln: /Users/sean/src/seon/tmp/test-runs/run.dlkHIE/target/dev-dependency-classes/7ae7f2d588008f7a2045d325a853095c379ff09f65337924709255d5d5e593e3: File exists
```

The preceding census reported tracked directories deleted and their linked
replacements added. There was no `runner-pid` recorded. At observation,
`bin/test:453-455` replaced the target link and created the dependency-classes link;
`bin/test` and `src/seon/test/runner.clj` had concurrent runner-paths edits.
This names the observed boundary without inferring which concurrent filesystem
operation produced the existing link. Full transient output:
`tmp/config-apply-subject-gate.log` (721,987 bytes, 8,341 lines).

An already-running `bin/seon init --dev default` also exited **1**. It reached
loaded definitions, SCI acquisition and JVM instrumentation, then refused:
`Source changed during development adoption; the next edit must converge it.`
Full transient output: `tmp/config-apply-adopt.log`. Adoption convergence and browser
paint are therefore unproven. No refork or provider call was performed.

## Unfinished and files

Stop rule applied immediately on the subject gate failure. Bare `bin/test` and
`bin/test --platform` were not run. No other lane was messaged, resumed, or edited.
Both launched shell sessions exited; no lane-owned background shell remains.
The holderless test root was removed without following its symlinks after recording
its evidence here; the two output logs remain in project-local `tmp/`.

Required next proof, after the runner boundary is repaired: run the explicit
subject namespaces, bare gate and platform gate; adopt the current config owner;
apply `config/default.edn` to default; run `bin/seon init --dev default`; observe
equal adopted/published commit IDs and confirm symbol-valued config remains data.
Then resolve/archive the config-apply issue. The config skill also contains stale
line references and retired terminology; that documentation was not changed in
this bounded lane.

Files touched are the three code files above, this landing note, and
`docs/seon/issues/config-apply-fails-on-a-private-var-in-its-prepl-form.md`.

The path-limited commit attempt was blocked by an existing `.git/index.lock`.
Three subsequent attempts to add only this landing note met the same lock; it
was not removed and no other Git process was interrupted. **No commit was
created by this lane.** All five paths remain available for the owner's commit.
