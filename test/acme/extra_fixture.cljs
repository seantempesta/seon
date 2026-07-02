(ns acme.extra-fixture
  "COMMITTED test fixture for the SEON_EXTRA_SRC extra-core path
   (task #36). Stands in for a DOWNSTREAM consumer's namespace — \"acme\"
   is the designated example downstream name. Lives under seon's own
   `test/` root so the suite needs no env var or external checkout: the
   extra-core tests register [[echo-greeting]] into
   `seon.client/!extra-core-vars` by hand, exactly what a real
   downstream entry ns does with `(seon.indexing/public-fn-vars)`.

   Deliberately NOT in seon.client's require closure, so it is never in
   `core-vars` — it is only ever an EXTRA var.")

(defn echo-greeting
  "Echo a greeting — the one specced fn the extra-core tests index."
  {:malli/schema [:=> [:cat :string] :string]}
  [who]
  (str "hello, " who))
