---
type: issue
status: open
severity: friction
tags: [issue, agent, research, component]
---

# Content-pin the Inspect source dependency

## Problem

`src-inspect-ai` originally described its vendored Inspect dependency as pinned
and proven while `pyproject.toml` and `uv.lock` selected a mutable local
directory without executable revision/content admission. The installed virtual
environment and the current `reference-code/inspect-ai` checkout could
therefore run different framework code under the same Seon lockfile.

This makes a green offline suite ambiguous: it proves the framework already
installed in `.venv`, not necessarily the source that a fresh `uv sync` would
install or the source a reviewer reads under `reference-code/`.

## Evidence

- `src-inspect-ai/pyproject.toml` maps Inspect and Inspect Evals to the root-
  selected source checkouts and pins Python OpenAI to `2.45.0`.
- `src-inspect-ai/uv.lock` records the local source selections but cannot by
  itself prove which Gitlink revision occupies those directories.
- After a later synchronization, the current `.venv` reports
  `0.3.247.dev0+g05322696a.d20260715`, matching the root-selected Inspect
  Gitlink commit.
- The referenced checkout remains at Git commit
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc`; its nested `_view/ts-mono`
  submodule is checked out at `f3588038…` rather than the parent-selected
  revision. The source lock now names both the parent-selected `eccde6b7…`
  coordinate and the intentional checked-out `f3588038…` overlay. Admission
  verifies the nested checkout revision, tree, and cleanliness independently;
  it does not hide the subtree with an excluded pathspec.

The source/evidence audit sharpened the mismatch beyond Inspect itself:

- the synchronized `inspect_evals` distribution now reports `0.14.3` from the
  declared root-selected checkout
  `97c99f5f6507fc5d1449fe3247f267d591f64350`;
- the installed Python `openai` provider is `2.45.0`, while `pyproject.toml`
  is now declared exactly and recorded by accepted-run admission; and
- historical scorecard rows identify a Seon Git SHA and dataset-lock hash but
  not Inspect, Inspect Evals, Python lock/provider, task source, or scorer
  implementation/config. Historical rows remain historical evidence.

These are distinct from the pod's Node provider SDKs, which are exactly matched
by npm lock and reference source. A shared provider brand must not collapse
Python harness provenance into Node runtime provenance.

## Implementation checkpoint

The source/run-admission unit is implemented. One reviewed
`evaluation-sources.lock.json` selects the Inspect and Inspect Evals Gitlink
revisions, the explicit nested Inspect view overlay, admitted task/scorer
paths, exact Python provider, and Python/dataset locks. Direct task loading and
prebuilt catalog runs verify parent and nested revisions, trees, relevant
cleanliness, installed source/version, provider, lock digests, and committed
Seon harness source before model or pod work. The admitted identity map is
native Inspect metadata. Finalization copies, hashes, reopens, requires a
successful native status, and compares that exact identity map; a missing,
unreadable, incomplete, or wrong-run `.eval` is rejected.

The native frozen shell/file/web task added after this checkpoint now performs
the same admission at task construction, retains the map in task and sample
metadata, and re-verifies it after the pod returns but before scoring. This
repairs the gap found when those task paths were accidentally swept into an
unrelated lifecycle commit; direct task invocation no longer bypasses source
admission.

A fresh `uv sync --extra test` synchronized the selected distributions. The
focused admission/catalog/native-log gate passes 34 tests, including a real
offline native-log read-back; the complete suite passes 321 tests with eight
expected skips. Deliberate revision, dirty-source, provider-version, and absent-
log mismatches fail deterministically. Dedicated tests also reject a changed or
dirty nested view checkout, corrupt archive, non-success status, and wrong
admission identity.

The issue remains open only for the successor measurement contract to give
scorecard summaries a stable correlation to the required native log and, after
the lifecycle lease exists, add its artifact/config identities. The source and
native-log admission described above is complete.

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
