# Stale sections: what the owner asked, what is known, the first experiment

Working paper, 2026-10-26. Nothing built; root's ruling: stale sections come back as a part on the
one path (agent view's revision 4.2: view → page → diffs), found by experiments in Seon agents'
REPLs first, design after.

## The owner's direction (to this session, meant for root)

- `:seon.section/read` as an error's subject is a code smell, and so is the machinery behind it: "we
  know because we are using immutable data generated from the database and the database knows
  exactly what has changed... there's an efficient way to know that no immutable values have changed
  and anything that's new has"; "only need to re-render those parts. The rest should hit cached
  renderings. Make this simple and fast."
- "I want this tested with agents in the repl and confirmed to be correct and efficient and then the
  design is proposed after we've tried a few things and have found the best way."
- "do not build yet another system." The context's rendering paths are not collapsed yet: seon.context's
  request assembly, seon.render.ai's faces, the page (seon.render.ui over the walk, seon.render.proc's
  component cache) and seon.screen (one-derivation slices 2, 3, 6′, 4b, 4c, 5 unmerged).

## What exists today

- `seon.problems/stale-sections`: a section is stale when a var it cites changed its docstring or
  contract since the section was written or reviewed. It compares the form ids the section recorded
  at load (`:seon.section/read`, `seon.docs/read-map`) with the current ones, then reads history
  (`value-at`) for the meaning (`seon.program/meaning`) then and now.
- The baseline: a section loaded with no `:read` asks git when it was last written
  (`seon.root/wiki-baseline` → the `:history` effect, `seon.fx/wiki-history`; `seon.docs/baseline-plan`,
  `baseline-reads`, `baseline-failed`); a failure is each section's `:seon.section/baseline-unread`,
  shown by health as a `:stale-section` row. `my.docs/reviewed!` stores the form ids a review read.
- Measured in the lab root (fresh store, main at 78ef3f61b): 412 sections, 2,990 vars, 1,155
  section→var citations (`:seon.section/names`), and **0 `:seon.section/read`**: boot writes the wiki
  in its boot commit before it imports the source (`seon.root/start!`, `import-source!` after), and
  each watcher batch loads `:docs` before `:source` (`my.root/load-kinds!` sorts the kinds), so a
  section's read is taken against a program that is not there yet, or not yet the new one. Today's
  check finds nothing stale on a fresh store.

## The first experiment to try (root's ruling)

The rule: a section is stale when a cited var has a datom on what the section describes
(`seon.program/meaning`: `:var/doc`, `:var/contract`) in a transaction after the section's
`:seon.section/text` (or its last review). The rule `seon.problems/closed?` uses for errors (a later
fact), not a second one. The writer adds no datom for an equal value, so a reformat or a body edit
cannot make a section stale.

In a Seon agent's REPL, on its branch of a lab store:
1. Today's `stale-sections` and the rule side by side, after random edits of a cited var's
   docstring, contract and body (the property: the rule's rows are exactly the sections citing a
   var whose meaning changed after them; a body edit makes none).
2. Cost in contracted calls and bytes at the store's size (412 sections, 1,155 citations) and grown
   (×10): today's scan of every read and form against one eavt seek per citation.
3. Then as a part of the page on the one path: its inputs ctx values, so an unchanged var is
   `identical?` (seon.ctx/patch's guarantee) and the part's memo a pointer test; only a transaction
   touching a cited var's meaning renders it again.
4. The load order: source before docs, at boot and per batch, so a section written with its code is
   current (else every section edited with its code reads stale).

What it would delete, if the experiments bear it out: `:seon.section/read`, `:seon.section/baseline-unread`,
`seon.root/wiki-baseline`, `seon.docs/baseline-plan`, `baseline-reads`, `baseline-failed`, `read-map`,
`seon.fx/wiki-history` (the `:history` effect), the form-id and history comparison in
`seon.problems/stale-sections`, and the form ids of `:seon.section/reviewed`. Given up: drift before
the store existed (git was the only source) and `Reviewed:` lines in old commit messages.
