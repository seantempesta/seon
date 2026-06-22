(ns acme.brand
  "Acme branding copy the agent can read — a SECOND downstream ns, here to
   prove more than one acme ns indexes and shows in context. The VISUAL
   theme is controlled separately and without any seon-src change via the
   existing seams bin/acme exports: SEON_BRAND_NAME / SEON_BRAND_TAGLINE
   (the product name + tagline rows) and SEON_BRAND_CSS (the stylesheet at
   acme/branding/acme.css, inlined after seon's output.css so its token
   overrides win).")

(defn tagline
  "Acme's product tagline — specced so this ns is indexed + shown."
  {:malli/schema [:=> [:cat] :string]}
  []
  "Acme — the third-party harness.")
