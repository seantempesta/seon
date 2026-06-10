(ns seon.instrument
  "Phase A item 7 — collect + install Malli instrumentation for every
   seon.* fn with `:malli/schema` metadata. (T15 positional read ops.)

   Why this exists: `malli.instrument/collect!` is JVM-only — it reads
   source files at JVM build time. CLJS has no built-in equivalent;
   `:malli/schema` metadata does NOT auto-populate
   `malli.core/-function-schemas*` at namespace load. So at boot,
   `malli.instrument/instrument!` finds no schemas and instruments
   nothing.

   The seon-native fix: a compile-time macro that reads the CLJS
   analyzer's view of every loaded namespace via
   `cljs.analyzer.api/all-ns` + `ns-publics`, filters to `seon.*`,
   walks each ns's defs, and for every def whose metadata carries
   `:malli/schema` emits one
   `(malli.core/-register-function-schema! ns name schema {})` call.
   The macro expands to a flat `(do …)` of registration calls —
   evaluated at runtime, populates the atom that `instrument!` reads.

   The runtime `install!` fn calls the macro then
   `malli.instrument/instrument!`. Idempotent — re-registering is a
   no-op (same key, last-write-wins, same value).

   Per CLAUDE.md: we don't have to use packages the way the original
   author intended. The end goal is `:malli/schema`-annotated fns get
   their inputs+outputs validated at runtime; the original `collect!`
   path is JVM-only; we ship the CLJS path that achieves the same end."
  #?(:cljs (:require-macros [seon.instrument :refer [collect!]]))
  (:require
    #?@(:clj  [[cljs.analyzer.api :as ana]
               [clojure.java.io :as io]
               [clojure.string :as str]]
        :cljs [[malli.core :as m]
               [malli.instrument :as mi]
               [seon.error.instrument :as ei]])))

#?(:clj
   (defn- first-party-file?
     "STRUCTURAL first/third-party boundary (V3-C, 2026-06-10 — same
      rule as `seon.indexing/first-party-file?`): a def is FIRST-PARTY
      iff its analyzer `:file` resolves to a file under the repo root
      (the macroexpanding JVM's working dir). Jars (`jar:` URLs) and
      gitlibs checkouts (file URLs outside the root) are third-party.
      Replaces the two name-prefix `collect!` calls (\"seon\" +
      \"my.\")."
     [file]
     (boolean
       (when (and file (string? file))
         (let [root (System/getProperty "user.dir")]
           (if (str/starts-with? file "/")
             (str/starts-with? file root)
             (when-let [url (io/resource file)]
               (and (= "file" (.getProtocol url))
                    (str/starts-with? (.getPath url) root)))))))))

#?(:clj
   (defn- collect-registrations
     "Compile-time scan: walk every FIRST-PARTY CLJS namespace
      ([[first-party-file?]] over each def's analyzer `:file` — the
      structural boundary, not a name prefix), find every def whose
      metadata carries `:malli/schema`, and return a vector of
      [ns-sym fn-sym schema-form] triples. Run at macroexpand time.

      `^:async` fns are skipped — their `:malli/schema` describes the
      RESOLVED value, but the wrapped fn returns a Promise. Instrumenting
      them would trip `:malli.core/invalid-output` on every call.
      Validating Promise-returning fns requires `await`-then-validate
      which `mi/instrument!` doesn't do. Two viable cleanups (both
      deferred): (1) a custom async-aware wrapper that awaits before
      output validation, (2) a per-fn opt-out marker. For v1 we just
      skip — input validation is the bigger win, and async fns mostly
      delegate to other (synchronously instrumented) fns anyway."
     []
     (vec
       (for [ns-sym  (ana/all-ns)
             [fn-sym ana-info] (ana/ns-publics ns-sym)
             :let    [meta-map (:meta ana-info)
                      schema   (:malli/schema meta-map)
                      async?   (boolean (:async meta-map))
                      file     (or (:file ana-info) (:file meta-map))]
             :when   (and schema (not async?)
                          (first-party-file? file))]
         [ns-sym fn-sym schema]))))

#?(:clj
   (defmacro collect!
     "Expand at compile time to a `(do …)` of
      `(malli.core/-register-function-schema! 'ns 'name <schema> {})`
      calls — one per discovered `:malli/schema`-annotated fn in a
      FIRST-PARTY namespace (structural boundary — every def whose
      source file lives under the repo root; see
      [[first-party-file?]]).

      Re-runs on every CLJS rebuild — picks up new fns as they're
      added. Returns the registration count.

      Example expansion (one of several inner forms):
        (malli.core/-register-function-schema!
          'seon.db 'transact!
          [:=> [:cat ::transact-request] ::transact-response]
          {})"
     []
     (let [registrations (collect-registrations)]
        ;; The 6-arity passes `:cljs` (the runtime key) + `identity`
        ;; (the transformer). This mirrors the canonical `m/=>` macro
        ;; expansion for CLJS (malli/core.cljc:3106-3107). The 4-arity
        ;; defaults `:clj` + `function-schema`; `mi/instrument!` reads
        ;; `(m/function-schemas :cljs)` by default (instrument.cljs:98),
        ;; so 4-arity registrations are silently invisible in CLJS, AND
        ;; `function-schema` requires the runtime schema env that isn't
        ;; safe at registration time per malli's own comment at
        ;; core.cljc:3080. `identity` defers schema-validation to
        ;; instrument! time — same pattern malli's own `=>` uses.
        `(do
           ~@(for [[ns-sym fn-sym schema] registrations]
               `(malli.core/-register-function-schema!
                  '~ns-sym '~fn-sym ~schema {}
                  :cljs identity))
           ~(count registrations)))))

#?(:cljs
   (defn install!
     "Boot-time call. Runs the compile-time-expanded `collect!` to
      populate `malli.core/-function-schemas*`, then calls
      `malli.instrument/instrument!` to wrap every registered fn
      with input+output validation per Sean's decision #7
      (instrumentation validates BOTH inputs and outputs on all
      public fns).

      Returns
        {:seon.instrument/n-registered <int>
         :seon.instrument/n-instrumented <int>}.

      Idempotent: re-calling re-registers (no-op; same keys + values)
      and re-instruments (replaces the wrapper)."
     []
     ;; ONE structural collect (V3-C): every first-party def — the
     ;; substrate plus the compiled my.* scaffold — by source-file
     ;; location, no name prefixes. Runtime-authored my.* fns aren't on
     ;; the compile-time roster (the analyzer can't see them); they
     ;; validate through the eval path instead.
     (let [n-reg (collect!)]
       ;; `mi/instrument!` returns nil (not a count) — it mutates each
       ;; registered fn's var-binding in place to install the validating
       ;; wrapper. n-registered == n-instrumented by construction
       ;; since instrument! reads the same atom collect! just populated.
       ;;
       ;; Reporter — Phase A item 8. Default reporter is `m/-fail!`
       ;; (throws a generic ex-info); we hand in `ei/report-fn` so
       ;; failures throw with the structured envelope as ex-data,
       ;; which seon.error/->map flattens into :seon.error/data and
       ;; record-eval! persists as :seon.eval/error-data.
       (mi/instrument! {:report ei/report-fn})
       {:seon.instrument/n-registered   n-reg
        :seon.instrument/n-instrumented n-reg})))
