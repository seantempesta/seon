# Health

Health is the set of open problems: a value computed when something reads it (`seon.problems/health`). It is never stored, never precomputed and never polled. Root's page, an agent's context and the done rule each read it on the db they hold.

## Two kinds of problem

A **recorded** problem is one only an effect can observe. Any code reports one by transacting it: an error recorded once per signature (`seon.error`), or an attempt's red proof, death, conflict or waste written as a fact at its end. `seon.problems/report` reads what was recorded, at a cost that follows the problems and never the program.

A **derived** problem is something the program's facts make true now. It is a read over facts the loaders already write, so nothing writes the problem and nothing closes it. It is open exactly while it is true, and fixing the fact closes it. `seon.problems/health` is `report` plus the derived classes, in the same value (`:seon.problems/report`). Not this: a check that stores its findings and must remember to retract them, or a timer that re-runs a scan.

## A dead citation

A wiki section stores the names its own text cites as references (`:seon.section/names`, written by `seon.docs/load-tx` through `seon.ref/kept`). A prose word is kept only when it names something at load; a backticked name is always kept. When one of those names no longer names anything (`seon.ref/names-on-db?`, the one rule, reading main's var names as a set first), it is a `:dead-citation` (`seon.problems/dead-citations`). The commonest case is a var deleted after the page cited it.

A link (or a cited `path#anchor`) that reaches no section is kept on its section as `:seon.section/dead-links` instead of refusing the load, so one stale link never takes the wiki down. `seon.problems/dead-citations` reads those targets too: one still no stored section is a `:dead-citation` row with the target under `:target` ("<section> links to <target>, which is no section"), closed when the section exists.

Not covered yet:
- A task's plan. It cites the vars it will create (its write set), so "names nothing" there is the plan, not a dead citation; `seon.task/citation-problems` answers those at the write.
- A cited keyword or path.
- A section older than the code it cites.

[references](references.md#the-wiki-in-the-store) has how the wiki is stored.

## A law's unnamed site

A law the compiler's facts answer exactly is a derived class too: `seon.problems/laws` holds each one as data (the fact that marks a site, the page that must name it, the check drivers it leaves out), and `seon.problems/unnamed-sites` lists each site whose page does not cite its function (`:unnamed-site`). A site is a form of Seon's own `src` (bin/laws scans src): an agent's app and any test are not, since a deftest's `is` expands to a catch (found by e2e-newns, whose agent's test was refused done for it). Today that is `catch`, whose sites from `:form/catches` are exactly `bin/laws`' 26 (measured 2026-10-23). Clocks, measures and resolution stay `bin/laws`' text scan until the compiler's facts can tell their sites: a host member is recorded as `jolt.host/static-member`, not by its name, and a quoted symbol counts as a ref.

## A red test

The test service ([merging](merging.md#the-test-service)) leaves a fact per test digest on main. `seon.problems/report` reads the newest result of each test (`:test-red`, area tests): a red one is an open row naming the test, the branch and tx it ran at, and the agent that asked. A later green at a new digest is a newer result, so the row is gone with nothing written; the red fact stays as history.

## A stale section

A wiki section that cites code is stale when the code changed after its text (`seon.problems/stale-sections`, class `:stale-section`). When the loader writes a section's text it records the form id of each var the text cites (`:seon.section/read`, `seon.docs/read-map`; a form id is the form's own digest, so it moves exactly when the form's text does), and while the text is unchanged a reload keeps what was recorded: it is the code as it stood when the section was last written. A cited var whose form id now differs is a row naming the section, the var and both ids. It closes when the section is edited, which re-reads the code, or when someone has read it as it is and says so with `my.docs/reviewed!` (who, and the form ids it read at; the older reviews stay in the history), with the text untouched. The comparison is by value, never by tx: a branch's tx counter is its own.

It is attention, never a refusal (root's ruling, 2026-10-23): every edit of a var stales the sections that cite it, and refusing done for that would force a doc edit with every code edit. A branch that changes a var sees, in its context under `:attention`, the sections it made stale (`seon.problems/caused-split`), the docs to read and probably fix; `seon.problems/caused` and the done rule do not carry them.

## An orphan page

A page of the wiki that no other page links to, the map (docs/README.md) included as a linker, is an `:orphan-page` (`seon.problems/orphan-pages`): a section of another page has a reference to one of its sections, or it is orphaned. The map is the root and is not judged, and a db whose wiki has no map has no orphans (unknown). Adding a link anywhere closes it, with nothing written.

## A problem with no page

Every problem class declares the section of the wiki that says what it is and how it is fixed (`:seon.problems/docs` on `:seon.problems/class`, beside its area), and a row shows the link. A class with an open row whose section is no stored section, or that declares none, is a `:problem-unlinked` (`seon.problems/problem-unlinked`, area health), itself a class that declares its own section, so a missing page for the unlinked class is one more row. A db with no wiki has none (unknown). Not built: the case of a recorded error that no task answers and whose owner namespace has no task file.

## Checks are callers of Seon

`bin/check health [CLASS ...]` (`seon.check/health-check`) is the one check of these classes. It boots Seon on the checkout's source and task files, loads the wiki, prints each open row of the derived classes, and ends `HEALTH OK` or `HEALTH FAILED`. `bin/laws catch` calls it for `:unnamed-site`; it checks nothing of its own. Not this: a second checker over the files (`bin/check docs` beside the class).

## What a branch caused

`seon.problems/caused` is the set of derived problems a branch's head has that its merge base had not: what the branch's own work broke.
- The agent's turn computes it each turn (`seon.turn`'s observe), so its context lists them with the done rule's other reasons.
- Root's judge records it at the attempt's end (`:seon.attempt/caused`, from the writer's `:branch-diff`).
- `seon.reconcile/done-explain` gives `:open-problem` as a reason while any is open, so the task is held for root naming it ([tasks](tasks.md#the-done-rule)).

Nothing gates an evaluation. An agent may break a citation in one eval and fix it three evals later (AGENTS §4: get out of an agent's way). A merge needs no rule of its own: a branch behind main is refused `:behind` and judged again after merging main.

## Every writer: root loads what changed

Root watches its checkout and its repository's refs, so whoever writes (a Claude session, a Seon agent, the owner's editor, git) reaches the same facts with no script of their own (owner, 2026-10-23: "seon code detecting and updating, not external scripts").
- `target/watch` (native/watch.c, built by `bin/build`) is an OS child over macOS FSEvents (latency 0, no timer, no poll). It prints one record per change: its kind letter, the absolute path, then NUL, since a path may hold a newline. It exits when its parent does.
- `seon.watch/start!` reads it on one edge thread, the blocking read AGENTS §5 allows. That thread is an outside caller of root's API, as a server connection is: a proc's step must never wait on the api's answers. Each batch is what the stream holds when a read returns, so no clock decides.
- `my.root/watch!` (started by `seon.check/root-serve`, the standing root `bin/root` runs) decides what each batch makes root load (`my.root/watch-kinds`):
  - a path by its loader: a page under docs/, a task file, Seon's source;
  - a moved ref (a commit or merge on any branch): the source;
  - a rescan or a moved root, where FSEvents coalesced or dropped events under a subtree: every kind.
- `my.root/watch!` loads each kind once (`load-kinds!`); a batch no loader reads writes nothing, so root's own store writes never feed back. Batches, paths and rescans are counted in root's `:seon.root/watch` fact.
- What changed while root was down is boot's to load, as it always was: the wiki (`seon.root/wiki-tx`) and the source (`seon.root/import-source!`). Task files load on their change or by `my.root/load-tasks!`, never at boot.
- A watcher that cannot start or dies is a `:platform` error on main (`seon.root/platform-tx`), shown on the banner; root goes on unwatched, never polling ([failure points](failure-points.md)).

`load-kinds!` answers the problems a load made true (`seon.problems/caused`, of main before and after), kept as `:last-opened` in the watch fact; a loader's typed error (a refused task file) is recorded as a `:platform` error (`my.root/load-errors`), and a throw in a batch's handler is the watcher's recorded death.

## Cost

Measured on 2026-10-23 (`seon.problems-test/dead-citations-follow-the-citations`, every citation dead, the worst case): 68 us, 68 KB and 4 contracted calls per cited name at n=1000, against an estimate of 90 us, 170 KB and 6. A citation that names something costs one set lookup. On root's own store (152 sections, 1,280 vars), a set built from main's var names and read for 1,712 names took 0.37 ms, against 18 ms for one AVET seek per name; that is why the class reads a set.

`unnamed-sites` costs about 75 us and 75 KB per catch form (measured at n=10, 100 and 1000: linear, two contracted calls per site, `seon.problems-test/unnamed-sites-follow-the-catch-forms`); `stale-pins`, `health` and `caused` have their own cost proofs in the same file. `caused` compares base and head by value (not by tx: a branch's tx counter is its own) and reads the rules only for what differs: the sections whose citations or dead links differ, the sections citing a name a var or task lost, and the forms carrying a law's fact whose catches or text differ (every site when a section of a law's page changed). Its floor is a scan of those attributes' datoms, with no pull and no rule. Measured in a REPL on Seon's own program (300 sections, 880 citations, 1,735 vars, 30 catch forms), after deleting a var that is cited: 11-12 ms, against 26-29 ms for reading both classes in full over both dbs; an unchanged branch 7 ms. With `:stale-section` (a scan of the vars' and forms' ids, on head and on base) the floor is 12 ms and a change 12-13 ms, and the whole of `health` on that program is 14 ms (0 stale sections right after a load, 0 orphan pages, 0 unlinked classes). `seon.problems-test/caused-follows-what-changed` holds it at 10, 100 and 1000 sections and sites with a fixed change (about 25 KB per section and site for the scan, 13 contracted calls however large), and `caused-agrees-with-the-full-reading` checks it against the full reading on eight changes.
