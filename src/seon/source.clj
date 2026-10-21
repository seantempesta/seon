(ns seon.source
  "Source text as the program: a file read form by form with its exact text, and the
  compiler's account of each form taken by analysis, without running it (K1: analysis
  needs the namespace and its macros, nothing else). The same accounts an agent's REPL
  records (seon.program/account), so a namespace imported from a file and one written at a
  REPL are the same facts.

  `read-forms` keeps every character: each form's text, the whitespace and comments before
  it (:lead), and what follows the last form (:tail), so the forms written back in their
  order are the file, byte for byte."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.program :as program] [jolt.analyzer] [jolt.host]))

(defn- line-starts
  "The offset in text where each line starts."
  {:malli/schema [:=> [:cat :string] [:vector :int]]}
  [^String text]
  (loop [acc [0] i 0]
    (let [j (.indexOf text "\n" (int i))]
      (if (neg? j) acc (recur (conj acc (inc j)) (inc j))))))

(defn- lead-end
  "Where the first form starts in text from i to end: past whitespace, commas and ; comments."
  {:malli/schema [:=> [:cat :string :int :int] :int]}
  [^String text i end]
  (loop [i i]
    (if (>= i end)
      end
      (let [c (.charAt text i)]
        (cond (or (Character/isWhitespace c) (= c \,)) (recur (inc i))
              (= c \;) (let [j (.indexOf text "\n" (int i))] (recur (if (or (neg? j) (> j end)) end (inc j))))
              :else i)))))

(defn read-forms
  "text read form by form in namespace nsym (its ns form switches the namespace the rest is
  read in, as loading does): {:forms [{:form :src :lead :ns}] :tail}. Each form's :src is
  its exact text (from where the reader starts it to where the reader stops after it), its
  :lead the text between the previous form and it, and :tail the text after the last."
  {:malli/schema [:=> [:cat :symbol :string] [:map [:forms [:vector :map]] [:tail :string]]]}
  [nsym ^String text]
  (let [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))
        starts (line-starts text)
        off (fn [line col] (+ (starts (dec line)) (dec col)))]
    (loop [acc [] prev 0 nsym nsym]
      (let [form (binding [*ns* (or (find-ns nsym) *ns*)] (read {:eof ::eof} r))]
        (if (= ::eof form)
          {:forms acc :tail (subs text prev)}
          (let [end (off (.getLineNumber r) (.getColumnNumber r))
                start (lead-end text prev end)
                nsym' (if (and (seq? form) (= 'ns (first form)) (symbol? (second form))) (second form) nsym)]
            (recur (conj acc {:form form :src (subs text start end) :lead (subs text prev start) :ns nsym'})
                   end nsym')))))))

(defn- head-var
  "The var form's head names in nsx, or nil."
  {:malli/schema [:=> [:cat :any :seon.host/namespace] [:maybe :seon.host/var]]}
  [form nsx]
  (when (and (seq? form) (symbol? (first form)))
    (let [v (ns-resolve nsx (first form))] (when (var? v) v))))

(defn analyze-form
  "The compiler's account units of form in namespace nsym, analyzed without running it. A
  form that sets up its namespace's environment is run first (an ns form for a namespace
  not loaded yet, a defmacro whose macro is not defined yet), since what follows it is
  analyzed against that environment (K1). Answers {:units [...] :ns-form? b}."
  {:malli/schema [:=> [:cat :symbol :any] :map]}
  [nsym form]
  (let [nsx (program/ensure-ns! nsym)
        hv (head-var form nsx)
        ns-form? (= #'clojure.core/ns hv)
        macro? (= #'clojure.core/defmacro hv)]
    (when (or (and ns-form? (not (find-ns (second form))))
              (and macro? (not (some-> (ns-resolve nsx (second form)) bound?))))
      (binding [*ns* nsx] (eval form)))
    (let [node (binding [*ns* nsx] (jolt.analyzer/analyze (jolt.host/ctx-for-ns (str nsym)) form))]
      {:units [(program/unit-account node)] :ns-form? ns-form?})))

(defn accounts
  "The program accounts of text's forms in namespace nsym, in file order: each form's
  account with its exact text, lead and order (its index), and a form that is none of the
  program's (a comment block, a top-level call) as a text form, so the file is whole.
  Answers {:accounts [...] :tail}."
  {:malli/schema [:=> [:cat :symbol :string] [:map [:accounts [:vector :map]] [:tail :string]]]}
  [nsym text]
  (let [{:keys [forms tail]} (read-forms nsym text)
        seen (volatile! {})]
    {:tail tail
     :accounts (vec (map-indexed
                      (fn [i {:keys [form src lead ns]}]
                        (let [k (get (vswap! seen update [ns src] (fnil inc -1)) [ns src])
                              {:keys [units ns-form?]} (analyze-form (if (and (seq? form) (= 'ns (first form))) nsym ns) form)
                              base {:units units :src src :ns ns :ns-after ns :ns-form? ns-form? :ns-meta (when ns-form? (meta (second form))) :before nil :lead lead :order i :k k}]
                          (or (program/account base)
                              {:id (str ns "/text-" i) :src src :ns ns :ns-form? false :defs [] :declares #{} :methods [] :impls []
                               :refs #{} :requires #{} :banned #{} :inline-defs #{} :lead lead :order i})))
                      forms))}))

(defn text-tx
  "The facts of text's forms as namespace nsym's program on db (seon.program/account-tx per
  form, in file order), with the namespace's file (relative path, or nil) and the text after
  its last form. A seed, an import and a test fixture are this."
  {:malli/schema [:=> [:cat :seon.host/db :symbol :string [:maybe :string]] :seon.db/tx-data]}
  [db nsym text file]
  (let [{:keys [accounts tail]} (accounts nsym text)
        tx (vec (mapcat #(program/account-tx db % (:order %)) accounts))]
    (cond-> tx
      (or file (seq tail)) (conj (cond-> {:db/id (str "ns " nsym) :ns/name nsym}
                                   file (assoc :ns/file file)
                                   (seq tail) (assoc :ns/tail tail))))))
