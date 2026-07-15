---
type: research
status: active
tags: [research, agent, component]
---

# Inspect reproducibility boundary — 2026-07-15

## Decision

A native Inspect `.eval` is the evaluation authority, but the current file is
not yet a reproducibility certificate. It preserves the selected task,
arguments, scorer names, sample ids, exact Seon prompt/reply evidence, and the
final database coordinate. It does not bind the clean Inspect and Inspect
Evals source trees, Python lock, Seon artifact/config, provider endpoint, or
local model weights that produced those bytes.

P1 therefore has two ordered owners:

1. `src-inspect-ai` validates and stamps one run identity before it constructs
   a task, and makes the resulting native `.eval` mandatory evidence.
2. The existing `bin/seon` operator adds an ownership-fenced per-sample lease.
   Inspect consumes that lease; it does not implement another supervisor.

Scored comparative claims remain serial against the explicitly provisioned
ACME URL until both owners pass. The current static path is useful for P0 and
diagnostic development, but it is not a concurrency or cleanup proof.

## Dependency ledger

| Dependency or mechanism | Exact identity observed | Maintained source and call sites | Required proof |
|---|---|---|---|
| Inspect AI | Superproject gitlink and checkout `05322696a0f784ec399ef6abbafd3d2a250ea9cc`, tag `0.3.246`; installed distribution `0.3.247.dev0+g05322696a.d20260715`; local-directory source in `pyproject.toml` and `uv.lock` | `reference-code/inspect-ai/`; `inspect_ai._eval.task.log.TaskLogger`; `inspect_ai.log.read_eval_log`; `seon_inspect.catalog.run_bench` | Selected gitlink, checkout `HEAD`, clean recursive submodules, installed source, and recorded identity agree before task import. |
| Inspect view submodule | Parent expects `eccde6b7c67c8d07eef224c1a8a3ff85c51eb1e2`; intentional overlay checkout is `f3588038f399de82eec7d189f82a31402153f553`, tree `9fd112592b2f4a3986797f53aa52500a943b66d8` | `reference-code/inspect-ai/src/inspect_ai/_view/ts-mono`; `evaluation-sources.lock.json`; `source_admission._nested_source_identities` | Admission records and verifies the parent coordinate, overlay revision/tree, and nested cleanliness independently. Any change fails before task construction. |
| Inspect Evals | Superproject gitlink and checkout `97c99f5f6507fc5d1449fe3247f267d591f64350`, tag `v0.14.3`; installed editable distribution reports `0.0.1.dev1+unknown.gce900d638` | `reference-code/inspect-evals/`; `seon_inspect.catalog.BENCHES`; selected upstream task, dataset, and scorer modules | Declare it in `pyproject.toml`/`uv.lock`, synchronize from the selected gitlink, and record the commit independently of generated package-version text. |
| Python dependency closure | `uv.lock` SHA-256 `34f230184c19b2c03d89eba5cdbc10c6509397a051773fca67a6a33b4de800f4`; `openai` is now locked at `2.45.0` with wheel and source hashes | `src-inspect-ai/pyproject.toml`; `src-inspect-ai/uv.lock`; Inspect direct-model paths | A fresh `uv sync --locked` produces the recorded packages; the run records the lock digest and only records the Python provider client when that path is actually used. |
| Dataset freeze | `evals/datasets.lock` SHA-256 `ff2496fa6fcf2efe592335c4d7b31d728c162de10da08ce49dc85cee72231ee1`; global seed `20260702` | `seon_inspect.freeze`; `test_freeze.py`; upstream pin constants under the selected Inspect Evals commit | The run records lock digest, split, sample ids allowed by tier, upstream pin, task args, and corpus content digest. |
| Native Inspect log | Current `.eval` format is a Zstandard ZIP containing `header.json`, sample JSON, summaries, reductions, and journal entries | `inspect_ai.log._log.EvalSpec`; `inspect_ai._eval.task.log.TaskLogger`; `seon_inspect.catalog.save_eval_logs` | Evidence finalization hashes and retains every returned native log; absent location, failed copy, incomplete log, or identity mismatch rejects the run. |
| Static Seon pod solver | Host `urllib` posts to `/agents/run`; Inspect model is deliberately `mockllm/model`; `max_samples=1` | `seon_inspect.solver`; `seon_inspect.catalog`; `seon.web.serve` | Sample metadata retains exact database coordinate and turn bytes, while run metadata identifies the owned target, artifact, config, provider, and real pod model. |
| Pod provider client | npm lock selects `openai` `6.42.0`; maintained source gitlink `reference-code/openai-node` is `6f849f4ff24f70167bf82d37c8c83e3f8b1c5472` | `src/seon/ai/openai_compat.cljs`; `src/seon/ai.cljs`; `/agents/run` model-config projection | Record this client only for the pod path. Do not conflate it with Python `openai` `2.45.0`. |
| Real model | The retained BFCL sample reports `openai-compat`, `Qwen/Qwen3.5-2B`, temperature `0.7`, max tokens `1024`, thinking `false` | `seon.ai/resolved-config`; provider adapter; local MLX/Ollama server outside this repository | Add a separate non-secret runtime identity: endpoint origin, server implementation/version, model revision or weights digest, quantization, and response-reported model identity. A name alone is insufficient. |
| Operator target status | `bin/seon status --edn` derives artifact flavor and digests, process PID/start ownership, and dynamic web/CLJ/CLJS endpoints | `seon.dev.cli/status-value`; `seon.dev.process/status`; artifact manifest v2 | Validate one already-owned target now; extend the same operator with a token-fenced lease for allocation, restart, and release. |
| Inspect Docker sandbox arm | Inspect Evals owns dataset, Docker sandbox, and official scorer; Seon overlays compose, runtime volume, entrypoint, egress, and pod solver | `tasks/swe_bench_seon.py`; `swebench_arm.py`; upstream `inspect_evals.swe_bench` and Inspect Docker sandbox source | Record image digest, sandbox config hash, overlay volume/runtime digest, entrypoint digest, egress stance, and provider/model identity. Tags and volume names are not content identities. |

