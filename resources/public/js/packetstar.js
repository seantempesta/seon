// packetstar.js — the whole browser client for Seon tiles.
//
// Two jobs, no framework:
//   1. Every element with [data-tile] opens its OWN EventSource to the URL in
//      that attribute and replaces its innerHTML with each streamed message.
//      One stream per tile → tiles are independent: one dead stream is one dead
//      tile (auto-retried), never a black screen, and a tile updating never
//      disturbs a sibling.
//   2. Any element with [data-action] POSTs that URL on click. The re-render
//      arrives over the tile's SSE stream, so the response body is ignored.
//
// This replaces datastar for the tile surface. Views are plain hiccup→HTML;
// interactivity is a bare URL. The server holds all the logic.
(function () {
  "use strict";

  var RETRY_MIN = 500;
  var RETRY_MAX = 8000;

  function openTile(el) {
    var url = el.getAttribute("data-tile");
    if (!url) return;
    var backoff = RETRY_MIN;

    function connect() {
      var es = new EventSource(url);

      es.onopen = function () { backoff = RETRY_MIN; };

      es.onmessage = function (e) {
        // One stream per tile, so the target is implicit — the payload is just
        // this tile's inner HTML.
        el.innerHTML = e.data;
      };

      es.onerror = function () {
        es.close();
        // Independent retry with capped backoff — the rest of the wall keeps
        // streaming while this one reconnects.
        setTimeout(connect, backoff);
        backoff = Math.min(backoff * 2, RETRY_MAX);
      };
    }

    connect();
  }

  function openAll(root) {
    (root || document).querySelectorAll("[data-tile]").forEach(openTile);
  }

  // Interactions: a bare data-action URL is POSTed; the tile re-renders over SSE.
  document.addEventListener("click", function (ev) {
    var t = ev.target.closest("[data-action]");
    if (!t) return;
    ev.preventDefault();
    fetch(t.getAttribute("data-action"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}"
    }).catch(function () { /* the SSE stream is the source of truth */ });
  });

  // Input tile (the REPL prompt): a [data-send] form POSTs its text on submit.
  // A value starting with "(" is a Clojure FORM → /eval (introspective, quiet);
  // anything else is prose → /chat (a message that wakes the agent). The input is
  // never server-overwritten, so focus/typing survive live updates around it.
  document.addEventListener("submit", function (ev) {
    var form = ev.target.closest("[data-send]");
    if (!form) return;
    ev.preventDefault();
    var input = form.querySelector("[name=text]");
    if (!input) return;
    var text = input.value;
    if (!text.trim()) return;
    var agent = encodeURIComponent(form.getAttribute("data-agent") || "");
    var isForm = text.trim().charAt(0) === "(";
    var url = (isForm ? "/eval?agent=" : "/chat?agent=") + agent;
    var field = isForm ? "form" : "text";
    fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: field + "=" + encodeURIComponent(text)
    }).catch(function () { /* the tiles reflect the result over SSE */ });
    input.value = "";
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () { openAll(); });
  } else {
    openAll();
  }

  // Expose for tiles added after first paint (a future composer/app-loader).
  window.packetstar = { openAll: openAll, openTile: openTile };
})();
