---
type: research
status: completed
tags: [research, reference, cljs, database, web]
---

# Exact dependency source integrity audit — 2026-07-14

## Result

Most selected runtime dependencies have exact reviewed source locally, but
their checked-out heads are not consistently the selected release. Two source
corpus gaps remain: ClojureScript `1.12.145` is truly absent from
`reference-code/`, and persistent-sorted-set `0.4.137` exists only in a clean
temporary shallow clone rather than the maintained reference corpus. The
Python evaluation environment has a more serious identity split: its installed
Inspect, Inspect Evals, and OpenAI distributions do not match the declared
mutable source/lock boundary.

No dependency checkout was fetched, switched, cleaned, or modified for this
audit. “Present in history” below means the exact Git object is reachable from
the existing local repository even when its working head differs.

## Exact-source matrix

| Selected dependency | Local source identity | Integrity result |
|---|---|---|
| ClojureScript `1.12.145` | `reference-code/clojurescript` head `946d75f3483c0c8e784e6668bff2c71a25619a77`; `pom.xml` is `1.12.41` although README text says `1.12.145` | **Missing.** No commit reachable from any local ref has `pom.xml` version `1.12.145`. README text is not release-source identity. |
| Shadow CLJS `3.4.10` | release bump `d3c04691952aa9ea33f7287ffe9a2b3109c1e510`; working head `8236315af7426ba505aad6102dea1c4ccb1fe412` (`3.4.11`) | **Exact source present in history.** The previously cited `2911c908…` is the release commit's parent and still declares `3.4.9`; it is not the `3.4.10` release. |
| Malli `0.20.0` | tag `0.20.0` → `4c054bd7d042e70d60b83b9f07fb765bc103037f`; working head `80138076960e7820523b4cb932c5b5d1936d4e7f` | **Exact source present by tag, not checked out.** The tag is not an ancestor of the current detached head, so reads must name the tag/object explicitly. |
| Datahike maintained fork | selected and checked-out `6f90b339768b1a02066dce3b6fcc93a200758fcc` | **Exact source checked out and clean.** Both `:writer` and `:cljs` override to this SHA. |
| Konserve maintained fork | selected and checked-out `df6818d43ea3363a808cd051c0d68917f1b987a9` | **Exact source checked out and clean.** Both runtime bases use this SHA. |
| persistent-sorted-set `0.4.137` | clean shallow tag/HEAD `e1a17bbe767c7801e67407c81f64efabfd2f1601` under `tmp/dependency-source/persistent-sorted-set` | **Exact source exists locally but is not maintained in `reference-code/`.** Generated CLJS output is not a substitute for source. |
| Reitit `0.10.1` | tag and checked-out head `106fc4c7a09290c8e2df2d4ef9570ea1322ab2ab` | **Exact source checked out and clean.** |
| Datastar browser bundle `v1.0.0-RC.7` | tag commit `904295865136b82a13eae2ba825d50693fa8c42e`; shipped `resources/public/js/datastar.js` SHA-256 `c9c8b99715d759df4543d4e01d6e6fe4b3940e4dee57ec9cde7eb344e86c61e2` | **Exact bytes present.** The shipped 30,732-byte file is byte-identical to tagged `bundles/datastar.js`. Datastar Clojure `v1.0.0-RC7` is also present at checked-out `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2`. |
| Node OpenAI `6.42.0` | npm lock `6.42.0`; tag/head `v6.42.0` → `6f849f4ff24f70167bf82d37c8c83e3f8b1c5472` | **Exact source checked out and clean.** This owns DeepSeek and other OpenAI-compatible pod traffic too. |
| Node Anthropic `0.104.2` | npm lock `0.104.2`; tag/head `sdk-v0.104.2` → `fbee0d149ce08532885d766d9b1dc99133181d8e` | **Exact source checked out and clean.** |
| Java Google GenAI `1.59.0` | tag `v1.59.0` → `7bdfda579a187984c89fe03e75b2da841156ad0b`; working head `fd3713ba47ec8156a01cd95e474d3e44b3205fc0` is two commits later | **Exact source present in history.** Read the tag for `src/seon/embed.clj`; `reference-code/js-genai` is not selected by current manifests. |

