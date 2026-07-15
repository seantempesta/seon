---
type: research
status: active
tags: [research, agent, component]
---

# Model-server identity audit — 2026-07-15

## Decision

The remaining formal model identity must enter through the existing native
Inspect admission and finalization boundary. It is not another Seon database
projection and it is not inferred from a model name, process command, or
`/v1/models` response.

`seon_inspect.catalog.run_native_task` already snapshots admitted source and
the semantic Seon target before task construction, snapshots them again after
Inspect publishes the terminal log, writes both identities into that log, and
rejects drift. Extend that same boundary with one required model-server
snapshot. The host-side identity is observed by `seon_inspect.cluster`; the
existing `seon_inspect.solver.require_scorable_pod_state` gate joins it to the
ordered attempt facts returned by the pod. No second runner, supervisor,
sidecar authority, or model-identity database schema is needed.

The first formal local row should use MLX with a dedicated listener and one
absolute Hugging Face snapshot path. Ollama can join the same contract later,
but a mutable tag is not an artifact identity: it additionally needs the
content-addressed manifest digest and loaded digest. Paid or remotely managed
servers that expose no revision or weights digest remain explicitly
externally mutable and cannot support a byte-reproducible weights claim.

This audit closes the design question, not
[[../../../seon/issues/inspect-model-transport-evidence-is-incomplete]]. That
issue closes only after one admitted native `.eval` retains and reopens the
joined identity.

## Dependency ledger

| Dependency or mechanism | Exact audit identity | Source-grounded behavior used here |
|---|---|---|
| Seon checkout | `5fd4b93a38a78122a09553545c241ab87dadfb61` at the read-only audit point | `src/seon/web/serve.cljs` projects ordered attempts from the final immutable database; `src/seon/ai/openai_compat.cljs` retains bounded response model, system fingerprint, and request id. |
| Native Inspect boundary | `src-inspect-ai/src/seon_inspect/catalog.py`, `run_native_task` | Source admission and `static_target_snapshot` run before task construction. The same values are re-read after Inspect returns, written with Inspect's public metadata edit API, compared for exact equality, and reopened by finalization. |
| Inspect capability gate | `src-inspect-ai/src/seon_inspect/solver.py`, `require_scorable_pod_state` | Source-admitted runs already reject absent, malformed, foreign, out-of-order, or drifted model-attempt evidence before task scoring. This is the one consumer that must join server identity to attempts. |
| Inspect AI | `reference-code/inspect-ai` at `05322696a0f784ec399ef6abbafd3d2a250ea9cc`; intentional dirty entry is only the separately admitted `src/inspect_ai/_view/ts-mono` overlay | Inspect records run/sample metadata in the native `.eval`; Seon's pod path deliberately uses inert `mockllm/model`, so Inspect's own model name does not identify the real server. |
| Pod provider client | `reference-code/openai-node` at `6f849f4ff24f70167bf82d37c8c83e3f8b1c5472` | The real generation call is made by the pod, not by Inspect's Python OpenAI client. |
| MLX server | `mlx-lm 0.31.3`, `mlx 0.32.0`, `transformers 5.13.1`, and `huggingface-hub 1.23.0`; installed `mlx_lm/server.py` SHA-256 `cdfcb4ac848636f9927851a0ec7a951584526530cb7832ba58049e4a9144db8b` | `ModelProvider.load` maps only `default_model` to the CLI model. Any other request `model` is passed to `mlx_lm.load` as a path/name. The response `model` merely echoes `requested_model`; its system fingerprint is MLX-LM version, MLX version, platform, and GPU architecture. `/v1/models` scans the Hugging Face cache and adds the configured path, so it is not a loaded-model statement. |
| First MLX artifact | `mlx-community/Qwen2.5-Coder-0.5B-Instruct-4bit`, snapshot revision `6b16732e5af5cd9bd600186ad59fa618867ef7a4` | The complete absolute snapshot is already inventoried in [[local-model-serving-inventory-2026-07-15]]. The request and the dedicated listener must use that exact absolute path, not the repository name or `default_model`. |
| Ollama server and artifact | Ollama `0.32.0`; `qwen3.5:35b-a3b-coding-nvfp4` manifest SHA-256 and `/api/tags` digest `6e73b30f8f1cfa06b979c842ba222ae21dad1e55e7c6748a7d8acad46fb340c4`; size `21,909,194,238`; quantization `nvfp4` | The local manifest is content-addressed and names every tensor/config layer digest. Inspect's maintained `ollama` provider is OpenAI-compatible and defaults to `http://localhost:11434/v1`, but it exposes no Ollama manifest digest in the completion contract. `/api/ps` is the loaded-model observation; it was empty during the inventory. |
| Existing target identity | `seon_inspect.cluster.static_target_snapshot` over `bin/acme status --edn` | The status EDN proves Seon artifact, process, and endpoint identity. It intentionally does not prove the separately managed model server and must not be relabeled as doing so. |

