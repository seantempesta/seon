// Debug logging for reactive demo
console.log('[reactive-demo] Script loaded');

document.addEventListener('DOMContentLoaded', function() {
  console.log('[reactive-demo] DOM ready');

  setTimeout(function() {
    console.log('[reactive-demo] Datastar loaded?', typeof window.Datastar);
    var main = document.getElementById('reactive-content');
    console.log('[reactive-demo] Main element:', main);
    if (main) {
      console.log('[reactive-demo] data-on-load attr:', main.getAttribute('data-on-load'));
    }
  }, 1000);
});
