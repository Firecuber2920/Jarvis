// Scroll-reveal fallback for the ring-panel "stops".
//
// Primary path: CSS `animation-timeline: view()` (scroll-timeline) in styles.css,
// which has no JS dependency and works natively in Chromium.
//
// Fallback path (this file): browsers without scroll-timeline support (Safari,
// Firefox stable, per the design doc) get an IntersectionObserver that adds
// `.is-visible` to each `.stop` as it enters the viewport, triggering the same
// fade/slide-in CSS transition -- same end state, less fluid than the native
// scroll-linked scrub.
//
// If prefers-reduced-motion is set, CSS already renders every stop statically
// (see the @media (prefers-reduced-motion: reduce) rule), so this script does
// not need to special-case it -- adding .is-visible is harmless either way.

(function () {
  var supportsScrollTimeline =
    window.CSS && CSS.supports && CSS.supports('animation-timeline', 'view()');

  if (supportsScrollTimeline) {
    // Native path handles everything via CSS. Nothing for JS to do.
    return;
  }

  var stops = document.querySelectorAll('.stop');
  if (!stops.length || !('IntersectionObserver' in window)) {
    // No IntersectionObserver support either (very old browser): show
    // everything immediately rather than leaving panels invisible forever.
    stops.forEach(function (el) { el.classList.add('is-visible'); });
    return;
  }

  var observer = new IntersectionObserver(
    function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
          observer.unobserve(entry.target);
        }
      });
    },
    { threshold: 0.3 }
  );

  stops.forEach(function (el) { observer.observe(el); });
})();