The maintained MLX-LM source is not mirrored under `reference-code/`. This
audit therefore records the exact installed source file and package tuple it
read. Formal implementation should add the serving source/package artifact to
the selected run lock or record its executable/module content digest; a
version string alone is insufficient.

## Shortest falsifiers

### F1 — CLI default is not request identity

Start MLX with snapshot A as `--model`, then send a request whose `model` is
snapshot B. In MLX-LM 0.31.3, `ModelProvider.load` resolves only the literal
`default_model` alias through `_model_map`; B otherwise reaches `load` and can
replace A in the same process. Therefore a process command containing A does
not prove an attempt used A.

This is already source-proven; a generation is unnecessary to establish the
contract. The implementation regression should use a fake MLX source seam or
fixture rather than loading weights.

### F2 — Discovery is not loaded state

`GET /v1/models` enumerates qualifying repositories from the whole Hugging
Face cache and appends the configured path. It can list models never loaded by
the process. Treating this response as weights evidence must fail admission.

### F3 — Response model is an echo

MLX assigns the request's `model` string to every completion response. The
string is useful for the join, but it is not an independent digest. A run with
only matching requested and response model strings, and no admitted artifact
manifest, must fail closed.

### F4 — Ollama tag is mutable

The installed Ollama name resolves today to manifest digest `6e73…c4`, but the
tag can later resolve to another manifest without changing the request model
string. Two snapshots with the same tag and different manifest digests must
not enter one comparison cell. `/api/ps` must name the admitted loaded digest;
`/api/tags` alone proves installed resolution, not which artifact served the
request.

### F5 — Current native log has no server join

The current `.eval` can retain source admission, static target, database
coordinate, requested model, endpoint, response model, and system fingerprint
while still lacking the immutable server artifact. Adding a plausible server
map only after finalization must not make it scorable; the map must be part of
the pre-task admitted identity and its end recheck.

## One identity shape

The host snapshot is a closed, non-secret map with these semantic fields:

- schema version and serving implementation;
- exact credential-free chat-completions request endpoint;
- executable/module content digest and package versions;
- managed PID and process start instant, plus a digest of the exact argument
  vector rather than an unbounded command string;
- artifact mechanism (`huggingface-snapshot`, `ollama-manifest`, or
  `externally-mutable`);
- exact request model identity;
- for local artifacts, revision or manifest digest, canonical
  artifact-manifest digest, byte size, and quantization/config digest; and
- expected response model and server fingerprint, or explicit absence when
  that protocol does not provide one.

Credentials, headers, query parameters, complete environment maps, prompt
bytes, and raw model manifests do not belong in this projection. The canonical
artifact manifest is sorted before hashing. For a Hugging Face snapshot it
includes every relative file, resolved blob/content digest, and size; for
Ollama the existing JSON manifest digest already commits to every layer, while
the verifier checks referenced blobs exist with declared sizes.

The snapshot must be byte-identical at run start and end. The existing attempt
evidence supplies the request-time join: every attempt endpoint equals the
admitted endpoint; every requested model equals the admitted artifact request
identity; every successful response model and fingerprint equals the declared
values when the server supplies them. The current transport-drift gate already
requires one comparable attempt configuration across the run.

## Provider-specific interpretation

### MLX dedicated snapshot

MLX is the clean first path because the exact absolute snapshot can be both
the launch default and the request model. Admission requires all of the
following together:

- one owned listener with stable PID/start identity;
- the CLI default set to the absolute revision directory;
- the same absolute path in the Seon database model value and every attempt;
- a canonical content-manifest digest over that directory;
- the response model equal to the same absolute path; and
- the response fingerprint equal to the fingerprint derived from the admitted
  MLX-LM/MLX/platform/device tuple.

The absolute path is not itself content identity. The manifest digest is. The
response echo is not itself content identity either; it binds the request to
the already admitted artifact. A dedicated process matters because the same
MLX process is otherwise expressly designed to switch models between requests.

### Ollama

