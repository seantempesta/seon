# probe-boot-ready-opus: why 590475a4d took 29.7 s to ready and 43 s in total (2026-09-27)

This probe was read-only against default (pid 6808, launched 04:02:07Z on 590475a4d). Nothing was restarted, and nothing
was written to default's store. The probes ran in a scratch JVM namespace that has since been removed, and clj-kondo
ran against a scratch copy of the analysis cache.

## Broken

1. **Boot republished the whole program (`:all`) because fn.clj's bytes changed, although no analyzer definition
   changed.** `launched-analyzer` (src/seon/cluster.clj:2474-2489) judges the analyzer by *files*. If any file in
   analyzer reach launched with bytes other than the published ones, that file's digest replaces the definition
   digests, so `analyzer-changed?` (cluster.clj:1966-1973) goes true and `full-source-refresh!` classifies `:all`.
   The publication then stored the same analyzer digest as before (`fb7d8a4a…`), which shows the full reanalysis
   changed none of the 428 reached definitions. So this is a false positive in the file-level test. It is not the
   ruled cost of an "analyzer-reach commit". The design is from ea5efae65 (2026-09-25): "forces one full
   reanalysis". Today it was triggered by fn.clj edits in b3f1a7ae7 (declare edges). c4dbc48c5 (sci/eval.clj) is
   not in analyzer reach and did not contribute. New task in `resources/seon/tasks/seon.cluster.edn`
   (`boot-reanalyzes-everything-when-an-analyzer-file-changes-bytes-only`).
2. **Every commit that changes `src/` misses the program class cache as a whole.** That adds about 6–8 s to the
   total before `start!` begins. `program-classes` (script/seon/operator.clj:557-577) keys one directory on the Git
   tree of all of `src` (b77a3fa63). One changed namespace therefore recompiles every first-party namespace, which
   costs time in proportion to the program, not to the change. This was a deliberate ruling (issue
   `move-to-head-takes-a-minute-or-more-not-seconds.md`: "first-party namespaces compile from source by design").
   It is reported here because it is today's second-largest term. There is no new task: the ruling owns it.
3. **Not explained: b86a85c36's 45 s total (7.5 s ready).** It was a test-only commit, and its class directory was a
   hit (`7c835a9…` shares a0f795375's src tree `a1774a4b7f` and was last used at 21:09:32 local, the restart
   minute). The 37.5 s between total and ready lies outside boot: down, head capture, and resume. Successful starts
   write no `operator-start-*.edn`, so the phases are gone. Root still has that start's printed
   `:seon.operator/phases`.
4. **Not measured: the selected-publication cost for 2b6355fa7 (14.2 s ready, 18 inputs, no analyzer file).** Only
   the live JVM's layers are readable, and boot publication runs unarmed with `*source-progress!*` unbound (task
   `17027736987a`), so no earlier boot left its breakdown.

## Evidence

**Layers of the running boot** (`seon.operator.runtime/running-instances`, `:seon.boot/layers`, cumulative ms):

| layer | at-ms | own ms |
|---|---|---|
| prepl-server | 380 | 380 |
| store | 435 | 55 |
| **branch** | **27,341** | **26,906** |
| cluster-connection / commit-id | 27,348 | 7 |
| config-result | 27,910 | 562 |
| sci ctx (arming) | 29,156 | 1,246 |
| flow graph | 29,633 | 477 |
| web served (ready) | 29,653 | 20 |

store→branch in `stand-boot-layers!` (src/seon/cluster/boot.clj:154-220) covers three steps:
`ensure-dependency-analysis!`, `changed-source-paths` + `refresh-source!`, and `retire-unclaimed!`. On the unchanged
restart (a0f795375) the whole boot was ready in 3,660 ms, so about 26 s of this is the publication.

**It was `:all`.** On the parent publication 6ab8910c, the stored analyzer digest is `fb7d8a4a706f…`.
`launched-analyzer` over the same commit with this JVM's `launched-source-digests` gives `fb9ebbe671f5…`, a
mismatch, so `analyzer-changed?` is true. The current publication 6ab89561 (04:02:41Z) stores `fb7d8a4a706f…`, the
same digest, and `launched-analyzer` against it gives `fb7d8a4a…`. Across the last seven `current-src` publications
(02:04Z–04:02Z), the only analyzer-reach file whose digest changed is `src/seon/fn.clj`, at the 590 boot.
Analyzer reach has 428 declarations in 14 files: schema.clj 100, db.clj 97, fn.clj 79, fn/analyzer.clj 41,
program.cljc 21, schema/internal.cljc 20, schema/edn.clj 19, error/refusal.clj 16, schema/datahike.clj 16, cache.clj
7, id.clj 5, fs.clj 4, fn/signature.cljc 2, env.clj 1. Reading it took 120 ms.

