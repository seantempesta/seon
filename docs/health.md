# Health

Health is the set of open problems: a value computed when something reads it (`seon.problems/health`), never stored, precomputed or polled. Root's page, an agent's context and the done rule each read it on the db they hold. It is the recorded classes (`seon.problems/report`) beside the derived ones (`:unnamed-site`, `:stale-pin`, `:stale-binary`, `:stale-section`, `:orphan-page`, `:leadless-section`, `:uncited-task`, `:stale-plan`, `:unsynced`, `:waived-proof`, `:stalled`, and `:problem-unlinked` over all of them), one value by class (`:seon.problems/report`).

## Two kinds of problem

A **recorded** problem is one only an effect can observe, reported by transacting it: an error recorded once per signature (`seon.error`), a task's red proof, cost verdict, death, conflict, unmerged entry, deferred test or waste written at its end ([`seon.problems/ended-classes`](reconcile.md#a-task-holds-how-its-work-ended)), and a task held for root (`:failed`). `seon.problems/report` reads them at a cost that follows the problems, never the program. A task's problems close when it is done or root cancels it. A recorded error closes, with nothing written, once its subject (`:seon.error/subject`: a var, a section, or an attribute only a live proc writes) has a fact written after its last occurrence (`seon.problems/closed?`): a refused build names root's `:seon.system/generation` (```seon.error/asked-tx```), so the next build's outcome closes it. A subject's fact is the producer's latest outcome, written on success and failure, never a counter. An error with no subject stays open until someone reads it.

A **derived** problem is something the program's facts make true now: a read over facts the loaders already write, open exactly while it is true, closed by fixing the fact. A branch is judged on every derived class its content can make (`:seon.problems/caused` on `:seon.problems/class`, ```seon.problems/caused-classes```), each read by `seon.problems/caused-split` from the datoms the branch moved; the rest (a stalled agent, a stale pin or binary, an unsynced task, a problem with no section) are the running system's state, and no branch causes them. `my.root/problems` answers health for root's main with the api's fold of repeats not yet written.

