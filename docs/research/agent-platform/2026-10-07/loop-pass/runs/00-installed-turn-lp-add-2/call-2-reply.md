I'll work on my task in the correct namespace. Let me check the current state of `scratch.add2/add-one`: 

```clojure
(in-ns 'scratch.add2)
```

Let me look at the current definition and the test:

```clojure
scratch.add2=> (dir 'scratch.add2)
```

Actually, let me just check what's already there and fix the function:

```clojure
scratch.add2=> (clojure.repl/source add-one)
```

Based on my earlier work, I accidentally defined `add-one` in the `user` namespace instead of `scratch.add2`. The task requires `scratch.add2/add-one` to return `x + 1`. Let me fix it in the right namespace:

```clojure
scratch.add2=> (defn add-one "Returns x plus one." {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))
```

Then verify the proof test passes:

```clojure
scratch.add2=> (clojure.test/run-test-var #'add-one-adds-one)
```

If for some reason the var isn't resolvable directly, use:

```clojure
scratch.add2=> (clojure.test/test-vars [#'add-one-adds-one])
```