Ollama's request uses a human-readable tag, while the artifact identity is its
manifest digest. Admission therefore records both. Before the run,
`/api/tags` and the on-disk manifest must agree on the digest and declared
size. After the first generation and at run end, `/api/ps` must report the same
loaded digest and quantization. A tag-only response cannot close the join.

Until the native boundary captures that loaded digest at the correct request
boundary, Ollama remains diagnostic or a strong sanity check rather than a
formal byte-identical comparison row. Do not weaken the common identity shape
to make Ollama look equivalent to the MLX absolute-snapshot path.

### Remote or dedicated API without exposed weights

A separately dedicated endpoint may provide strong process isolation but no
weights digest. Record its exact request endpoint, provider/model name,
declared revision, server deployment identity, response model, or fingerprint
only when each is actually available, plus an explicit `externally-mutable`
artifact mechanism. Local process/runtime and response fields remain absent
when the remote protocol does not expose them; nulls and invented digests are
not evidence. Such a row can sanity-check whether the task is solvable, but it
cannot be compared as if its weights were locally reconstructable.

## Exact minimal implementation

1. Add a pure model-server snapshot function beside
   `seon_inspect.cluster.static_target_snapshot`. It receives an explicit
   provider-specific declaration and injected process/HTTP/file readers for
   tests. It performs no lifecycle action.
2. Make `catalog.run_native_task` require a `model_server_snapshot` callback
   for a formal live run. Evaluate it before task construction, put the exact
   map in run metadata, evaluate it again in the existing `before_finalize`
   hook, write the end map with the existing metadata edit, and reject any
   inequality alongside source/target drift.
3. Extend the existing `solver.require_scorable_pod_state` admission—not each
   task scorer—to require that run identity and join every attempt by endpoint,
   requested model, response model, and fingerprint/digest rules. Diagnostic
   solvers may continue retaining incomplete evidence without scoring it.
4. Keep the current pod attempt schema. Its endpoint, requested model,
   response model, and system fingerprint already carry the request side of
   the join. Do not copy host process or artifact facts into Datahike attempt
   entities.
5. Route the initial admitted P0b replay through the MLX declaration for the
   exact Qwen2.5 Coder 0.5B snapshot. Defer Ollama scoring until its loaded
   digest is captured at the common boundary.

This design deliberately does not add a model server to Seon's three-process
application target. The server is a selected external dependency of the
evaluation run. Lifecycle ownership may later compose with the semantic
operator's lease, but identity admission does not wait for or duplicate that
supervision work.

## Focused tests

- MLX: reject a configured CLI snapshot A plus requested snapshot B; reject a
  cache listing that contains A without the admitted manifest; reject changed
  PID/start, module digest, manifest digest, endpoint, response model, or
  fingerprint; accept one exact immutable map.
- Ollama: reject equal tags with unequal manifest digests; reject an installed
  digest absent from `/api/ps`; reject a loaded digest or quantization that
  differs from the manifest; accept matching tag, manifest, blob-size, and
  loaded observations.
- Catalog: prove snapshot happens before task construction; prove end metadata
  is written into terminal evidence before drift raises; prove finalization
  reopens the exact start/end maps; reject no callback for a formal live run.
- Solver: mutate each join field independently and require
  `PodRunInfrastructureError` before task scoring; prove older diagnostic logs
  remain explicitly incomplete rather than backfilled.

Run only the focused `test_cluster.py`, `test_catalog.py`, `test_solver.py`,
and native-log finalization tests for this unit.

## Live proof

After the runtime lane hands off the coordinated dependency coordinate and
ACME is cleanly rebuilt:

1. Compute the Qwen2.5 Coder 0.5B snapshot manifest once, start its owned MLX
   listener through the agreed lifecycle owner, and obtain the admitted server
   snapshot without generating.
2. Transact the exact absolute snapshot path into the selected ACME database
   model row and read it back through `seon.ai/resolved-config`.
3. Run only `database_workflow-seed1-000` through `run_native_task`.
4. Reopen the finalized `.eval` and require byte-identical start/end source,
   Seon target, and model-server maps.
5. Require every ordered model attempt to carry the admitted endpoint and
   absolute snapshot path; require the successful response model and
   fingerprint to join the server map; require the database-operation proof
   and final database coordinate to remain independently valid.
6. Change only one declared snapshot digest in an offline copy of the identity
   and prove admission rejects it before the unchanged milestone scorer runs.

Only that read-back closes the formal blocker. A ready server, one successful
generation, or matching names without the native-log join remains diagnostic
evidence.
