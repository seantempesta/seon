# The reactive core: one way to keep a derived value current

Almost everything Seon keeps is derived: a page, a model request, health, a task's readiness, a test's verdict, the program a process runs. Each is a function of what it read. The reactive core is the one mechanism that records what a computation read and runs it again only when a commit moved something it read. Runtime owns it; every area moves its own "did this change?" code onto it and deletes that code ([the plan](plan.md#the-root-concept-one-reaction-over-the-store)).

## The idea in one paragraph

Datahike's recorder (``datahike.reads/with-reads``) sees every read of the store and records each scanned range as a key: `[:e e]`, `[:ea e a]`, `[:a a]` or `[:av a v]`. A datom a commit moves touches exactly five keys (``datahike.reads/datom-keys``). So "who must run again after this commit" is one lookup per moved key in an index `{key #{reader}}`, built from each reader's own recorded keys. That is O(d) in the commit's datoms, and constant in the number of readers and in the store's size. A reader is anything that keeps a value of a computation: a reaction, a walk's component, a test's verdict.

## Writing a reader: Reagent's API

```seon.ratom``` is Reagent's `reagent.ratom` ported, with Reagent's names and behaviour. A Clojure programmer who knows Reagent already knows it.

- **The store is a source.** `seon.ratom/store` wraps a db value; a deref inside a reaction answers the db, and every read made of it during the run becomes that reaction's key set. `seon.ratom/commit!` gives the store a new db and the datoms that made it.
- **A reaction** (`seon.ratom/make-reaction`, the `reaction` macro, `seon.ratom/run!`) is a value derived by a function of no arguments, re-run when what it read moved. A reaction that returns a value `=` to its last one signals nothing onward.
- **A track** (`seon.ratom/track`) is a reaction cached by function and arguments, so every equal `(track f args)` shares one run. Its cache belongs to the owner of the reactions, bound as `seon.ratom/*tracks*`. With no cache bound, a track runs f at each deref.
- **Ratoms** (`seon.ratom/atom`) hold values that are not datoms: page state, a process's runtime values. They are sources as the store is.
- **Views** (`seon.ratom/view`) read a pulled value as Vue 3's reactive proxy does. Each lookup records the path read, so a walk can match a tree's diff against what each component read ([reactive renders](render.md#reactive-renders)).
- `seon.ratom/reads-of` runs a function outside any reaction and answers its value, its view reads and its key set: what a walk kept as a value keeps per component.

Two of Reagent's rules matter here [M, trials A1 and T0]:
- A reaction that is not auto-run, derefed from outside any reaction, is not reactive. It runs its function and records nothing. Make the outermost reader auto-run (`seon.ratom/run!`), or deref it inside one.
- An auto-run reaction runs at once, inside the change that dirtied it. In a diamond (one source read by several paths that meet again) its top therefore runs once per path, which is three times in the reconciler's trial.

A minimal reader, at root's REPL:

```clojure
(let [s (seon.ratom/store @(:conn sys))
      ready (seon.ratom/run! (count (datahike.api/datoms @s :aevt :seon.task/ready)))]
  @ready)   ; reruns only when a datom of :seon.task/ready moves
```

## How a commit reaches its readers today

`seon.ratom/commit!` sets the store's db and moved datoms and notifies every watch on the store. Each reaction checks its own key set against the datoms (``datahike.reads/reads-touched?``) and, when touched, is dirtied: an auto-run one runs at once, the rest queue for `seon.ratom/flush!`. Nothing in production feeds the store from real commits yet; the render proc keeps its own read index fed by the writer's `:main` notice ([render](render.md#a-page-is-a-kept-walk)).

## What it costs [M, at root's REPL on main, 434k datoms]

| Path | Measured | Grows with |
|---|---|---|
| Today's `commit!` (every reader checked) | 1.25M ticks for a commit no reader read, at 1,489 readers (trial A1); ~6.4k ticks a reader (T1) | readers × d |
| An index lookup of the moved keys (trial prototype) | ~1k ticks a commit at 1, 100, 745 or 1,489 readers | d only |
| `datoms-diff` of two plain heads | 111k ticks at d = 12; 723k at d = 111; 139M and 429 MB at d = 7,163 | d; bytes superlinear at merge size |
| A reaction in memory | 176 B, plus ~26 B for its store watch, plus ~135 B a key (a boxed vector key) | its keys |
| A health class as a reaction (web T2) | first run 247 ms, 1,522 keys; an untouched commit 0 ms | what moved |

## Rules every reader follows

- **Read the store through the recorder.** A read with Datahike's recorder bound to `nil` (``datahike.index.interface/*reads*``) escapes the core: it is never re-run for. Each such site is a known gap, named in [the survey](plan.md#the-root-concept-one-reaction-over-the-store).
- **Diff two plain heads, once a commit.** Never `d/history` or another view as a diff input: a view merges its whole range, and a history seek per retraction cost 2.25M ticks for 60 datoms (tests-4).
- **A derived value is a reader, never a copy kept by hand.** If it must outlive a process (a verdict reused across branches), its key set is stored on its entity (``:seon.reads/key-set``, agreed with data and tests) and found by the reverse query: the readers whose keys a commit moved.

## What it does not do yet

Each limit below names the task that lifts it. The design, its cost analysis (n, big-O, ticks, bytes, seconds) and its red-first proofs are in that task's plan:
- Commit cost linear in readers, the diamond's glitch, a source gained mid-flush: `seon.ratom/a-commit-reaches-only-the-readers-of-what-it-moved`.
- One process-wide queue, where another owner's flush runs your reactions: `seon.ratom/a-reaction-queue-is-its-owners`.
- No owner step feeding commits (in root, the fork server and each agent): `seon.ratom/an-owner-step-advances-its-store-and-flushes`.
- A boxed key costs 135 B: `seon.ratom/a-read-key-is-one-fixnum`.
- A pull records its entity, not its attribute; lazy reads after recording ends are missed; nested recorders do not nest. These are deps' Datahike tasks, named in each plan above.
