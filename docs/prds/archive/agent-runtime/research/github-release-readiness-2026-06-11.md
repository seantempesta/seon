---
type: research
status: active
tags: [research]
---

# GitHub Release Readiness — push latest seon + datahike bugfix (2026-06-11)

Analyzed at seon HEAD `1fafa2577b6a3bed8570200786fe09df51971347`
(feature/agent-runtime). Concurrent agents were editing
`src/seon/render/*`, `test/seon/gym/*`, `src/seon/agent.cljs` during this
read-only analysis. Goal (user, verbatim intent): push the latest code,
possibly push yesterday's datahike bugfix, such that "any user can be
able to build everything we are doing today."

## TL;DR

- **The seon repo is ALREADY PUBLIC** (`github.com/seantempesta/seon`,
  `private=false`). The 206 local commits on `feature/agent-runtime` are
  the publish unit; origin's copy of the branch is at 2026-06-02. `main`
  is 337 commits behind the branch and 0 ahead — a fast-forward.
- **ONE hard build blocker for outsiders: konserve.** Four deps.edn
  aliases (`:writer`, `:cljs`, `:replica-probe-jvm`, `:replica-peer-jvm`)
  pin `org.replikativ/konserve {:local/root "/Users/sean/src/konserve"}`.
  That local fork has 2 commits (header fix `32e3c59` + NOTICE `1fec9ba`)
  that exist NOWHERE on GitHub — push to upstream is literally disabled
  (`upstream DISABLED-NO-PUSH-TO-UPSTREAM (push)`) and
  `github.com/seantempesta/konserve` returns 404. An outside user today
  cannot start the wire-server, compile the CLJS pod, or run either
  replica probe. Fix: create the public konserve fork, push, switch the
  four `:local/root` entries to `:git/url`+`:git/sha`.
- **Yesterday's datahike bugfix is already public.** Commit
  `1ae3569611ec62c4b0e378ffb902e563bddf57e1` ("fix(query/execute): direct
  multi-group path corrupts joins with >1 group-join edge", authored
  2026-06-10T17:01Z) is the HEAD of `feat/cljs-promise-api` on
  `github.com/seantempesta/datahike`, and deps.edn already pins exactly
  that sha in `:writer` and `:cljs`. Nothing to push for the build to
  work. The open question is upstream contribution (framed in §8).
- **All other git-dep forks resolve publicly**: superv.async `3e6ed755`
  OK, partial-cps `c0d941d4` OK.
