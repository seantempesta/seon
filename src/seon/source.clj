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
  (:require [seon.contract :refer [defn defn-]] [seon.program :as program] [datahike.api :as d] [clojure.string :as str] [jolt.analyzer] [jolt.host]))

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
  :lead the text between the previous form and it, and :tail the text after the last. With
  step, each form's map is step's answer for it, taken before the next form is read (as
  loading evaluates a form before reading the next), so a form that sets up its namespace's
  aliases is in effect for the forms after it (`::alias/k`)."
  {:malli/schema [:function [:=> [:cat :symbol :string] [:map [:forms [:vector :map]] [:tail :string]]]
                  [:=> [:cat :symbol :string :seon.host/fn] [:map [:forms [:vector :map]] [:tail :string]]]]}
  ([nsym text] (read-forms nsym text identity))
  ([nsym ^String text step]
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
             (recur (conj acc (step {:form form :src (subs text start end) :lead (subs text prev start) :ns nsym'}))
                    end nsym'))))))))

(defn- head-var
  "The var form's head names in nsx, or nil."
  {:malli/schema [:=> [:cat :any :seon.host/namespace] [:maybe :seon.host/var]]}
  [form nsx]
  (when (and (seq? form) (symbol? (first form)))
    (let [v (ns-resolve nsx (first form))] (when (var? v) v))))

(defn- analyze
  "form's analyzed node in namespace nsym, without running it."
  {:malli/schema [:=> [:cat :symbol :any] :map]}
  [nsym form]
  (let [nsx (program/ensure-ns! nsym)]
    (binding [*ns* nsx] (jolt.analyzer/analyze (jolt.host/ctx-for-ns (str nsym)) form))))

(defn analyze-form
  "The compiler's account units of form in namespace nsym, analyzed without running it. A
  form that sets up its namespace's environment is run first (an ns form for a namespace
  not loaded, a defmacro whose macro is not defined yet), since what follows it is
  analyzed against that environment (K1). A namespace is loaded when the process loaded
  its lib (`clojure.core/loaded-libs`), not merely when it exists: the namespace of a file
  the binary never loaded (test/'s) gets its requires and aliases from its own ns form.
  Answers {:units [...] :ns-form? b}."
  {:malli/schema [:=> [:cat :symbol :any] :map]}
  [nsym form]
  (let [nsx (program/ensure-ns! nsym)
        hv (head-var form nsx)
        ns-form? (= #'clojure.core/ns hv)
        macro? (= #'clojure.core/defmacro hv)]
    (when (or (and ns-form? (not (contains? (loaded-libs) (second form))))
              (and macro? (not (some-> (ns-resolve nsx (second form)) bound?))))
      (binding [*ns* nsx] (eval form)))
    {:units [(program/unit-account (analyze nsym form))] :ns-form? ns-form?}))

(defn accounts
  "The program accounts of text's forms in namespace nsym, in file order: each form's
  account with its exact text, lead and order (its index), and a form that is none of the
  program's (a comment block, a top-level call) as a text form, so the file is whole.
  Answers {:accounts [...] :tail}."
  {:malli/schema [:=> [:cat :symbol :string] [:map [:accounts [:vector :map]] [:tail :string]]]}
  [nsym text]
  (let [{:keys [forms tail]} (read-forms nsym text
                                         (fn [{:keys [form ns] :as f}]
                                           (merge f (analyze-form (if (and (seq? form) (= 'ns (first form))) nsym ns) form))))
        seen (volatile! {})]
    {:tail tail
     :accounts (vec (map-indexed
                      (fn [i {:keys [form src lead ns units ns-form?]}]
                        (let [k (get (vswap! seen update [ns src] (fnil inc -1)) [ns src])
                              base {:units units :src src :ns ns :ns-after ns :ns-form? ns-form? :ns-meta (when ns-form? (meta (second form))) :before nil :lead lead :order i :k k}]
                          (or (program/account base)
                              {:id (str ns "/text-" i) :src src :ns ns :ns-form? false :defs [] :declares #{} :methods [] :impls []
                               :refs #{} :requires #{} :banned #{} :inline-defs #{} :timeouts #{} :catches #{} :lead lead :order i})))
                      forms))}))

(defn text-tx
  "The facts of text's forms as namespace nsym's program on db (seon.program/account-tx per
  form, in file order), with the namespace's file (relative path, or nil) and the text after
  its last form. A seed, an import and a test fixture are this."
  {:malli/schema [:=> [:cat :seon.host/db :symbol :string [:maybe :string]] :seon.db/tx-data]}
  [db nsym text file]
  (let [{:keys [accounts tail]} (accounts nsym text)
        tx (vec (concat (mapcat #(program/account-tx db % (:order %)) accounts)
                        (when file (for [a accounts] {:db/id (str "form " (:id a)) :form/source true}))))]
    (cond-> tx
      ;; a file's tail is kept even when empty: no newline at its end is part of the file
      (or file (seq tail)) (conj (cond-> {:db/id (str "ns " nsym) :ns/name nsym :ns/tail tail}
                                   file (assoc :ns/file file))))))

(defn file-ns
  "The namespace a file's text declares: the name of its first form when that is an ns
  form, or nil."
  {:malli/schema [:=> [:cat :string] [:maybe :symbol]]}
  [text]
  (let [form (binding [*ns* (the-ns 'user)] (read {:eof nil} (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))))]
    (when (and (seq? form) (= 'ns (first form)) (symbol? (second form))) (second form))))

(defn- file-requires
  "The namespaces a file's ns form requires, from the compiler's analysis of it (not run)."
  {:malli/schema [:=> [:cat :symbol :string] [:set :symbol]]}
  [nsym text]
  (let [form (binding [*ns* (the-ns 'user)] (read {:eof nil} (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))))]
    (:requires (program/unit-account (analyze nsym form)))))

(defn import-tx
  "The facts of files ({relative path text}, each a namespace's file) as program on db: each
  file's forms with their exact text and the compiler's account (text-tx), its namespace
  carrying the file. Files are analyzed in the order loading them would take, each after
  the files it requires (ties by path), so a namespace the process never loaded is set up
  before a file that requires it. A file with no ns form is no namespace's and is left out."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string :string]] :seon.db/tx-data]}
  [db files]
  (let [by-ns (into {} (for [[path text] files :let [nsym (file-ns text)] :when nsym] [nsym [path text]]))
        reqs (into {} (for [[nsym [_ text]] by-ns] [nsym (file-requires nsym text)]))]
    (vec (for [nsym (program/topo (keys by-ns) #(reqs %) #(first (by-ns %)))
               :let [[path text] (by-ns nsym)]
               op (text-tx db nsym text path)]
           op))))

(defn source-nss
  "The namespaces on db imported from a file (they carry :ns/file), sorted."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :symbol]]}
  [db] (vec (sort (d/q '[:find [?n ...] :where [?e :ns/file] [?e :ns/name ?n]] db))))

(defn file-text
  "Namespace nsym's file as db holds it: its live forms in their order, each after its
  lead (a form recorded at a REPL has none: a blank line, or nothing before the first),
  then the text after its last form (a newline when it has none)."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] :string]}
  [db nsym]
  (let [ne (:e (first (d/datoms db :avet :ns/name nsym)))
        fs (sort-by :order (for [dt (d/datoms db :avet :form/ns ne) :when (program/live? db (:e dt))] (program/form-row db (:e dt))))]
    (str (apply str (map-indexed (fn [i f] (str (or (:lead f) (if (zero? i) "" "\n\n")) (:src f))) fs))
         (or (:ns/tail (d/entity db ne)) "\n"))))

(defn files
  "{relative path text} of the namespaces nss that db holds a file for (file-text)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :symbol]] [:map-of :string :string]]}
  [db nss]
  (into (sorted-map) (for [n nss :let [p (:ns/file (d/entity db [:ns/name n]))] :when p] [p (file-text db n)])))

(defn changed-nss
  "The namespaces whose forms a merge's changes (seon.merge/three-way's :ours) add,
  change or remove, sorted."
  {:malli/schema [:=> [:cat :seon.merge/changes] [:vector :symbol]]}
  [changes]
  (vec (sort (distinct (for [[[k] [a b]] changes :when (= :form k)] (:ns (or b a)))))))

(defn commit-message
  "The merges commit's message, from the facts of what merged: each member's task and
  attempt."
  {:malli/schema [:=> [:cat [:sequential [:map [:task :any] [:attempt :string]]]] :string]}
  [members]
  (str "Seon merge: " (str/join ", " (map (comp str :task) members)) "\n\n"
       (str/join "\n" (for [{:keys [task attempt]} members] (str "attempt " attempt " (task " task ")"))) "\n"))
