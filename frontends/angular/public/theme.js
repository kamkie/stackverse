// Apply the persisted theme before first paint (see spec/design/tokens.css).
(function () {
  try {
    const theme = localStorage.getItem('stackverse.theme');
    if (theme === 'light' || theme === 'dark') {
      document.documentElement.setAttribute('data-theme', theme);
    }
  } catch {
    // Storage unavailable: stay on auto.
  }
})();