The current superproject commit observed during the audit is
`e97d6e55d2a87120f187c1d08c9abdde1d93f6bc`. It is intentionally not a
permanent dependency constant; the run gate must derive the selected commit
and dirty closure at execution time.

## Current native `.eval` evidence

The retained successful BFCL `multiple_0` run at
`src-inspect-ai/logs/2026-07-15T01-37-01-00-00_bfcl_fxM4rRBVc2UjXZF9dxDVqT.eval`
demonstrates the exact boundary.

Its `EvalSpec` records:

- `inspect_evals/bfcl`, task version `5`, interface metadata `5-B`, and the
  four selected AST categories;
- solver chain and explicit ACME URL, upstream scorer name and metrics,
  sample id, epoch, Inspect config, and `mockllm/model`;
- package-version strings for `inspect_ai` and `inspect_evals`; and
- the Seon checkout revision as an abbreviated commit plus a dirty boolean.

Its sample metadata additionally records the resolved pod model configuration,
the complete `{database_id, branch, commit_id, t}` point, and the exact ordered
turn evidence. That is sufficient to inspect what this model saw and returned.

It does not record:

- the Inspect or Inspect Evals full commit, nested overlay identity, recursive
  dirty state, or source tree digest;
- the Python lock or dataset-lock digest;
- the Seon artifact manifest or applied config identity;
- the provider endpoint/server implementation or model weight/revision digest;
- the Python `openai` package, because the pod solver does not use Inspect's
  model provider; or
- a mandatory evidence-finalization result proving the returned `.eval` was
  durably retained and hashed.

Inspect's package-version capture is useful but cannot close these gaps. The
current Inspect Evals import resolves to the selected editable checkout while
its installed distribution metadata names an older-looking generated commit.
The current Inspect package was copied from a local directory and its PEP 610
record contains only a `file://` URL, not a VCS revision. Full source identity
must therefore be explicit run metadata validated before task construction.

## Source issue reconciliation

[[../../../seon/issues/inspect-source-dependency-is-not-content-pinned]] remains
open, with two facts updated by current evidence:

- The superproject gitlinks do provide selected source commits, so the
  repository has a content authority. The defect is that `uv` accepts mutable
  sibling-directory bytes without checking them against those gitlinks and
  the run does not record the check.
- The lock now contains `openai` `2.45.0` and hashes. The older claim that the
  package is absent from `uv.lock` is stale. Inspect Evals remains undeclared,
  while the Inspect view overlay requires an identity distinct from its parent
  Gitlink.

The smallest repair is not a second vendoring system. Keep the selected
submodules as reviewed source, declare both Python packages, and put a strict
source/install/lock preflight at the one Inspect run boundary.

## Sandbox and client boundary

There are three materially different execution paths and their identities must
not be merged.

### Static pod path

Inspect owns dataset, solver scheduling, scorer, and `.eval`. The host-side
solver sends the rendered task prompt to an already-running Seon pod. The
Inspect model is a required inert placeholder; generation occurs through the
pod's Node provider client. No Inspect sandbox is selected for BFCL and the
other case-1 tasks. The ClojureScript evaluator catches model mistakes, but it
is not the security boundary; process and database capabilities are.

This path can be reproducible serially once the run records the operator status
snapshot and rejects a target whose artifact/config/model identity changes.

### Inspect Docker sandbox path

SWE-bench keeps the upstream dataset, Docker sandbox, and official scorer. The
Seon arm alters the per-sample sandbox composition by mounting the Seon runtime
and entrypoint, booting the pod in the task container, and selecting provider
egress. The existing sample metadata records several overlay facts, but an
accepted run still needs immutable Docker image, mounted runtime, generated
compose, and model identities.

### Typeahead corpus capture

The current capture path correctly requires explicit web and writer endpoints
and refuses restart without an injected owner. It still sends raw Datahike
forms through the writer REPL and reads checkout-local blob paths. That is a
diagnostic bridge, not an accepted lease consumer. Migration must use the
lease's typed database/debug/blob boundaries and preserve the returned
coordinate and content hashes in the corpus manifest.

## Smallest-owner implementation order

### R1 — Strict run identity before task construction

Owner: `src-inspect-ai` run boundary used by `catalog.run_bench` and the other
accepted runners.

Derive one immutable run-identity map containing:

- full Seon commit plus dirty closure digest;
- `uv.lock` and `datasets.lock` SHA-256 values;
- Inspect and Inspect Evals superproject gitlinks, checkout heads, recursive
  status, source tree hashes, installed versions, and PEP 610 source records;
- selected task/scorer registry names, versions, arguments, source commit,
  dataset pin/content identity, split, and sample membership;
- actual execution path, distinguishing Inspect provider, static pod, and
  Docker sandbox; and
- selected operator artifact/config/provider/model identity when the path is
  live.

The preflight returns data on success and a bounded structured error on
mismatch. It runs before `load_bench_task`, network access, subprocesses, or
model calls. Pass that exact map through Inspect's existing run-level
`metadata` argument; do not invent a sidecar as the authority.

Acceptance evidence:

- a changed or dirty `ts-mono` overlay and undeclared/stale Inspect Evals
  installation fail before benchmark import;
- a fresh `uv sync --locked` from clean selected submodules passes;
- mutating either source, lock, task pin, or selected config makes the same
  probe fail; and
- reading the resulting `.eval` recovers the exact run-identity map unchanged.

### R2 — Mandatory native-log finalization

Owner: replace the permissive behavior in `catalog.save_eval_logs` and route
all scored callers through it.

For every returned log, require a complete native `.eval`, copy it into the
declared evidence directory, compute SHA-256 and size, read it back with
Inspect, and verify its run identity. Return a manifest of retained logs.
Missing location, copy/read failure, incomplete status, or identity mismatch
is an infrastructure failure and prevents scorecard publication. Exploratory
runs may omit an evidence directory only when explicitly marked unscored.

Acceptance evidence:

- permission denial, missing source log, truncated archive, and copy failure
  each fail finalization rather than silently continuing;
- cancellation preserves any valid partial/native log and reports its status;
- a finalized artifact opens independently after the source log is removed;
  and
- `scorecard.append_row` refuses a scored row without the finalized log digest
  and run identity.

