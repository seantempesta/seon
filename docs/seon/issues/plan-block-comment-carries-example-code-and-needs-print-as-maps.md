---
type: issue
status: open
severity: friction
tags: [issue, render, plan, context, ugly-output]
---

# The plan block's thinking comment carries fifteen lines of example code, and `:needs` prints as a set of maps

Read from Juniper's stored opening on `default` at 2026-09-09 20:00
(after `fd8646edd`). The plan block's comment embeds two commented-out
example forms before the pull — an add that pulls the plan's `:db/id` and
computes the next position with `reduce max`, and a `retractEntity`
remove — fifteen lines of `;;` code in the agent's own thinking voice.
The owner's rule: no padding. And the add example is heavier than the
data model now needs: the plan is addressable by `:my.plan/agent`, so an
add is one map, and position can be derived from the current count.

`:needs` prints as `#{#:my.plan.item{:id "juniper/query"}}` — a set of
maps for what is a vector of ids.

Fix: the block's comment is one line ("My plan is my instructions; a
step is done when it has :completed-tx. (doc my.plan) shows how to add,
complete, and remove steps; ids are (seon.id/id title 8)."); the examples
live in `(doc my.plan)` as the simplest forms (identity upsert; one
`:db/add` for completion via "datomic.tx"; `retractEntity`); `:needs`
projects to ids. Settings: the effective block drops dials the agent
cannot act on (`api-key-variable`, `chars-per-token-prior`, `endpoint`,
backup model) unless a settings dial asks for the full set.

Also on the same page (20:30): the "Turns (3)" concern block still prints
evaluation sources (`(help)` appears there as well as in "Context now" and
the would-be system turn), so the history shows three times on one page;
the ruling (chart §14a) is turn headers only in that block.

