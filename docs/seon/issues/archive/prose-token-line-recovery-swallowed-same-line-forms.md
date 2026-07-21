---
type: issue
status: resolved
tags: [agent, cljs, issue]
severity: friction
---

# Prose-token line recovery swallowed same-line corrective forms

## Evidence

Battery L4 (2026-07-21, live Muse, run 9267): the agent received the fs
allowlist denial for `/etc/hosts` and replied with a correct same-line
fallback — "Got the denial for /etc/hosts as expected — falling through
to package.json now.(seon.agent.fs/read-file {...})" — three turns in a
row. Every such reply parsed to ZERO entries, so nothing evaluated,
nothing surfaced, and the run closed `:no-forms`.

Root cause in `src/seon/repl/internal.cljc`: an A.1 prose-classified
reader throw (`/etc/hosts` is an unreadable token) recovered at the NEXT
NEWLINE, silently swallowing the rest of the line including a valid
trailing form. Inconsistent by the file's own contract ("Forms BEFORE
and AFTER the failure still parse") and with the clean-read path, where
the identical sentence without the stray token evaluates its trailing
form ("prose ends here. (fs/grants)" → `:form`).

## Fix

`prose-token-recovery` — prose-token throws now recover just past the
TOKEN (maximal run to the next whitespace/comma/comment/string/opener,
always advancing), so same-line forms after a stray token parse.
Backtick-led narration spans keep line recovery (their drop unit is the
quoted prose span). Regression cases added to `prose-token-cases`;
`bin/test-cljs --test=seon.repl.internal-test` — 47 tests, 380
assertions, 0 failures. Live pod probe parses the exact failing turn-4
reply into its corrective form.