The first common execution boundary is now implemented. Standard upstream
benchmarks and Seon-native tasks both call one admitted evaluator that stamps
the exact run identity, invokes Inspect, requires successful native logs,
reopens them, verifies identity equality, and optionally copies them into the
declared evidence directory. `run_native_task` admits before invoking its task
factory, preserves the task's own dataset/solver/scorer, and enforces the
configured one-pod sample ceiling. `milestone_lift` carries the same admission
on its task and samples and rechecks source identity after a live sample. The
focused gate passes 58 tests, including a real native `.eval` read-back. Static
operator artifact/config identity remains R1/P1a work; this change does not
promote a URL or source commit into artifact proof.

### R3 — Honest provider and model identity

Owners: `seon.ai`/provider adapter for facts known inside the pod, and the
configured MLX/Ollama/provider launcher for facts known only by the server.
Inspect only records their returned data.

Keep behavioral configuration in `seon.ai/resolved-config`. Add a separate
non-secret runtime identity containing endpoint origin, provider adapter,
client implementation/version, server implementation/version, selected model
revision or content digest, quantization, and response-reported model identity.
Never record credentials or query parameters. Paid APIs that expose no weight
digest are explicitly marked externally mutable; their name is not presented
as byte-reproducible weights.

Acceptance evidence:

- two local servers advertising the same model name but different weight or
  quantization identities cannot enter one comparison cell;
- endpoint changes are visible without leaking credentials;
- every live sample reports the same declared identity or the run is voided;
  and
- the `.eval` distinguishes Python OpenAI client use from pod Node OpenAI
  client use.

### R4 — One operator lease, then caller migration

Operator owner: extend `bin/seon`'s current config, artifact manifest, process
records, locks, and status projection. Inspect owner: consume the returned
lease in `cluster.py`, `bench_common.py`, and `typeahead_corpus.py`.

The lease record contains a random unguessable owner token, lease id, allocated
cluster/database identity, immutable artifact manifest and config digest,
process PID/start identities, dynamic web/CLJ/CLJS endpoints, and expiry or
cancellation state. Token-fenced create, status, restart, and idempotent release
execute under the existing operator locks. Release stops only matching
PID/start identities and preserves evidence coordinates before removing
ephemeral files. A stale or foreign owner fails closed.

Do not let Python derive process directories, ports, Shadow caches, artifact
paths, or database directories. `cluster.py` becomes a typed consumer of the
operator result and deletes the raw lifecycle helpers once migration is live.

Acceptance evidence:

- two leases allocate disjoint process, endpoint, cache, and database
  coordinates while sharing one immutable artifact safely;
- wrong-token release, stale PID reuse, foreign listener, partial boot,
  restart failure, timeout, and evaluator cancellation never stop another
  lease or the default/ACME target;
- restart preserves lease, artifact, config, database identity, and agent
  read-back while process identities change as declared;
- repeated owned release is a no-op and leaves no owned listener/process;
- one live smoke proves CLJ read-back, CLJS execution, pod execution,
  typeahead capture through typed boundaries, restart continuity, mandatory
  `.eval` finalization, and cleanup; and
- only after this proof may Inspect set `max_samples > 1` through independently
  leased samples.

## Blockers and handoff

The immediate source gate now admits the deliberate Inspect view overlay
without hiding it: the lock separately names the parent coordinate and actual
nested revision, and admission verifies the nested tree and cleanliness.
Inspect Evals is declared and synchronized. The focused source/catalog/native-
log gate passes 34 tests. Finalization now reopens the retained archive,
requires success, and compares the exact admitted identity; corrupt,
incomplete, and wrong-run logs fail. P1a still needs the static ACME
artifact/config/model
identity and bounded scorer evidence before a scored serial claim is complete.

The model gate also needs a launcher-owned artifact identity. The pod can
report resolved behavior config, but it cannot infer which local weight bytes
an OpenAI-compatible server loaded from the advertised model name.

Finally, [[../../../seon/issues/inspect-live-cluster-caller-drift]] remains an
operator dependency. Current status data is a sound foundation for one owned
target, but there is no allocator or token-fenced create/restart/release lease.
This audit makes no runtime-source change and does not operate any cluster.