## Inspect and Inspect Evals split

`src-inspect-ai/pyproject.toml` claims the proven Inspect build is
`0.1.dev1+g92dd737b9` and selects a mutable sibling directory. Its lock records
only `source = { directory = "../reference-code/inspect-ai" }`, without a Git
revision or tree digest. The referenced repository's main commit is the exact
`0.3.246` tag at `05322696a0f784ec399ef6abbafd3d2a250ea9cc`, but the working tree is
dirty because the `ts-mono` gitlink has moved from recorded `eccde6b7…` to
`f3588038…`. The installed wheel still reports
`0.1.dev1+g92dd737b9`; that old commit remains present in local history, but the
mutable direct URL cannot prove installed bytes equal current reviewed bytes.

Inspect Evals is actively imported by the catalog and tests but is absent from
both the project dependency list and `uv.lock`. Its clean reference checkout is
tag `v0.14.3` at `97c99f5f6507fc5d1449fe3247f267d591f64350`; the environment instead has
an editable `0.0.1.dev1+unknown.gce900d638` installation from old commit
`ce900d6389860acbadec4b5176634b37fcf76070`. The project declares unbounded
`openai`, but the current lock has no `openai` package row and does not list it
under `seon-inspect`; the environment independently contains OpenAI `2.45.0`.
Therefore the green offline suite is useful behavioral evidence, but it is not
a reproducible dependency identity proof.

## Machine-actionable repair plan

1. Mirror exact ClojureScript `1.12.145` into
   `reference-code/clojurescript` or a version-qualified adjacent reference
   checkout. Record a release tag/commit and tree hash, then replace every
   analyzer-sensitive inference made from the `1.12.41` tree with a named
   exact-object read.
2. Promote the existing clean persistent-sorted-set tag
   `e1a17bbe767c7801e67407c81f64efabfd2f1601` from
   `tmp/dependency-source/` into `reference-code/` without changing the
   selected dependency. Verify `git rev-parse 0.4.137^{commit}` and a clean
   tree before using it in a plan.
3. Add one repository-owned dependency-source manifest mapping every selected
   coordinate to repository path, commit/tag, and expected tree or artifact
   digest. Its check must use `git cat-file`/`git rev-parse`, never require a
   particular working-tree checkout, and verify the shipped Datastar bundle
   digest.
4. Pin Inspect and Inspect Evals to reviewed content identities, declare both
   plus the Python OpenAI provider explicitly, regenerate the lock, and build a
   fresh environment. Fail task construction when Git/tree identity, lock,
   installed distribution, or dirty-source state differs.
5. Record Inspect, Inspect Evals/task/scorer, Python lock/provider, and native
   `.eval` log identities in every scorecard row. Only after that identity gate
   passes should the current offline suite and representative standard task be
   treated as reproducible graduation evidence.

## Verification commands

The integrity check should reduce to read-only assertions equivalent to:

```bash
git -C reference-code/shadow-cljs show d3c04691952aa9ea33f7287ffe9a2b3109c1e510:project.clj
git -C reference-code/malli rev-parse '0.20.0^{commit}'
git -C reference-code/reitit rev-parse '0.10.1^{commit}'
git -C reference-code/datahike rev-parse HEAD
git -C reference-code/konserve rev-parse HEAD
git -C reference-code/inspect-ai status --porcelain=v1
git -C reference-code/inspect-evals status --porcelain=v1
cmp resources/public/js/datastar.js \
  <(git -C reference-code/datastar show v1.0.0-RC.7:bundles/datastar.js)

```

The resulting manifest/check belongs to the unit-0 documentation and operator
integrity boundary. It must not switch or clean dependency worktrees as a side
effect.
