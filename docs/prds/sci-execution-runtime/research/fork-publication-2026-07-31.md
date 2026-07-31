---
type: research
status: active
tags: [research, dependency]
---

# Maintained fork publication — 2026-07-31

## Verdict

Every maintained submodule tip consumed by Seon is now reachable from one
named branch under the owner's GitHub account. GitHub's API returned the same
full SHA as the local maintained branch for all twelve rows below.

No force-push was used. No upstream repository was written, no pull request
was opened, no gitlink moved, and no branch other than the eight named
maintained branches was pushed. Creating the five missing GitHub forks copied
their upstream refs as GitHub's normal fork operation; Seon pushed only the
branches recorded under "Publication actions."

## Method and scope

The source roster is
`upstream-delta-sweep-2026-07-31.md:71-97`, checked against the current
`deps.edn`, `.gitmodules`, local branch ancestry, and every submodule remote.
For each maintained fork, verification compared:

- `git -C reference-code/<submodule> rev-parse <maintained-branch>`; and
- `gh api repos/seantempesta/<repo>/commits/<branch> --jq .sha`.

Equality, rather than mere commit reachability, is the publication gate. The
GitHub API checks below ran after all pushes on 2026-07-31.

`core.async` is not a Seon fork publication: the selected
`dc35f3e0d7bc2eef502e77982f48641f025c8051` is already on upstream's
`dev-flow-alpha` branch and carries no Seon commit. `persistent-sorted-set`
and `datalog-parser` are likewise upstream pins. `sharp`'s local `seon-pin`
is quarry-only and is not consumed by Seon. `datahike-lmdb` has a historical
Sean-authored validation commit on local `main`, but the current gitlink was
deliberately moved back to its upstream parent `bc9f024b`; that abandoned
branch is not a maintained pin and was not published.

## Publication actions

Five missing owner repositories were created with `gh repo fork
<upstream> --clone=false`:

