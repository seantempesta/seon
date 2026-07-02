---
type: vision
status: active
tags: [vision, agent]
---

# Think in Clojure — the meta-system and the translation goal

Three escalating capabilities, one mechanism. The mechanism already exists:
the program graph (`:seon.fn` / `:seon.ns` / `:seon.schema` entities) plus
the discipline that makes it queryable — every key namespaced, every public
fn fully specced, data definitions and the functions that process them
co-located. Everything below is that graph, widened.

## 1. Affordance surfacing — the skills-killer

**Everything is data definitions + functions that process that data — so
"what can I do next?" is a query, not a document.** The fully-specced
map-in/map-out convention exists precisely so a Datalog query can join
function specs to the data those functions operate on. The affordances
block derives from that join: take the schemas of the values in the
agent's recent evals (and its current namespace's data), find every fn —
in ANY namespace — whose input spec accepts those shapes, and surface
them, ranked (exact schema match, then subsumption, embedding similarity
and observed usage as tiebreaks).

The agent never "reaches out" for guidance. Holding a `::expense` map
makes `totals-by-category` visible; storing a `:my.kb` row surfaces the
recall verbs; a `:seon/error` value surfaces the fns that consume errors.
Relevance is **defined programmatically and always loaded** — computed
from data flow at render time, self-healing like every derived block.
This is the replacement trajectory for the skill library: procedural
knowledge migrates into specced code (runnable, composable, 1/10th the
tokens); what remains of "skills" is whatever cannot yet be expressed as
data + functions — and that remainder should shrink toward zero.

This is also the self-building flywheel: an agent that writes a specced fn
has, by writing it, taught the system when to surface that fn — to itself
and to every other agent. Self-correcting follows: a failing shape (an
error value, a spec violation) surfaces the fns that fix it.

## 2. The meta-system — index other codebases into the same graph

The analyzer currently feeds the graph from seon's own Clojure source.
Widen the intake: index ANY codebase into the same entity shapes —
namespaces (modules), data definitions (types/schemas/tables), functions
(with their input/output shapes), and the relationships between them. The
knowledge of a foreign system is stored the way seon stores knowledge of
itself: as schema'd data in the graph, with functions for
storing/retrieving/interacting alongside. A system that understands a
system — so the agent reasons about a client's codebase with the same
query-driven context it has over its own: "what's in this module, what
shapes flow through it, what processes them, what would this change
touch."

**LSP is the universal intake adapter.** One LSP client, N language
servers: symbols, references, definitions, type signatures — mapped onto
the graph's entity shapes. Any LSP-compatible codebase becomes indexable
without per-language analyzers. (Vendored starting points:
`reference-code/claude-code-lsps`, `clj-kondo`, `orchard`,
`clojure-mcp`.) Where LSP is thin (rich types, schemas), embeddings over
source — the same ONE index — carry the semantic remainder.

## 3. The translation goal — solve in Clojure, ship in theirs

Explicit goal, carried forward from how Rich Hickey contracted: solve the
client's problem in Lisp first — where the essence is visible and the
language doesn't impose incidental complexity — then translate the
working solution into the target language's narrow best-practice idiom.

The coding agent does its **thinking, modeling, and planning in Clojure**:
schemas for the domain, pure fns over them, the solution proven live in
the REPL against real data. Then it **implements in whatever the user's
codebase speaks**, using the meta-system's index of that codebase (its
idioms, its existing shapes, its conventions) as the translation target.
The Clojure artifact remains as the executable specification — the thing
tests and future changes are reasoned against; the target-language code
is its projection.

This composes with the rest of the runtime: the plan lives in the plan
tree, the foreign index in the graph, the translation verbs are specced
fns, and the whole loop is drivable and measurable (SWE-bench-class
benchmarks through the same `/solve` door are the natural fitness
function for translate-quality).

## 4. Horizon: seon writes seon

Self-hosting is a design goal (explicitly NOT current work): the runtime's
own source is already in the graph (code-as-data — the core seed IS the
indexed codebase), so the same flywheel points inward. An agent proposes a
change to a core fn as data; the change persists IFF it passes the gate —
tests green, schema validation, instrumentation clean — the same
tests-and-validations bar a human change meets, mechanized as the publish
gate the code-as-data concept already names. Easy persistence for
validated code, impossible persistence for unvalidated code. Everything
above (affordances, the meta-system, translation) makes this safer over
time: the system that understands systems eventually understands itself
well enough to be its own client.

## Sequencing reality

Affordance surfacing is buildable now (the join is over data that already
exists; the block renderer is the existing mechanism). Foreign indexing
begins as a research spike: one LSP server, one small real codebase,
mapped into graph entities — proven by an agent answering questions about
it from context alone. Translation is the capstone and is measured, not
asserted: it ships when a driven agent solves-in-Clojure and
implements-in-target on a real benchmark task. We-are-here and ordering:
[[roadmap]].
