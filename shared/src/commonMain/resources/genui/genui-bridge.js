(() => {
  const root = document.getElementById('genui-root');
  let dispose = null;
  let activeRenderId = 0;
  let lastPayloadJson = null;
  let lastTheme = null;
  let isInteractive = false;
  let lastReportedHeight = 0;
  let resizeTimer = 0;
  let failed = false;
  let observer = null;

  const post = (name, value, renderId = activeRenderId) => {
    const text = String(value ?? '').slice(0, 20000);
    const id = Number.isInteger(renderId) ? renderId : 0;
    const ios = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.genui;
    if (ios) ios.postMessage({ name, value: text, renderId: id });
    else if (window.AndroidGenui && typeof window.AndroidGenui.post === 'function') {
      window.AndroidGenui.post(name, text, id);
    }
  };

  window.GenuiNative = {
    action(action, payload) {
      if (!isInteractive || failed || activeRenderId <= 0) return;
      try {
        const safePayload = payload && typeof payload === 'object' && !Array.isArray(payload) ? payload : {};
        post('action', JSON.stringify({
          version: 1,
          action: String(action),
          payload: safePayload
        }), activeRenderId);
      } catch (_) {
        post('error', 'GenUI action could not be encoded', activeRenderId);
      }
    }
  };

  const setInteractive = (interactive) => {
    const normalized = interactive !== false;
    isInteractive = normalized;
    root.inert = !normalized;
    root.dataset.interactive = normalized ? 'true' : 'false';
  };

  const setTheme = (theme) => {
    const normalized = theme === 'dark' ? 'dark' : 'light';
    const changed = lastTheme !== normalized;
    lastTheme = normalized;
    root.dataset.theme = normalized;
    document.documentElement.dataset.theme = normalized;
    document.body.toggleAttribute('data-ds-dark-theme', normalized === 'dark');
    return changed;
  };

  const measure = (force = false) => {
    if (failed || activeRenderId <= 0) return;
    const height = Math.max(1, Math.ceil(Math.max(root.scrollHeight, root.getBoundingClientRect().height)));
    if (!Number.isFinite(height) || height > 20000) {
      fail('GenUI content exceeds the supported height', activeRenderId);
      return;
    }
    if (!force && Math.abs(height - lastReportedHeight) < 1) return;
    lastReportedHeight = height;
    post('resize', height, activeRenderId);
  };

  const scheduleMeasure = (force = false) => {
    if (failed || activeRenderId <= 0) return;
    const expectedRenderId = activeRenderId;
    window.clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(() => {
      if (expectedRenderId === activeRenderId && !failed) measure(force);
    }, 64);
  };

  const fail = (message, renderId = activeRenderId) => {
    if (failed || (renderId !== activeRenderId && activeRenderId > 0)) return;
    failed = true;
    lastPayloadJson = null;
    lastReportedHeight = 0;
    window.clearTimeout(resizeTimer);
    if (observer) observer.disconnect();
    if (dispose) {
      try { dispose(); } catch (_) {}
    }
    dispose = null;
    root.replaceChildren();
    post('error', String(message || 'GenUI render failed').slice(0, 500), renderId);
  };

  const observeErrors = (renderId) => {
    if (observer) observer.disconnect();
    observer = new MutationObserver(() => {
      if (renderId !== activeRenderId || failed) return;
      const alert = root.querySelector('[data-genui-error]');
      if (alert) fail(alert.textContent || 'GenUI render failed', renderId);
    });
    observer.observe(root, { childList: true, subtree: true });
  };

  window.setGenuiTheme = (theme) => {
    if (setTheme(theme) && dispose !== null && !failed) {
      scheduleMeasure(false);
    }
  };

  window.setGenuiInteractive = (interactive) => {
    setInteractive(interactive);
  };

  window.setGenuiFontScale = (scale) => {
    const finite = Number(scale);
    const normalized = Number.isFinite(finite) ? Math.min(2, Math.max(0.8, finite)) : 1;
    document.documentElement.style.setProperty('--genui-font-scale', String(normalized));
    document.documentElement.style.setProperty('--dsh-content-font-size', (17 * normalized).toFixed(2) + 'px');
    if (dispose !== null && !failed) {
      requestAnimationFrame(() => scheduleMeasure(false));
    }
  };

  window.renderGenui = (payloadJson, theme, renderId, interactive = true) => {
    const payloadStr = String(payloadJson);
    const nextRenderId = Number.isInteger(renderId) && renderId > 0 ? renderId : activeRenderId + 1;
    if (nextRenderId < activeRenderId) return false;
    if (dispose !== null && !failed && lastPayloadJson === payloadStr) {
      const renderIdChanged = activeRenderId !== nextRenderId;
      activeRenderId = nextRenderId;
      setTheme(theme);
      setInteractive(interactive);
      if (renderIdChanged) scheduleMeasure(true);
      return true;
    }

    activeRenderId = nextRenderId;
    failed = false;
    lastPayloadJson = null;
    lastReportedHeight = 0;
    window.clearTimeout(resizeTimer);
    if (observer) observer.disconnect();
    if (dispose) {
      try { dispose(); } catch (_) {}
    }
    dispose = null;
    root.replaceChildren();
    setTheme(theme);
    setInteractive(interactive);
    try {
      if (!window.__GenuiStandalone__ || typeof window.__GenuiStandalone__.mount !== 'function') {
        throw new Error('GenUI runtime is unavailable');
      }
      const artifact = JSON.parse(payloadStr);
      if (!artifact || typeof artifact !== 'object' || Array.isArray(artifact)) {
        throw new Error('Invalid GenUI artifact');
      }
      artifact.presentation = Object.assign({}, artifact.presentation || {}, {
        locale: artifact.presentation && artifact.presentation.locale === 'zh' ? 'zh' : 'en',
        theme: theme === 'dark' ? 'dark' : 'light'
      });
      dispose = window.__GenuiStandalone__.mount(root, artifact);
      lastPayloadJson = payloadStr;
      observeErrors(nextRenderId);
      requestAnimationFrame(() => {
        if (nextRenderId === activeRenderId && !failed) scheduleMeasure(true);
      });
      return true;
    } catch (error) {
      fail(error && error.message ? error.message : 'GenUI render failed', nextRenderId);
      return false;
    }
  };

  if ('ResizeObserver' in window) {
    const sizeObserver = new ResizeObserver(() => scheduleMeasure(false));
    sizeObserver.observe(root);
  }
  window.addEventListener('error', (event) => {
    if (event.target && event.target.tagName === 'SCRIPT') {
      fail('GenUI runtime resource failed to load', activeRenderId);
    }
  }, true);
  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason;
    fail(reason && reason.message ? reason.message : 'GenUI render failed', activeRenderId);
  });
})();
