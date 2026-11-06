(() => {
  const root = document.documentElement;
  root.dataset.renderQuality = 'static';
  const updateVisibility = () => root.toggleAttribute('data-motion-paused', document.hidden);
  document.addEventListener('visibilitychange', updateVisibility);
  updateVisibility();
  // A missing module, file:// preview or unavailable Canvas keeps a static,
  // readable CSS surface instead of starting an expensive native SVG fallback.
  if (location.protocol === 'file:' || !('ResizeObserver' in window)) return;
  requestAnimationFrame(() => {
    import('./liquid-refraction.js?v=20261106-ocean-photo-v1').then(module => {
      module.createGlassRefraction();
    }).catch(() => { root.dataset.renderQuality = 'static'; });
  });
})();
