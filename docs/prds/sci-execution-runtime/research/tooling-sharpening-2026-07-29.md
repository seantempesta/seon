---
type: research
status: complete
tags: [research, tooling, testing, agent]
---

# Development tooling sharpening — 2026-07-29

## Verdict

The skill drift emergency is structurally closed: Codex, Claude, and runtime
import now traverse one physical `.agents/skills/` directory. The stale
generator and its dying-operator command are deleted, `browser-automation` is
deleted, and the fresh `bin/test` gate refuses either consumer link being
replaced or redirected. The repaired corpus includes the verified
`seon-flow-architecture` skill and all six references at every consumer path.

The named tool sweep also removed a duplicate REPL launcher, made failing tests
and live Codex lanes identify themselves, and made contradictory lint requests
fail before partial work. The remaining defects are bounded and filed:
dead CLJS tools advertised by the MCP, two protected cluster docstrings naming
the deleted launcher, the pod-era loadable-skills component page, the retained
hook/classpath boundary, and the issue-index checker/schedule disagreement.

## Dependency ledger

| Boundary | Selected implementation | Evidence read |
|---|---|---|
| Skills | POSIX directory links; no generator | owner ruling 29 in `plan/README.md:862-888`; commits `f60a5ea79`, `1ee741672` |
| Fresh tests | Clojure CLI 1.12.5.1654; `clojure.test` | `bin/test`; `test/seon/test_runner_failure_fixture.clj`; `test/seon/test_runner_test.clj` |
| Development scripts | Babashka 1.12.212; Bash; POSIX shell | the named `bin/` scripts and their actual launchers |
| Lint | clj-kondo 2025.04.07; Splint 1.22.0 | `bin/lint` |
| CSS | Tailwind CLI 4.1.18 through Bun | `bin/css`; installed CLI help |
| Development MCP | newline-delimited JSON-RPC 2024-11-05; JVM io-prepl | `.mcp.json`; `.codex/config.toml`; `bin/mcp-server-cljs`; `script/seon/dev/mcp.clj` |
| Operator | one `seon.fresh-operator` entry | commits `c073093e2`, `26a5ef07f`; `bin/seon`; compatibility `bin/seon-fresh` |

No `src/` owner was edited. The cluster-priming and
operator-reconciliation lanes retained ownership of the protected cluster,
program-graph, schema, and fresh-operator changes throughout this sweep.

## Skill emergency

The initial tree really had three divergent copies. Today's independently
verified content was under `.agents/skills`, while the nominal runtime corpus
and Claude adapter were stale. During grounding, the owner settled a simpler
contract in ruling 29: one real directory, linked at every consumer path, with
no generated adapters or drift detector.

The resulting filesystem contract is:

| Path | Role | Current form |
|---|---|---|
| `.agents/skills/` | one checked-in corpus and Codex discovery | real directory |
| `seon-skills` | runtime import path | link to `.agents/skills` |
| `.claude/skills` | Claude discovery | link to `../.agents/skills` |

`stat -L` resolved all three paths to inode `30092077`. The live gate checks
both the link shape and the resolved directory before loading Clojure. A
repository-local falsifier replaced both links with directories and observed
the gate fail with:

```text
bin/test: skill paths must be links to the one .agents/skills tree
bin/test: restore the tracked links; edit skills only under .agents/skills
```

Commit `f60a5ea79` formed the linked tree and deleted
`browser-automation`. Commit `1ee741672` deleted
`script/seon/dev/skills.clj`, its old test, and the old CLI's `skills sync` /
`skills check` surface. Commit `9e79b77e9` put the structural refusal in
`bin/test`. The Datastar skill's remaining compatibility-alias examples were
then sharpened to teach only `bin/seon`. The active program ledger's browser
verification instruction now points to `datastar-web-ui` plus built-in browser
control, and the ignored local Claude permission entry for the deleted skill
was removed.

Current falsifiers find no `browser-automation` reference inside any consumer
skill tree and no live `seon.dev.skills`, generator command, or adapter-drift
owner. The former Datastar adapter-drift issue records the final linked-tree
resolution.

## Tool sweep