- [seantempesta/clj-kondo](https://github.com/seantempesta/clj-kondo), forked
  from `clj-kondo/clj-kondo`;
- [seantempesta/core.async.flow-monitor](https://github.com/seantempesta/core.async.flow-monitor),
  forked from `clojure/core.async.flow-monitor`; and
- [seantempesta/http-kit](https://github.com/seantempesta/http-kit), forked
  from `http-kit/http-kit`;
- [seantempesta/bun](https://github.com/seantempesta/bun), forked from
  `oven-sh/bun`; and
- [seantempesta/claude-agent-sdk-typescript](https://github.com/seantempesta/claude-agent-sdk-typescript),
  forked from `anthropics/claude-agent-sdk-typescript`.

Eight plain pushes published missing maintained tips:

| Submodule | Branch | Before | Published tip |
|---|---|---|---|
| `datahike` | `main` | `9c356e32a0f2b0afcd41ce5000cba2a575a59a8a` | `9b3be9d59cb07d9c895af280e60eb074bb57a400` |
| `sci` | `seon` | `8fac6e88f32d53a5fd82ebe80640881e317b84fd` | `1305a90a1ab9ac3737ff5a539180bcc6d8f4e2d4` |
| `partial-cps` | `main` | `395063696067b65afe87f85c1f336578b608428c` | `1e119b03ea908ad925b98f9ba0a26371c65441e3` |
| `clj-kondo` | `seon` | absent | `57252e07975710aa579b24f0d1b2b1e04195caa2` |
| `core.async.flow-monitor` | `seon` | absent | `fbff8424696c7080ee7dc27b55cde1659ec18d8f` |
| `http-kit` | `seon-pending-write-state` | absent | `238a85cc555a38892f2f9a7583c9cf5cec0fb201` |
| `bun` | `seon` | absent | `d8ecf098572e2b8265b23e40c04efb4067e516cc` |
| `claude-agent-sdk-typescript` | `seon` | absent | `6084a9d91e253cf0d269438444dfd851cbf7ee67` |

The three existing branches were proven fast-forwardable with
`git merge-base --is-ancestor` before push. The three absent branches were
created directly at the pinned commits. The two quarry branches were likewise
created directly at their current gitlink commits after the full-roster audit
proved those commits had no remote ref.

## Verified owner roster

| Submodule | Owner repository | Maintained branch | GitHub API tip | Verified |
|---|---|---|---|---|
| `datahike` | [seantempesta/datahike](https://github.com/seantempesta/datahike) | `main` | `9b3be9d59cb07d9c895af280e60eb074bb57a400` | yes |
| `konserve` | [seantempesta/konserve](https://github.com/seantempesta/konserve) | `seon-0.9.359-legacy-header` | `b5c99bc02a7175652a610324215288b78551801f` | yes |
| `proximum` | [seantempesta/proximum](https://github.com/seantempesta/proximum) | `seon-guarded-force-v126` | `9846d3e79e1aee48474bc876d3d563d7137209c6` | yes |
| `sci` | [seantempesta/sci](https://github.com/seantempesta/sci) | `seon` | `937d392a008e4f2f246b9ddf9dd816ca99de9d4e` | yes |
| `clj-kondo` | [seantempesta/clj-kondo](https://github.com/seantempesta/clj-kondo) | `seon` | `57252e07975710aa579b24f0d1b2b1e04195caa2` | yes |
| `core.async.flow-monitor` | [seantempesta/core.async.flow-monitor](https://github.com/seantempesta/core.async.flow-monitor) | `seon` | `fbff8424696c7080ee7dc27b55cde1659ec18d8f` | yes |
| `superv.async` | [seantempesta/superv.async](https://github.com/seantempesta/superv.async) | `wasm/lazy-watchdog` | `3e6ed755f83634c9e9bbb58707f9446420d32ce9` | yes |
| `partial-cps` | [seantempesta/partial-cps](https://github.com/seantempesta/partial-cps) | `main` | `1e119b03ea908ad925b98f9ba0a26371c65441e3` | yes |
| `http-kit` | [seantempesta/http-kit](https://github.com/seantempesta/http-kit) | `seon-pending-write-state` | `238a85cc555a38892f2f9a7583c9cf5cec0fb201` | yes |
| `shadow-cljs` | [seantempesta/shadow-cljs](https://github.com/seantempesta/shadow-cljs) | `sync-upstream` | `c98bf60f70c102abda0fd385f78cc0fcd9c25408` | yes |
| `bun` | [seantempesta/bun](https://github.com/seantempesta/bun) | `seon` | `d8ecf098572e2b8265b23e40c04efb4067e516cc` | yes |
| `claude-agent-sdk-typescript` | [seantempesta/claude-agent-sdk-typescript](https://github.com/seantempesta/claude-agent-sdk-typescript) | `seon` | `6084a9d91e253cf0d269438444dfd851cbf7ee67` | yes |

## Remote and dependency truth

`.gitmodules` now clones the owner repository for `clj-kondo`,
`core.async.flow-monitor`, `partial-cps`, and `http-kit`, matching the existing
owner URLs for `datahike`, `konserve`, `proximum`, `sci`, and `superv.async`.
The full-roster audit also moved `shadow-cljs`, `bun`, and
`claude-agent-sdk-typescript` to their owner URLs. Each of the twelve local
submodules retains the upstream repository as a second remote for future delta
sweeps. Remote names vary where established history already used `fork`,
`seon`, or `origin`; the fetch targets are unambiguous. The path-limited
super-repo commits are `cdcf7cc69` and `98f874a17`.

The `deps.edn:92-93` claim that flow-monitor exactly matched published
`v0.1.5@376d6ec` was false. It now records that the vendored fork is one
commit ahead and that `fbff842` publishes the bound monitor port.

## Name-collision limitation

GitHub reports `seantempesta/sci` as `fork: false`, not as a network fork of
`babashka/sci`. The existing repository name therefore prevents creating a
second `seantempesta/sci` with `gh repo fork`. No alternate repository was
invented. The already-established owner remote remains the publication owner,
and its `seon` tip is API-verified above.

## Follow-up publication

The `sci-substrate-prep` lane subsequently added eleven modern core functions
on `sci/seon`. A plain fast-forward push advanced the owner branch, and a
fresh GitHub API read exactly matched the local tip.

| Submodule | Owner repository | Branch | Previous tip | Verified remote tip |
|---|---|---|---|---|
| `sci` | [seantempesta/sci](https://github.com/seantempesta/sci) | `seon` | `1305a90a1ab9ac3737ff5a539180bcc6d8f4e2d4` | `937d392a008e4f2f246b9ddf9dd816ca99de9d4e` |