- **Submodule red flag resolves to noise, with one real defect.** The
  `?` on `reference-code/datahike` and `reference-code/posh` is just
  untracked content (`cache/`, `logs/`) inside otherwise-proper gitlinks
  — both ARE in the index (mode 160000). The real defect: the datahike
  gitlink pins `717a0d27`, which is reachable from `replikativ/datahike`
  main but NOT from any branch on the configured submodule URL
  (`seantempesta/datahike` — fork main is older, the feature branch
  doesn't contain it). `git clone --recursive` may fail to check out that
  submodule. None of the builds depend on reference-code/, so a plain
  clone is unaffected.
- **No secrets found in tracked files.** API keys are env-only
  (`GEMINI_API_KEY` at `src/seon/ai/gemini.clj:338`, `DEEPSEEK_API_KEY`
  at `src/seon/ai/deepseek.cljs:269`); `.env`, `data/`, `logs/`, `tmp/`,
  `out/` are gitignored; no tracked credential files; no personal email
  in tracked content (committer identity in git history only, which is
  normal).
- **Licensing is done on this lineage**: AGPL-3.0 `LICENSE`,
  `CONTRIBUTING.md`, and the narrative `README.md` are all tracked at
  HEAD (commit `584b08c`). Gap: README has zero build/run instructions.
- **The old scrub concerns are mostly resolved**: `docs/archive/`,
  `test-hook.db`, and obsidian plugin code are not tracked on this
  lineage; `docs/prds/seon-transform/prd.md` no longer exists on ANY
  current branch tip (archived long ago in "archive 16 stale PRDs") — the
  "scrubbed from main, kept on feature branches" protocol is obsolete.

## 1. Repo state (evidence)

```text
origin  git@github.com:seantempesta/seon.git          # private=false, default=main
feature/agent-runtime  1fafa25  [origin/feature/agent-runtime: ahead 206]
origin/feature/agent-runtime  e81f189  2026-06-02 12:30:11 -0400
origin/main = local main      d0b2046  2026-05-23  "feat: add SOUL.md ..."
main..feature/agent-runtime: 337 commits   feature..main: 0 commits
```

- `main` is strictly behind — merging is a fast-forward, no merge commit
  needed. Local `main` == `origin/main` (0 unpushed).
- Working tree at analysis time: `src/seon/agent.cljs` modified
  (concurrent agent), submodule untracked-content noise; nothing else.
- The May-era scrub/license work (`35b912e`, `79f8f4a` on worktree branch
  `claude/friendly-hellman-c4cbc5`) is NOT an ancestor of this branch —
  it was a different lineage (datahike-migration era). Its useful
  outcomes were independently re-landed here (LICENSE via `584b08c`;
  `docs/archive/`, `test-hook.db`, obsidian plugins not tracked at HEAD).
  That branch can be considered historical.

## 2. Submodules (reference-code/)

- 31 gitlinks, all mode 160000, all with `.gitmodules` entries pointing
  at public GitHub URLs. The only fork URL is
  `reference-code/datahike → https://github.com/seantempesta/datahike.git`.
- `git status` "?" on datahike/posh = untracked `cache/` and `logs/`
  dirs INSIDE the checkouts ("modified: ... (untracked content)"); the
  pinned shas match the checkouts. Posh pins `2347c85` = upstream
  `mpdairy/posh` master head — fine; posh checkout has zero local
  commits and points at upstream only (no fork dependency).
- **Defect — datahike gitlink unreachable from its configured URL.**
  Pinned sha `717a0d27` (upstream "#831 versioning API"):
  - `replikativ/datahike` compare `717a0d27...main` → `status=ahead`
    (reachable from upstream main, 12 back).
  - `seantempesta/datahike` compare `717a0d27...main` → `status=behind`
    (fork main `015fb2a5` is an ANCESTOR of it; not contained). Fork has
    only `main` + topic branches; `feat/cljs-promise-api` forked 15
    commits before upstream main, so it doesn't contain `717a0d27`
    either. GitHub serves fork-network objects unreliably for
    fetch-by-sha; `git submodule update --init reference-code/datahike`
    on a fresh `--recursive` clone is at risk of failing.
  - Two clean fixes (pick one): (a) repoint the gitlink at
    `1ae35696` — the sha we actually RUN, on a real fork branch
    (coherent with the "read the source we run" purpose of
    reference-code/); or (b) push a `upstream-track` branch at
    `717a0d27` to the fork. (a) is better.
- An outside user who clones WITHOUT `--recursive` is unaffected:
  nothing in deps.edn, package.json, or shadow-cljs.edn references
  reference-code/. Submodules are documentation/grep material only.
  (Note `--recursive` also pulls openclaw + hermes-agent + shadow-cljs —
  multi-GB. README should say "submodules optional".)

## 3. The datahike fork + yesterday's bugfix

**What we run** (deps.edn): `:writer` (wire-server), `:cljs` (pod),
`:replica-probe-jvm`, `:replica-peer-jvm` all pin

```clojure
org.replikativ/datahike
{:git/url "https://github.com/seantempesta/datahike"
 :git/sha "1ae3569611ec62c4b0e378ffb902e563bddf57e1"}
```

The dev/test JVM aliases still use mvn `0.8.1671` (deps.edn:90,108) —
the dev JVM does NOT carry the fix; only wire-server/pod/probes do. The
deps comment (deps.edn:142-151) flags this as "2.2d Stage B sha
alignment (PREPARED, not flipped)".

**The bugfix** (seon bump commit `156a53e`, 2026-06-10 13:10 -0400:
"deps: bump datahike fork sha 01ba3f18 → 1ae35696"). Fork commit message
verbatim (trimmed to load-bearing parts):

```text
fix(query/execute): direct multi-group path corrupts joins with >1 group-join edge

A datalog query joining TWO identity-attr clauses through one row —
  [?ag :seon.agent/id ?bid] [?m :from ?ag] [?m :to ?u] [?u :seon.user/id "user"]
with :in $ ?bid — ignored the ?bid binding and returned the
inverse-direction rows, regardless of clause order (found 2026-06-10 by
seon's gym lane).

Root cause: execute-plan-direct's multi-group hash-probe loop only
supports a single producer→consumer probe-join edge, but ran any
group-join topology [...]

On CLJS the planner is always on (*force-legacy* false) so every such
query was silently wrong; on CLJ the bug was masked by *force-legacy*
defaulting to true and reproduces with DATAHIKE_QUERY_PLANNER=true.

Conservative fix: can-direct-fuse? now rejects plans whose group-join
topology is anything other than a single producer→consumer edge with
the producer executed first. Rejected plans fall back to the Relation
path (execute-plan), which handles these joins correctly [...]

Regression test (CLJC, runs on both platforms):
datahike.test.cljs-pattern-scan-test/two-identity-joins-through-one-row-honor-in-binding

Suites: clj-pss 503 tests/2401 assertions/0 failures (legacy default);
planner-on identical except 2 pre-existing stratum-bridge errors;
bb node-cljs-test 8 tests/58 assertions/0 failures.
```

Files: `src/datahike/query/execute.cljc` +
`test/datahike/test/cljs_pattern_scan_test.cljc`.

**Where it lives**: PUBLIC, head of `seantempesta/datahike`
`feat/cljs-promise-api`. Branch shape vs `replikativ/datahike` main:
ahead 4, behind 15. The 4 commits:

```text
f092a63a feat(cljs): selective Promise wrap on datahike.api for native async/await
f6ecf173 build: make compile-java work cold so :git/url consumers succeed at prep-lib
01ba3f18 fix(cljs): silence 16 ClojureScript analyzer warnings
1ae35696 fix(query/execute): direct multi-group path corrupts joins with >1 group-join edge
```

So "pushing the datahike bugfix" in the build-reproducibility sense is
**already done**. What remains is the upstream question — §8.

**Other git-dep forks** (all public, verified by sha lookup):

- `seantempesta/superv.async@3e6ed755` (lazy watchdog, deps.edn:311) — OK
- `seantempesta/partial-cps@c0d941d4` (ioc probe safety, deps.edn:314) — OK

**Konserve — the blocker** (full detail). `/Users/sean/src/konserve`:

```text
upstream  https://github.com/replikativ/konserve.git (fetch)
upstream  DISABLED-NO-PUSH-TO-UPSTREAM (push)
32e3c59 fix(header): CLJS meta-size BE32 at bytes 4-7 + legacy 1-byte sniff
1fec9ba fork(sync-only): add NOTICE.md and CHANGES.md
===unpushed===
32e3c59, 1fec9ba          # both commits exist ONLY on this machine
```

`gh api repos/seantempesta/konserve` → 404. Referenced at deps.edn:160,
182, 202, 297. The `dev-resources/konserve-shim` pom.properties (tracked)
works around `:local/root` having no version; a `:git/url` dep ALSO has
no pom.properties, so **keep the shim after the switch** (deps.edn:183
comment confirms the version-check reads nil from non-jar classpath
entries).

## 4. Build-from-scratch walk (fresh clone, outside user)

Prerequisites an outsider needs: JDK 21, Clojure CLI, Node + npm,
`DEEPSEEK_API_KEY` (live agents) / `GEMINI_API_KEY` (optional,
`user/search`). What works and what breaks, in launch order:

1. `git clone` (plain) — OK. `--recursive` — at risk on the datahike
   gitlink (§2) and multi-GB; should be documented as optional.
2. `npm install` — OK (package.json deps are all public npm).
3. `./bin/run` (dev JVM) — **breaks at bin/run:11**:
   `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/...`
   hardcodes a macOS Temurin path. Also stale comment "Starts
   Datalevin..." (bin/run:7). Deps themselves resolve (all mvn/public).
4. `bin/seon start wire-server` → `clojure -M:writer ...` — **breaks at
   deps.edn:160**: konserve `:local/root "/Users/sean/src/konserve"`
   does not exist on their machine. Hard stop.
5. `clj -M:cljs compile client` / `bin/seon start cljs-watch` — **breaks
   at deps.edn:297**: same konserve `:local/root` in the `:cljs` alias
   `:override-deps`. Hard stop for the entire pod lane.
6. `bin/seon start pod` (`node out/client/main.js`) — blocked by 5.
7. `pod-host/` (datahike-harness, libdatahike-cljs, wasm-tauri) — not on
   the demo path (`bin/seon` doesn't launch them); fine to leave
   undocumented for now.
8. README — narrative only; no Quick Start, no prerequisite list, no
   mention of `bin/seon`, no submodule guidance. An outsider has no
   entry point ("Reading the codebase" §5 points at CLAUDE.md, which
   describes processes but assumes the local setup).

So the concrete breaking refs for "build today's demo": deps.edn:160,
deps.edn:182, deps.edn:202, deps.edn:297 (konserve), bin/run:11
(JAVA_HOME), plus the 206-commit push itself.

## 5. Secrets + personal data sweep

- **Pattern grep over all tracked files** (sk-/AIza/ghp_/inline api_key
  values), excluding reference-code: zero hits.
- **Env-var discipline confirmed**: `GEMINI_API_KEY`
  (src/seon/ai/gemini.clj:338), `DEEPSEEK_API_KEY`
  (src/seon/ai/deepseek.cljs:269-272, fails loudly when missing),
  `ANTHROPIC_API_KEY` only ever `assoc`'d to `""`
  (src/seon/ai/claude/sdk.clj:176).
- **.gitignore** covers `data/` (:9), `logs/` (:23), `tmp/` (:24),
  `.env` (:32), `node_modules/` (:40), `out/` (:46). No tracked `.env*`,
  `*secret*`, `*credential*` files.
- **Personal data**: no `sean.tempesta@gmail` / `tempesta.io` strings in
  tracked docs/src/resources (git author metadata only — normal).
  `resources/seed/facts-seed.edn` is seon-self-knowledge, not personal.
  Health/workout code (`src/seon/health*`,
  `test/seon/gym/scenarios/s21-log-workout-existing-schema.edn`) is
  schema/scenario material, no real personal records found.
- **Tracked dot-dirs, judgment calls** (none blocking): `.claude/`
  (agents, skills, settings.json hooks, seon-hook.edn — arguably a
  feature: "the rules I make every agent read" is part of the thesis),
  `.mcp.json` (no secrets, but two ABSOLUTE paths
  `/Users/sean/src/seon/bin/mcp-server*` — works only after clone to the
  same path; consider `$CLAUDE_PROJECT_DIR`-style or document),
  `docs/.obsidian/*.json` (editor config, harmless).
- `bin/run-datalevin` still exists (dead-era launcher) — cosmetic.

## 6. Licensing

- `LICENSE` = AGPL-3.0 (tracked, commit `584b08c` "chore(license):
  AGPL-3.0 + README + inbound-license CONTRIBUTING"). `CONTRIBUTING.md`
  present.
- Submodules are NOT part of seon's source distribution (gitlinks only,
  each repo carries its own license: datahike/konserve EPL-1.0, malli
  EPL-2.0, etc.). No vendored third-party source is committed into the
  tree itself — the May scrub already removed the one case (obsidian
  plugin JS). No action required beyond (optional) a README sentence
  noting reference-code/ submodules carry their own licenses.
- The konserve fork, once pushed, must keep upstream's EPL license file
  and copyright — the local fork already added NOTICE.md/CHANGES.md
  (`1fec9ba "fork(sync-only)"`) which is the right shape.

## 7. Concrete push plan (commands WRITTEN, not executed)

Ordered for "outside user builds today's demo" with minimum motion.
Steps 1-3 are the substance; 4-6 are the seon push; 7 is optional polish.

```bash
# 1. Publish the konserve fork (THE blocker)
gh repo fork replikativ/konserve --clone=false      # creates seantempesta/konserve
cd /Users/sean/src/konserve
git remote add origin git@github.com:seantempesta/konserve.git
git push origin HEAD:fix/cljs-header-be32           # publishes 32e3c59 + 1fec9ba

# 2. Switch deps.edn off :local/root (4 sites: lines 160, 182, 202, 297)
#    org.replikativ/konserve
#    {:git/url "https://github.com/seantempesta/konserve"
#     :git/sha "32e3c59<full-40-char-sha>"}
#    KEEP dev-resources/konserve-shim (git deps also lack pom.properties).
#    Verify locally before pushing: clj -A:writer -Stree | grep konserve
#    and bin/seon restart wire-server + a pod compile.

# 3. Fix the datahike submodule gitlink (clone-with---recursive safety)
cd /Users/sean/src/seon/reference-code/datahike
git fetch https://github.com/seantempesta/datahike feat/cljs-promise-api
git checkout 1ae3569611ec62c4b0e378ffb902e563bddf57e1
cd /Users/sean/src/seon
git add reference-code/datahike                      # gitlink → the sha we run

# 4. Fix bin/run portability (bin/run:11) — guard the JAVA_HOME export:
#    [ -d "$JAVA_HOME_CANDIDATE" ] && export JAVA_HOME=... (or drop it and
#    document JDK 21 in README); fix the stale "Datalevin" comment line 7.

# 5. Add a README "Build & Run" section: prereqs (JDK 21, Clojure CLI,
#    Node), `npm install`, `bin/seon start all`, DEEPSEEK_API_KEY,
#    submodules-optional note. (Smallest version: 15 lines.)

# 6. Commit (steps 2-5) + push the branch, then fast-forward main
git add deps.edn reference-code/datahike bin/run README.md
git commit -m "release: public-build reproducibility — konserve fork sha, submodule pin, portable bin/run, README quick start"
git push origin feature/agent-runtime                # publishes the 206+1 commits
# main is 0 ahead / 337 behind → pure fast-forward:
git push origin feature/agent-runtime:main           # (or merge locally first — user's call)

# 7. Optional polish (separate commits, any time):
#    - .mcp.json absolute paths → relative/documented
#    - delete bin/run-datalevin
#    - README note: reference-code/ submodules are optional + large
```

Fresh-clone verification oracle (run on another directory/machine):
`git clone <url> && cd seon && npm install && clj -A:writer -Stree &&
clj -M:cljs compile client && bin/seon start wire-server`.

## 8. The datahike decision — fork-push vs upstream PR (user decides)

The minimum for outside builds is ALREADY met: the fix sha is public on
the fork and deps.edn pins it. The remaining question is upstream
contribution. The 2026-06-10 standing decision was NO upstream PR; the
user is reconsidering. Both options honestly:

**Option A — leave it on the fork (status quo).**

- Cost: none now. The fork branch is `ahead 4 / behind 15` and will keep
  drifting; each future upstream bump means a rebase of 4 commits.
- Risk: upstream's planner work (#825-#827 etc. continue actively)
  may rework `execute.cljc` and make the eventual rebase harder; outside
  users of seon get the fix transitively either way.
- Fits if: the demo window is the priority and datahike maintenance time
  is zero this week.

**Option B — PR upstream (reconsidered).**

- The bugfix commit is unusually PR-ready: surgical (1 src file + 1 CLJC
  regression test), conservative (rejects fusion rather than rewriting
  the join), with full suite numbers in the message, and it fixes a
  *silent wrong-results* bug that hits every CLJS user (planner always
  on) and any CLJ user with `DATAHIKE_QUERY_PLANNER=true`. Upstream has
  been merging planner-correctness PRs at a steady clip (#816-#827),
  so review latency is plausibly short.
- Costs: (1) rebase `1ae35696` onto upstream main (15 commits of drift —
  the touched file `query/execute.cljc` is exactly where upstream is
  active, so conflicts are possible); (2) PR shepherding time
  (responding to review); (3) the commit is only 1 of 4 on the branch —
  PRing it alone means extracting it from under the Promise-wrap
  commits, or PRing the bugfix first and keeping the CLJS-API commits
  fork-only (they were already described in deps.edn as "PR-shaped
  pending upstream review", so the eventual intent was upstream anyway).
- Benefit: kills the fork-maintenance treadmill for this commit; every
  future sha bump gets the fix for free; reputational/ecosystem value
  for the seon release ("found by seon's gym lane" is a good story).
- Note: an upstream PR does NOT change seon's deps this week — we'd keep
  pinning the fork sha until a merged upstream sha/release exists.

Same check for the other forks: superv.async (`wasm/lazy-watchdog`) and
partial-cps (`fix/ioc-await-probe-safety`) are both already framed in
deps.edn comments as "Upstream PR pending" — same A/B applies, lower
stakes. Posh: no fork dependency at all (upstream sha), nothing to do.

## 9. Scrub list (before/with the push)

Nothing blocking was found; this is the verification list:

1. ~~docs/archive/~~, ~~.claude/test-hook.db~~, ~~obsidian plugin JS~~ —
   already untracked on this lineage (verified via `git ls-files`).
2. ~~docs/prds/seon-transform/prd.md~~ — no longer exists on any branch
   tip; the old "keep on feature branches, scrub from main" protocol is
   obsolete. If the personal-framing doc matters, it survives only in
   git history of old branches (`2e64107` lineage).
3. `.mcp.json` absolute paths — fix or document (cosmetic, not secret).
4. `bin/run-datalevin` + the "Datalevin" comment in `bin/run` — delete/fix.
5. Re-run the secrets grep over the FINAL diff right before pushing
   (concurrent agents are landing commits daily):
   `git log -p origin/feature/agent-runtime..HEAD | grep -iE "sk-[A-Za-z0-9]{20}|AIza|ghp_|api[_-]?key.{0,8}[\"'][A-Za-z0-9_-]{20}"`.
6. Spot-check `data/`, `logs/`, `tmp/` are still untracked at push time
   (`git status --ignored -s | grep -v '^!!' `).

## 10. Open questions (for the user)

1. **Branch vs main**: push `feature/agent-runtime` only, or also
   fast-forward `main` (337 commits)? README/LICENSE/SOUL on origin/main
   are 2026-05-23 vintage; the public repo's default branch is `main`,
   so visitors currently land on stale content. Recommendation implicit
   in §7 step 6 is fast-forward, but it's a one-way door for "main is
   always demo-ready" expectations.
2. **Datahike upstream PR** — §8, user's call. If yes: bugfix-only PR
   first, or the whole 4-commit branch?
3. **Konserve branch naming + PR**: the header fix
   (`fix/cljs-header-be32`) is itself upstream-PR-shaped (deps.edn
   comments say ":local/root until the fix ships as a new mvn version").
   Same A/B as datahike — fork-pin is sufficient for builds.
4. **Is `.claude/` (agents/skills/hooks) intentionally public?** It
   currently is (already on origin). It reads as part of the thesis;
   confirm that's deliberate.
5. **`:writer` Stage B flip**: deps.edn:142 says the sha alignment is
   "PREPARED, not flipped" and the RUNNING wire-server keeps old deps
   until restart. For the public story it doesn't matter (fresh clones
   resolve the new deps), but the README/demo docs should describe the
   post-flip world only.
6. **Dev JVM still on mvn 0.8.1671** (deps.edn:90,108) — without the
   join fix. Intentional (JVM legacy path masks the bug) or should
   dev/test also move to the fork sha for one-truth?
