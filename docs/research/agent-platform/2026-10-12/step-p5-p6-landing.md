# P5/P6 landing evidence

`seon.cluster.agent/plan-facts` selects `seon.task/obligations` from the fork commit that `seon.cluster.agent/write-attempt!` selects, rather than main. `seon.turn/end-task!` reads the held task's incoming `:seon.task/needs` refs and requests a merge only for a terminal step. Admission is cached by immutable commit identity and task symbol through `seon.cache/lookup`; the root problem action carries its intent into `seon.cluster.agent/write-problem!`. No new execution machinery.

## Admission and completion

Root resolved the design conflict: a Prove step is valid when its own tagged proofs exist at the fork point or a successor task declares it in `:seon.task/needs`. The successor's intent describes the product that Prove will write. Implement still requires its own tagged proofs at the fork point. Prove completion retains P3's rule: nonempty product, new since the fork, each red by assertion failure or `:seon.error/unimplemented` (typed reports landed in `be32e7501`). The hybrid's §3 validity row is corrected in place.

The admission table covers Prove with own proofs, Prove with only a successor declaration, invalid Prove without either, and invalid Implement without proofs despite a successor. P3's Prove chain also asserts no merge request for p; the green chain asserts no request for its predecessor and one request for its terminal Implement step. The experiment's successor-proof regression checks the predecessor commit rather than main. No admission fence or alternate completion mechanism.

## Measurements and proofs

- Initial HEAD: `031125438`; final check HEAD: `35e906c38`; Datahike pinned `42309184021bd24d0f9c0fd17f514c320ab037fd`; Malli pinned `dd13ff81139159778edb0a67b491eccc0baa991d`.
- Dependency seam: `datahike.api/commit-as-db` accepts an attached database and preserves its cache identity. Primary-only materialization has no native resources to release. The selected predecessor commit remains the existing first sorted commit; the octopus fork is E6's separate work. `malli.core/validator` and `malli.core/-instrument` own contract checking.
- Initial default status: ready, pid 65510, faulted health; one open fault and one errored receipt. Default was never stopped, reset or restarted.
- SCI `(seon.context/current-state)` as step-p5-p6/default: 18 ms, 10.7 MB; complete request 294.9 ms. No uncommitted host source loaded.
- Red-before candidate `d745db91761416cd65dbe95594f0d71c2e32217e`, run `0a7e6c86e11f`: 2 tests, 5 passing assertions, 3 failing, no errors, 9081 ms. Successor had zero attempts and an invalid-task problem; predecessor had a merge request. Gate 44058 ms; boot 20267 ms; adopt 2190 ms.
- Changed candidate `ed56f612b0e6a9f565efab472e802ea6b6618ce6`, run `65eca3b0d586`: db-flow namespace, 19 tests, 137 passing assertions, zero failing assertions, one error, 20640 ms. Both new regressions passed and 18 tests were green. Sole red: `seon.cluster.db-flow-test/a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red`, because p has no attempt/branch after admission refuses it. Gate 57144 ms; boot 22530 ms; adopt 2199 ms.
- Baseline candidate `16a4921fbac8a46920179d2deb7ef6d3cecc6ce0` excludes the changed owners: P3's same proof passes, run `e46d1532af9e`, 3 assertions, 5126 ms; total 37185 ms. Confirms the regression belongs to this rule.
- Final `clj-kondo --lint src script test --cache false`: 0 errors, 1571 existing warnings, 7456 ms. `git diff --check` is clean.
- Corrected candidate `aabbe9f5d10c675f6306ab5b1ef155036d2ff48a`, run `441f3ca70ef4`: all 20 non-long db-flow tests green, 143 passing assertions, no failures or errors, 19142 ms. P3, P5 successor-fork regression, P6 merge-request rows and the admission table are all included. Gate 92552 ms, of which first template acquisition was 49036 ms, boot 15151 ms, adopt 1307 ms; publication rehearsal 3210 ms and adoption 503 ms. The gate costs include store preparation and fresh JVM boot, not an ordinary admission call; both named before/after behavioral probes use the production flow path.
- Source diff: +46/-17 lines (net +29); tests +72/-3 (net +69). Test-run allocation figures are unavailable from the operator summary; no memory pass claimed.
- Commit entrance: `bin/lane commit step-p5-p6 -m 'P5/P6: admit steps at their fork basis and merge only terminal steps' --test seon.cluster.db-flow-test`, with precisely the seven claimed paths. It repeats publication, adoption, archive boot, lint and the namespace proofs; it does not restart default.

## Foreign boundary and verification limits

The in-flight SCI program-context/cache work was never included in the earlier candidate or edited. It landed as `35e906c38` while a check was using default's PREPL; an external JVM replacement closed that connection and left that run's outcome unknown (diagnostic `operator-check-20261012T085904.147.edn`). The check was repeated on the new committed HEAD. This lane never stopped, reset or restarted default. Other uncommitted files and submodule changes are excluded by the lane's explicit paths. No worktree, merge, push, shared-index staging or foreign-session interaction.

The first-sorted-commit selection for multiple predecessors remains E6's octopus-fork boundary. Test-run memory is unavailable from the operator summary; no allocation improvement is claimed. The platform gate boots, publishes and adopts the slice and runs the entire non-long db-flow namespace; broad tiers remain root's work.
