# Seon's own source is the program

The self-improvement loop (owner, 2026-10-21): agents change Seon's own functions on their branches, the merge lands them on main, main's files are written back exactly and committed on a merges branch, and the next generation is built from that commit and resumed on the same store. This page is how each step works and what it records.

## Import: the running generation's source as main's program

With `:source {:root dir :dirs ["src"] :branch "jolt/seon-merges"}` (or the config's `:seon.config.source/root`, with `:seon.config.source/dirs` and `:seon.config.source/branch`; a run states it with `--set :seon.config.source/root=\"dir\"`), root reads the tree through the `:source` effect at boot, when main holds no imported namespace yet. Each file is read form by form with its exact text, the whitespace and comments before each form (`:form/lead`) and the text after the last (`:ns/tail`). Each form is analyzed by Jolt's compiler without running it, giving the same account an agent's REPL records. What happened is root's `:seon.root/source` fact (files, forms, ms, or the error).

Imported forms carry `:form/source true`: the binary already holds them compiled. Root and the fork server never evaluate a namespace imported from a file (`:ns/file`); an agent does run its branch's changes to one (`load-diff!` skips only the imported text). Measured on next/src: 54 files, 1,168 forms, 418 ms of analysis and 1.0 s to transact in the test runtime; 1.5 s at a root's boot.

```seon.root/start!``` asks for the import (`:seon.config.source/*` in ```seon.root/start!```'s opts or the config); ```seon.fx/read-source``` reads the tree; ```seon.source/read-forms``` keeps each form's exact text and lead, ```seon.source/accounts``` analyzes them, ```seon.source/text-tx``` and ```seon.source/import-tx``` write `:ns/file`, `:ns/tail`, `:form/lead` and `:form/source`; ```seon.source/file-ns``` and ```seon.source/source-nss``` name what was imported. ```seon.program/load-all!```, ```seon.program/load-diff!``` and ```seon.program/load-changes!``` skip the imported text.

## Write-back: main's files, byte for byte

`file-text` is a namespace's live forms in their order, each after its lead, then its tail. A form recorded at a REPL has no lead: a blank line goes before it. A redefinition takes the place and the lead of the form it supersedes. With no merge, import then write-back is a zero diff over all of next/src (`seon.source-test/import-then-write-back-is-a-zero-diff`). After a merge, only the files of the namespaces whose forms changed are written (`changed-nss` of the train member's changes, which the writer puts on each merged member's answer as `:nss`).

```seon.source/file-text``` builds one file, ```seon.source/files``` every changed namespace's, from ```seon.source/changed-nss``` of what ```seon.writer/merge-branch``` merged; ```seon.fx/write-file``` writes one.

## Commit: the merges branch

The reconciler asks for the write-back after a train lands: it reads main, computes the files, and asks the `:git` effect to commit them on the merges branch with a message naming each merged task and attempt. The commit uses git's plumbing (blobs, a tree from the branch's own, `commit-tree`, `update-ref` from the head it read), so no working tree or checkout moves; a branch not there yet starts at the tree's HEAD. The sha is recorded on each merged attempt as `:seon.attempt/commit`, or the refusal as `:seon.attempt/commit-error`. `bin/check loop` proves the turn on a clone of the repository.

```seon.reconcile/reconciler-proc``` asks for it, ```seon.source/commit-message``` names the merged work, ```seon.fx/git-commit``` commits; the answer lands on the attempt as `:seon.attempt/commit` or `:seon.attempt/commit-error`.

## The next generation

Not automated yet (task `seon.source/the-next-generation-runs-from-the-merges-commit`). Proven by hand on 2026-10-21: the commit `bin/check loop` made (seon.fixtures/generation 1 -> 2) was built in a copy of the worktree, and that binary's root, resumed on the run's kept store, answered 2 with main still holding the merged attempt and its sha.

```seon.e2e/loop-run``` is the turn `bin/check loop` runs; ```seon.fixtures/generation``` is the function it changes.
