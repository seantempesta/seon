---
type: issue
status: open
severity: friction
tags: [issue, agent, research, component]
---

# Content-pin the Inspect source dependency

## Problem

`src-inspect-ai` describes its vendored Inspect dependency as pinned and proven,
but `pyproject.toml` and `uv.lock` select a mutable local directory without a
commit or content digest. The installed virtual environment and the current
`reference-code/inspect-ai` checkout can therefore run different framework
code under the same Seon lockfile.

This makes a green offline suite ambiguous: it proves the framework already
installed in `.venv`, not necessarily the source that a fresh `uv sync` would
install or the source a reviewer reads under `reference-code/`.

## Evidence

- `src-inspect-ai/pyproject.toml` says the proven build is
  `0.1.dev1+g92dd737b9` and maps `inspect-ai` to
  `../reference-code/inspect-ai`.
- `src-inspect-ai/uv.lock` records only
  `source = { directory = "../reference-code/inspect-ai" }`; it carries no Git
  revision or tree digest for that directory.
- The current `.venv` reports Inspect `0.1.dev1+g92dd737b9` from site-packages.
- The current referenced checkout is Git commit
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc` and describes itself as
  `0.3.246-dirty`. Its `_view/ts-mono` entry is also dirty.

The source/evidence audit sharpened the mismatch beyond Inspect itself:

- the installed `inspect_evals` distribution reports
  `0.0.1.dev1+unknown.gce900d638`, while the clean inspected checkout is
  `97c99f5f6507fc5d1449fe3247f267d591f64350` / `v0.14.3`; it is not a
  declared `src-inspect-ai` dependency despite current catalog/tests importing
  it;
- the installed Python `openai` provider is `2.45.0`, while `pyproject.toml`
  declares unbounded `openai`; the current lock has no `openai` package row and
  does not list it under `seon-inspect`, so the installed provider is not
  reproducible from that lock; and
- historical scorecard rows identify a Seon Git SHA and dataset-lock hash but
  not Inspect, Inspect Evals, Python lock/provider, task source, or scorer
  implementation/config. Copying a native `.eval` log is currently best-effort
  and silently continues when the copy fails.

These are distinct from the pod's Node provider SDKs, which are exactly matched
by npm lock and reference source. A shared provider brand must not collapse
Python harness provenance into Node runtime provenance.

No environment was synchronized during this audit, so the observed version
split remains intact evidence rather than an implicit framework upgrade.

## Owner

The `src-inspect-ai` Python dependency and evaluation-provenance boundary:
`pyproject.toml`, `uv.lock`, the vendored/reference source policy, and scorecard
run metadata.

## Acceptance

- A fresh environment resolves the exact reviewed Inspect source revision or
  content digest, not whichever bytes happen to be in a sibling directory.
- The declared, installed, and source-checkout versions and Git identities
  agree before a scored run starts.
- The Inspect suite and one representative offline task pass from a newly
  synchronized environment.
- Every scorecard/run artifact records the Inspect framework identity needed
  to reproduce it, plus Inspect Evals/task/scorer, Python provider and lock,
  native `.eval` log, and Seon runtime/artifact/config identities. A dirty or
  mismatched source fails before task construction; it is not merely labeled
  after a score exists.