A dead citation is no class: a citation of nothing is refused at its write ([references](references.md#task-references), [the wiki in the store](references.md#the-wiki-in-the-store)).

## A law's unnamed site

A law the compiler's facts answer exactly is a derived class: `seon.problems/laws` holds each as data (the fact that marks a site, the page that must name it, the drivers left out), and `seon.problems/unnamed-sites` lists each site whose page does not cite its function (`:unnamed-site`). A site is a live form of Seon's own program (```seon.program/program-nss```; the check drivers `seon.check` and `seon.e2e` left out), and never a test: a form whose var's `:var/meta` holds `:test` is a test wherever it lives (```seon.agent-test/an-agents-own-deftest-is-no-failure-point```). A form is named by its var, else the multimethod or protocol it extends, else its form id. The one law held so is `catch` (`:form/catches`, page [failure points](failure-points.md)); clocks, measures and resolution stay `bin/laws`' text scan until the compiler's facts can tell their sites.

## A red test

A red test is a verdict, not a problem fact: verdicts are derived data cached by digest outside the store ([merging](merging.md#verdicts-are-a-cache)). A red on a branch is read by its own plan and the done rule (`:proof-red`, `:stray-red`); no class reads main's verdicts yet.

## A flaky test

A verdict is a function of its reach digest, so a test judged both green and red at one digest is a defect at its cause (a clock, a race, an input its digest misses), never a reason to run it again. The cache keeps `<digest>.pass.edn` and `<digest>.red.edn`, each written once (```seon.fx/write-verdicts!```), and never evicts a digest with a red file. ```seon.proofs/verdict``` judges a digest holding both as ```seon.proofs/flaky```: red, led by a failure saying so, never reused and never run again until a change moves the digest. `bin/test` and `bin/prove` print `FLAKY test digest` and end red. A test that never reported and a `bin/check` step's red are not kept, so neither is flaky.

## A stale section

A wiki section that cites code is stale when a cited var's contract or docstring changed after its text (`seon.problems/stale-sections`, `:stale-section`); a body change alone is not. When the loader writes a section it records the form id of each var the text cites (`:seon.doc/read`, `seon.doc/read-map`), kept while the text is unchanged. For a cited var whose form id now differs, its `:var/contract` and `:var/doc` as of the section's read (or review) are compared by value with today's (`seon.program/def-tx` keeps their history). The problem names the section, the var, `:changed`, `:was` and `:now`. The eval that makes such a change says so (`:redefined`, `seon.program/meaning-changes`), with the sections citing the var. It closes when the section is edited, or reviewed as it is with `my.doc/reviewed!`.

A section with no read yet takes its baseline from git when `:seon.config.root/wiki-baseline` is on (`seon.system/wiki-baseline`, the `:history` effect `seon.fx/wiki-history`, pure planning in `seon.doc/baseline-plan` and `baseline-reads`): the newest commit among the section's lines (`git blame --porcelain`) or the last commit whose `Reviewed: docs/x.md#heading` trailer names it (`my.doc/reviewed!` makes that empty commit), and for each cited var its form at that commit (`git show`, read by `seon.source/defining-ids` and `seon.source/defining-meanings`). A var absent from that commit's file is `:absent`, stale; one whose docstring or schema differs is `:changed`; a section git cannot answer for carries `:seon.doc/baseline-unread`, one attention entry until edited.

It is attention, never a reason to refuse done: a branch sees the sections it made stale under `:attention` (```seon.ctx.status/status```, from `seon.problems/caused-split`).

## A stale pin

A pin `docs/forks.md` states ("`reference-code/<name>` pinned at `<sha>`", shape in `seon.problems/pins`) is a `:stale-pin` (`seon.problems/stale-pins`) when its sha is not a prefix of the gitlink root recorded at its last source import (`:seon.system/source`'s `:pins`). With no import recorded there is nothing to compare and no problem. Fixing the line closes it.

## An orphan page

A page no other page links to, the map (docs/README.md) counting as a linker, is an `:orphan-page` (`seon.problems/orphan-pages`). The map is not judged, and a wiki with no map has no orphans. A link anywhere closes it.

## A section with no lead

A section whose first block is a list, a table or code is a `:leadless-section` (`seon.problems/leadless-sections`): its summary is its heading alone ([the wiki's rule](README.md)). Writing a leading sentence closes it.

## A task that cites nothing

A ready `:implement` task is an `:uncited-task` (`seon.problems/uncited-tasks`) when its plan, accumulated over its needs (`seon.task/accumulate`), cites no var, attribute or section (`:no-references`), names no proof (`:no-proofs`), still holds the placeholder proof `seon.ctx` writes (`:placeholder-proof`), or cites what the db no longer holds (`:gone`, from `:seon.task/gone`, [task references](references.md#task-references)). A design step's text plan is never judged. Rewriting the plan closes it, with nothing else written.

## A plan that went stale

A ready task whose own plan cites a var whose `:var/form` datom was written after its `:seon.task/plan` datom is a `:stale-plan` (`seon.problems/stale-plans`). A branch that makes one is shown it and never refused (`:seon.problems/attention`): changing the code a plan cites is the work, an agent's own task's plan included. It closes when the plan is written again after reading the var; when it all holds, add a line `Checked <date> against <var>: holds.`, since the same text is no new datom.

## A function that cites no section

A public function, macro or def of Seon's own source whose docstring cites no `docs/` section is an `:uncited-var` (`seon.problems/uncited-vars`; [the summary depth](references.md#the-delimiter-picks-the-depth)). It is read only for the vars a branch changed (`seon.problems/caused-split`) and shown as attention, never refusing done ([processes](processes.md#an-agent-is-never-gated)). A private var and a test are not judged; a definition is told by its recorded form's head (`seon.source/first-head`). The fix is the citation, with the mechanism written into the section, not the docstring.

## A key with no schema

Every key Seon names is one schema entry: each of Seon's own qualified keywords (a `seon` or `my` namespace) that a source form names (`:form/keywords`) and no entry defines (`:schema/key`) is an `:unschemed-key` (```seon.problems/unschemed-keys```), one row a key naming the vars that use it. A reverse ref and an enum's value are not keys. A branch that names a new key without its entry, or removes the entry of a key still named, is caused the row; it closes when the entry is written, with its `:description`.

## A form too long

A live form whose `:form/src` is longer than `:seon.config.shrink/form-tokens` tokens (`seon.print.emit/chars-tokens`) is a `:long-form` (`seon.problems/long-forms`), read only for the vars a branch changed and shown as attention.

## A proof that passes by waiver

A cost proof green because of an `:overrun` ([cost](cost.md#an-accepted-overrun-and-its-ratchet)) is a `:waived-proof` (`seon.problems/waived-proofs`): the test, the task, the size, the recorded numbers and their multiple of the proof's own bound in the worst counted dimension (`seon.cost/expected`, `seon.cost/row-ratio`; nothing measured). The proofs are the test forms referencing `seon.cost/proof`, and the `:overrun` is read from the form. A branch that adds an `:overrun` is caused the row, and one that deletes it closes it. `bin/prove` ends with `prove: N proofs pass by waiver`.

## A problem with no page

Every problem class declares its wiki section (`:seon.problems/docs` on `:seon.problems/class`), and a problem shows the link. A class with an open problem whose section is not stored, or that declares none, is a `:problem-unlinked` (`seon.problems/problem-unlinked`), a class that declares its own section. A db with no wiki has none.

## Checks are callers of Seon

`bin/check health [CLASS ...]` (`bin/test --health`, ``test-runner/health!``) holds a checkout to what a Seon agent is held to: what its branch caused. The head is the working tree's program db, kept in the program store (``test-runner/program-db``); the base is the base commit's, made by the base's own code in a process of its own (``test-runner/base-program``), from the program store, else the base store (`-health-base`), else a checkout of the base (`bin/worktree --at`) filled by its own plan. Each loads its task files and wiki with root's loaders over `:seon.config.source/dirs`. It boots no root and needs no binary. The base commit is ```seon.fx/base-commit```: a branch's merge base with main; on clean main, the previous main; on main with changes, HEAD. It judges `seon.problems/caused-split` over `seon.problems/caused-classes` (```test-runner/branch-health```), prints each caused problem and attention entry and the head's open total, and ends `HEALTH OK` or `HEALTH FAILED`. A problem the base already had is root's to show. `bin/prove` keys the step by both trees (`seon.proofs/step-digests`). `bin/laws catch` calls it for `:unnamed-site`.

## What a branch caused

`seon.problems/caused` is the set of derived problems a branch's head has that its merge base had not, which refuse its done: `seon.problems/caused-split`'s `:caused` (it also answers `:closed`, and the attention classes, the stale sections, under `:attention`). It takes the datoms that differ between base and head (the `:eavt` diff, or `seon.problems/caused-datoms`), so its cost follows what the branch changed ([cost](cost.md#a-diff-per-attribute-costs-a-descent-each)).
- The agent's turn computes it each turn (`seon.turn`'s observe), and its context lists them with the done rule's other reasons.
- Root's judge computes it at the agent's end from the writer's `:branch-diff`; it is not stored.
- `seon.reconcile/done-explain` gives `:open-problem` while any is open ([tasks](tasks.md#the-done-rule)).

Nothing gates an evaluation ([processes](processes.md#an-agent-is-never-gated)). A branch behind main is refused `:behind` and judged again after merging main.

## Cost

`unnamed-sites` is linear in the catch forms (`seon.problems-test/unnamed-sites-follow-the-catch-forms`). `caused` compares base and head by value and reads the rules only for what differs; `seon.problems-test/caused-follows-what-changed` holds it flat in untouched sections and sites, and `caused-agrees-with-the-full-reading` checks it against the full reading.

`my.root/problems` reads the health the render proc keeps (`seon.render.proc/health!`), brought to the caller's main and answered at the error fold's counts (`seon.problems/with-fold`), so a read costs what main changed (`seon.agent-test/root-reads-the-health-the-render-proc-keeps`). A move of main costs what it changed (`seon.problems/health-moved`): given the datoms `advance-main` already read, the caused classes are the held rows less `:closed` plus the new ones, and the task classes re-read only the tasks the move touched (`seon.problems-test/problems-move-at-the-cost-of-what-changed`; `health-moved-is-health` holds it equal to `health`). The problems tile (`seon.render.ui/problems-tile`) draws that value (`seon.render-test/the-problems-tile-follows-main-without-reading-it-whole`).

Root's page draws every open class; an agent's page draws only its own task's and branch's problems (`seon.render.ui/own-problems`, from `seon.problems/own`), at a cost flat in finished history (`seon.problems-test/an-agents-own-problems-cost-nothing-for-the-history`).

## A stale binary

Root's binary is stale when built from another git tree than the one main was last brought to from outside (an import or a landing): its boot writes both trees on `:seon.system/source` (```seon.system/booted-source```) and ```seon.problems/stale-binary``` derives one `:stale-binary` row while they differ. Root then forks no agent (```seon.system/stale-binary```), and its agents share no verdicts ([source](source.md#the-binary-records-its-program)). The message names the acts that bring the two into agreement (```seon.problems/stale-binary-acts```, [files and the db](source.md#files-and-the-db-two-directions)); the next boot on a binary of main's tree closes it.

## Every writer: the db is written, files follow

Nothing in a running root reads a file: whoever changes the system writes main, and main's files are written from it; an edit on disk acts on nothing until a person re-indexes ([files and the db](source.md#files-and-the-db-two-directions)). A re-index's typed error is answered to whoever asked.