| Surface | Observation | Disposition |
|---|---|---|
| `bin/seon` | Operator-unify commit `c073093e2` already routes the public command directly to `seon.fresh-operator`; commit `26a5ef07f` is the concurrent reconciliation landing. | Read and coordinated; no edit in this lane. |
| `bin/seon-fresh` | Now an explicit compatibility alias to the one operator, not a second implementation. | Retained by its owning lane; skills teach `bin/seon`. |
| `bin/test` | Unknown options were accepted as namespace names, and the final failure summary omitted test vars. The skill filesystem had no live invariant. | Fixed in `9e79b77e9`: help/argument refusal, linked-tree gate, selected-namespace failing-var inventory. Resolved issue: `bin-test-summary-omits-failing-test-names.md`. |
| `bin/repl` | Directly started a cluster outside the public operator while MCP errors taught that second path. | Deleted and MCP remedy repointed in `9e79b77e9`. Resolved issue: `bin-repl-duplicates-the-fresh-operator-start-path.md`. |
| `bin/codex-agent` | Status did not assemble lane identity and placeholder summaries looked landed; absent watch/summary targets failed generically. | Fixed in `9e79b77e9`. Resolved issue: `codex-agent-status-hides-running-lane-identity.md`. |
| `bin/lint` | Conflicting exclusive modes, `--fix --kondo`, absent paths, and missing executables could begin partial or misleading work. | Fixed in `9e79b77e9`. Resolved issue: `bin-lint-conflicting-modes-can-report-a-false-green.md`. |
| `bin/issues-index` | `--check` exits 1 and tells the user to regenerate, but regeneration overwrites the ranked schedule required by the issue authority. | Not widened here. Existing open issue: `issues-index-checker-disagrees-with-the-schedule-convention.md`. The schedule entry is coordinated after concurrent issue owners finish. |
| `bin/css` | Dependency failures name the missing Tailwind CLI or Bun and the install command; installed `--help` succeeds. One transform owns the truth. | No defect found. |
| `bin/seon-hook` | Empty input returns a valid continuation, but real Markdown/Clojure events can catch linter classpath failures and limp green. A direct Babashka load of `seon.dev.markdown` failed because fresh `seon.schema` requires Datahike outside the hook classpath. | Existing blocker retained: `finish-deleting-the-old-operator-classpath-from-retained-tooling.md`. |
| MCP registration | One implementation serves both clients, but `tools/list` advertises four deleted CLJS controls and the launcher/server names retain CLJS terminology. | Filed `development-mcp-advertises-deleted-cljs-tools.md`; client restart and registration breadth make this a separate wave. |

The sweep also found two `bin/repl` references in the concurrently protected
`src/seon/cluster.clj`; they are filed in
`fresh-cluster-docstrings-teach-deleted-bin-repl.md`, not edited. Removing the
last generator pointer exposed that `docs/seon/components/loadable-skills.md`
still describes a deleted pod importer while architecture documents retain a
future target; that root cause is filed in
`loadable-skills-component-describes-deleted-pod-importer.md`.

## Executable evidence

| Probe | Result |
|---|---|
| `bash -n bin/test`; `sh -n bin/codex-agent`; `bash -n bin/lint` | passed |
| `bin/test --help` | passed |
| `bin/test --bogus` | exited 64 and named the unknown option |
| `bin/test seon.test-runner-failure-fixture` | deliberately exited 1; final inventory named `seon.test-runner-failure-fixture/failing-example` |
| Linked-tree negative falsifier | exited 1 before Clojure load and printed the exact repair |
| `bin/codex-agent status` | named all live lanes with PID, elapsed time, and command; omitted in-progress summaries |
| missing `bin/codex-agent summary` | exited 1 and named the expected file |
| conflicting `bin/lint` modes; `--fix --kondo`; absent path | exited 64, 64, and 66 with actionable messages |
| `bin/lint --kondo script/seon/dev/mcp.clj` | zero errors and six standing shadow warnings; clj-kondo exits 2 on warnings |
| MCP `initialize` plus `tools/list` | protocol initialized; advertised `eval_cljs`, `create_session`, `stop_session`, and `reload_deps` despite their error-only implementation |
| `printf '{}\n' \| bin/seon-hook` | returned `{"continue":true}` |
| direct Babashka `seon.dev.markdown` load | failed at fresh `seon.schema` because `datahike.api` is absent, reproducing the retained hook/classpath issue |
| Markdown validation through an explicit mixed JVM classpath | all eight new research/issue notes passed after replacing one single-use `lint` tag |
| `bin/css --help` | passed through Tailwind CLI 4.1.18 |
| `bin/issues-index --check` | exited 1 with the existing schedule/generator mismatch |
| `git diff --check` | passed before the final documentation checkpoint |

The first focused `bin/test seon.test-runner-test` retry reached another
lane's protected program-indexing work and failed while transacting a nil
`:seon.ns.require/refers` value from `seon.fn/index!`. That is not a tooling
failure and was not patched here. The final focused and complete checkpoint
below records the coherent-tree retry.

## Final checkpoint

The complete `bin/test` checkpoint was not run because another lane's
in-flight reset drill destroyed the source-freeze boundary. While the
cluster-priming lane remained active, its transcript reported that reset-test
cleanup followed repository symlinks. At that exact point:

- `src/seon/cluster.clj`, `src/seon/fn.clj`, `src/seon/schema.cljc`, the rest
  of the maintained `src/` runtime, and ten `reference-code/` dependencies
  were absent from the filesystem;
- `git status --short` reported those paths as deletions, alongside the
  cluster-priming lane's modified operator and boot tests; and
- `ls` confirmed that `src/seon/cluster.clj`, `src/seon/test/runner.clj`, and
  `reference-code/datahike` did not exist.

This is the user's explicit stop condition: another lane's in-flight breakage
blocks the gate. The tool-sharpening lane did not run a misleading test,
restore or edit any owned source, resume or message the other lane, or wait for
an uncommitted repair and call it a checkpoint. The last focused attempt before
this event remains the protected nil-transaction failure described above; no
full-suite verdict is claimed.
