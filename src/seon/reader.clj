(ns seon.reader
  "Reading source as data, for analysis (edamame): the program's definitions as the loader
  and the reach walk see them, and a task's references. Evaluation reads with Jolt's own
  reader (seon.repl); this reader never evaluates anything: tagged literals, `::` keywords
  and reader conditionals all read as plain data."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [edamame.core :as e]))

(defn opts
  "edamame's options for reading source written in namespace nsym: every Clojure syntax
  (`#()`, regex, var, syntax-quote, `#=` as data), `::kw` resolved against nsym (an alias
  resolves to itself), any tag read as its form, and the :clj branch of a reader
  conditional."
  {:malli/schema [:=> [:cat :symbol] :map]}
  [nsym]
  {:all true :read-eval false :read-cond :allow :features #{:clj}
   :auto-resolve (fn [a] (if (= :current a) nsym a))
   :readers (fn [_] identity)})

(defn read-all
  "Every form of text, written in namespace nsym, in order."
  {:malli/schema [:=> [:cat :string :symbol] [:vector :any]]}
  [text nsym]
  (e/parse-string-all text (opts nsym)))

(defn read-text
  "Every form of text written in namespace nsym, or nil when text does not read as Clojure
  (prose, an unbalanced span). The text is a model's or a plan's, so not reading is an
  answer, not a failure; nothing is evaluated (no #=), so whatever the read throws means
  that. edamame 1.4.32 on Jolt throws a bare IllegalArgumentException for a meta prefix at
  the end (\"a^\"), not its :edamame/error (docs/DRAGONS.md)."
  {:malli/schema [:=> [:cat :string :symbol] [:maybe [:vector :any]]]}
  [text nsym]
  (try (read-all text nsym)
       (catch Throwable _ nil)))

(defn read-one
  "The first form of text, written in namespace nsym (nil when it has none)."
  {:malli/schema [:=> [:cat :string :symbol] :any]}
  [text nsym]
  (e/parse-string text (opts nsym)))
