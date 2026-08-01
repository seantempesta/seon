"""Task set for the DeepSeek-V4-Flash thinking-mode interrogation (2026-08-01).

Every code task names an exact fn and demands ONE ```clojure fence so
extraction is mechanical. Graders live in graders.py and were written
before any model output was seen.
"""

VALUE_CLJC = open("/Users/sean/src/seon/src/seon/render/value.cljc").read()

TASKS = {}

TASKS["t1-transducer"] = """Write a Clojure transducer `dedupe-by`.

`(dedupe-by f)` returns a transducer that removes elements whose `(f x)` is
equal to the `(f x)` of the element immediately preceding it in the output.

Hard requirements:
- It must work with `into`, `transduce`, and `sequence`.
- The SAME transducer value must be reusable: `(let [xf (dedupe-by :k)] ...)`
  used in two separate reductions must produce identical results both times.
- It must correctly forward the init (0-arity) and completion (1-arity) arities.
- Metadata on retained elements must survive.
- `nil` is a legal value of `(f x)` and two consecutive nils are duplicates.

Reply with exactly one ```clojure fence containing a single top-level
`(defn dedupe-by ...)` form and nothing else."""

TASKS["t2-chunking"] = """In Clojure 1.12 on the JVM, consider:

```clojure
(def side-effects (atom 0))
(def result
  (take 3 (map (fn [x] (swap! side-effects inc) (* x x)) (range 100))))
(doall result)
@side-effects
```

Answer two things:
1. The EXACT integer value of `@side-effects` after this runs, and why.
2. Write a function `(map-exactly-n n f coll)` returning a vector of the first
   `n` results of applying `f` to elements of `coll`, guaranteeing `f` is
   called EXACTLY `n` times (or `(count coll)` times if the coll is shorter),
   for any `coll` including a chunked `range`.

Reply with your integer answer on a line of the form `ANSWER: <integer>`,
then exactly one ```clojure fence containing a single top-level
`(defn map-exactly-n ...)` form."""

TASKS["t3-cas-reduce"] = """Write `(apply-fenced init txs)` in Clojure.

`init` is a map `{:basis <long> :applied [] :rejected []}`.
`txs` is a sequence of maps, each `{:tx/expect <long> :tx/id <keyword>}`,
possibly also carrying `:tx/halt true`.

Reduce `txs` over `init` with these rules, in this order:
1. If the tx has `:tx/halt true`, stop immediately WITHOUT processing it and
   return the accumulator as it stands (use `reduced`).
2. Otherwise, if `(= (:tx/expect tx) (:basis acc))`, the CAS succeeds:
   conj `:tx/id` onto `:applied` and increment `:basis` by 1.
3. Otherwise the CAS fails: conj `:tx/id` onto `:rejected`; `:basis`
   is unchanged.

`:applied` and `:rejected` must stay vectors in encounter order.

Reply with exactly one ```clojure fence containing a single top-level
`(defn apply-fenced ...)` form and nothing else."""

TASKS["t4-datalog-malli"] = """A Datahike/DataScript database holds agent run facts with this EAV shape
(all attributes namespaced, refs are entity ids):

  :seon.cluster.agent/id        - :db.unique/identity, string
  :seon.agent.run/agent         - ref to the agent entity
  :seon.agent.run/epoch         - long
  :seon.agent.run/receipt       - cardinality many, ref to receipt entities
  :seon.agent.receipt/status    - keyword, one of :ok :interrupted :failed
  :seon.agent.receipt/tokens    - long

Write ONE Clojure file containing exactly two top-level forms:

1. `(def interrupted-token-load-q '[...])` - a Datalog query taking `$` and
   one input `?agent-id` (a string). It must find every receipt reachable
   from that agent's runs whose status is `:interrupted`, and return a
   relation of `[?epoch ?tokens]` - one tuple per interrupted receipt.

2. `(def RunLoad [...])` - a Malli schema for the summary map
   `{:agent/id <string> :run/epoch <long> :run/interrupted <long>
     :run/total <long>}` where `:run/interrupted` must never exceed
   `:run/total`. Express that cross-key constraint with a `[:fn ...]`
   predicate applied to the whole map, and make sure the schema is still
   usable with `malli.generator/generate` (the generator must not reject
   samples forever). All four keys are required. Do not use `:any`.

Reply with exactly one ```clojure fence containing those two forms."""

TASKS["t5-debug"] = """This Clojure is buggy. It is supposed to return a vector of the running
sums of `xs`, and to log exactly one line per element to the atom `log`.

```clojure
(def log (atom []))

(defn running-sums [xs]
  (loop [i 0
         total 0
         out []]
    (if (< i (dec (count xs)))
      (let [x (nth xs i)]
        (recur (inc i) (+ total x) (conj out (+ total x))))
      out)))

(defn logged-sums [xs]
  (let [ys (map (fn [x] (swap! log conj x) x) xs)]
    (reset! log [])
    (running-sums (vec ys))))
```

There are TWO distinct bugs: an off-by-one in the loop, and a bug caused by
laziness interacting with the side effect / `reset!`.

Explain both bugs in one or two sentences each, then reply with exactly one
```clojure fence containing corrected `running-sums` and `logged-sums`
top-level defns (do not redefine `log`). After the fix,
`(running-sums [1 2 3 4])` must be `[1 3 6 10]`, and after
`(logged-sums [1 2 3])` the value of `@log` must be `[1 2 3]`."""

TASKS["t6-puzzle"] = """Five build lanes (A, B, C, D, E) each occupy exactly one 30-minute slot on
one shared machine. Slots run 09:00, 09:30, 10:00, 10:30, 11:00.

Constraints:
1. B runs at some point before D.
2. A runs immediately after C (A's slot starts exactly 30 min after C's).
3. E does not run in the first or last slot.
4. D does not run immediately after B.
5. C does not run at 09:00.
6. D and E run in adjacent slots (30 minutes apart, in either order).
7. E runs after A.

Determine the unique assignment. Then compute the answer value: take the
slot index of each lane (09:00 = 1, 09:30 = 2, 10:00 = 3, 10:30 = 4,
11:00 = 5) and compute (index of A) * 10000 + (index of B) * 1000 +
(index of C) * 100 + (index of D) * 10 + (index of E).

Reply with your reasoning, then a final line of exactly the form
`ANSWER: <integer>`."""

TASKS["t7-longctx"] = """Here is the complete source of the Seon namespace `seon.render.value`:

```clojure
""" + VALUE_CLJC + """```

Question. Suppose `prepare` is called on a unit where:
- `:seon.render.value/route-base` is `"/data/abc"` (present),
- `:seon.render.data/cursor` is `{:seon.render.data/path [] :seon.render.data/offset 0}`,
- `:seon.render/value` is the vector `[:a :b :c]`,
- `:seon.sci.admit/caps` is present and `page-size` for this unit evaluates
  to exactly `1`,
- `:seon.cluster.eval/result-edn` is ABSENT from the unit.

Trace `display-value` -> `opened-window` precisely and state the EXACT value
of each of these five keys in the map `opened-window` returns:
`:seon.render.value/window`, `:seon.render.value/steps`,
`:seon.render.value/shown`, `:seon.render.value/total`,
`:seon.render.value/more?`.

Also answer: does `prepare` end up setting `:seon.render.value/truncated?`
to true or false for this unit?

Format your final answer as exactly these six lines, nothing else after them:
WINDOW: <value>
STEPS: <value>
SHOWN: <value>
TOTAL: <value>
MORE: <value>
TRUNCATED: <value>"""