**What `:all` costs against a selection.** `seon.fn/analyze-rows` ran on the live JVM, armed and warm, against the
published database with a scratch cache root:

| selection | inputs | rows | ms |
|---|---|---|---|
| fn.clj only | 1 | 202 | 687 |
| 68c721fbb..590475a4d changed inputs | 24 | 1,191 | 1,664 |
| `:all` (the whole inventory) | 824 | 9,633 | 9,110 |

Boot pays the `:all` row cold and unarmed, plus contract rows and the reconcile transaction over 9,633 rows. The
2026-09-26 replay put those at about 2.8 s and about 5 s warm (perf-boot-opus Resume 3). That adds up to the 26.9 s
layer. A selected publication of the same 24 inputs would have been about 1.7 s of analysis plus a
proportionally smaller reconcile.

**Class cache.** `target/dev-program-classes/` holds `00314d27…` (promoted 22:02:44 local, the 590 launch: a
miss), `ee282263…` (21:44, 68c721fbb: a miss, src changed), `5d61063f…` (21:32, 2b6355fa7: a miss, src changed),
and `7c835a9b…` (21:09, a0f795375/b86a85c36: the same src tree, a hit). Total minus ready is 5.7 s on the hit and
13.2 s, 11.8 s and 13.4 s on the three misses.

**Comparison with earlier evidence.** perf-boot-opus measured `:all` at 81.6 s store→branch before 46ad08a41 and
9 s warm analysis after it, and the move-to-head issue records about 27 s per analyzer-reach commit. Today's 26.9 s
is that ruled cost, now reached through a file-level false positive. Task `1d92b52b2470` already records the 27–32 s
`:all` cost. It does not record that `:all` fires with no definition change.

## Cause, fix, owner

**Regression source.** The mechanism is ea5efae65, whose file-level `launched-analyzer` is conservative by design.
b3f1a7ae7 tripped it by changing fn.clj's bytes (lines inside analyzer-reach files) without changing any reached
definition digest. It is not c4dbc48c5, and it is not whole-program work proportional to the changed files: the
cost is the whole program, whatever changed.

**Smallest fix (owner `src/seon/cluster.clj`, `full-source-refresh!` / `launched-analyzer`, about 20 lines, no new
machinery).** The files that differ from what was published always fall inside `changed`, so they get analyzed on
the selected path anyway. When the only reason `analyzer-changed?` holds is `::launched-analyzer`:
1. analyze the selected `changed` paths first;
2. compute `published-analyzer-digest` over the stored function rows with the re-analyzed files' rows overriding
   them (reach walks `:seon.fn/calls` from `seon.fn/analyze-rows`);
3. classify `:all` only if that digest differs from the stored one, and otherwise publish the selected rows
   already in hand.

This keeps ea5efae65's guarantee: a boot on changed analyzer code never compares the old analyzer with itself,
because the digest now comes from the new bytes' definitions. For 590 this would have been about 1.7 s of analysis
in place of about 9 s warm, or about 26 s cold at boot. Regression: a boot or publication whose only analyzer-file
edit leaves reached definition digests unchanged (a comment, or a non-reached defn in fn.clj) classifies
`:selected`, and one that changes a reached body still classifies `:all`.
Risk: the analyzer identity only covers definitions reachable through `:seon.fn/calls`. Macros, config EDN, and
declared-but-uncalled Vars fall outside it, and the ruled adoption path (`analyzer-digest`) already accepts that
limit.

**Second term (script/seon/operator.clj, ruled).** A key per namespace, or reusing the previous class directory
minus the changed namespaces and their dependents, would make the class cost follow the change. That is a change to
a root ruling (b77a3fa63), so it is listed for root and not designed here.

## Changed paths

- `docs/research/agent-platform/landing/boot-ready-regression-2026-09-27.md` (this note)
- `resources/seon/tasks/seon.cluster.edn` (+1 task: the file-level `:all` false positive)

## Focused checks for root

After a fix lands: `--ns seon.cluster.program-change-test`, `--ns seon.cluster.publication-delta-test`, and the
ea5efae65 regression in `seon.fn-test` that guards "boot on changed analyzer files reanalyzes". Then run one
`start --head` whose only src change is a non-reached edit in fn.clj, and read `:seon.boot/layers` store→branch
(target under 3 s).
