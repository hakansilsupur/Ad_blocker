/**
 * Cosmetic filtering: hide what the network layer left behind.
 *
 * Runs at document_start so the CSS lands before the first paint. Because the
 * "is this site allowlisted?" answer is async, the rules go in first and are
 * pulled back out if the site turns out to be exempt -- a hidden ad slot that
 * reappears is harmless, an ad that flashes on every load is not.
 */

(() => {
  const filters = globalThis.COSMETIC_FILTERS;
  if (!filters) return;

  const STYLE_ID = 'adblock-cosmetic-filters';
  const site = location.hostname.replace(/^www\./, '');

  /** Per-site rules apply to the domain and its subdomains. */
  function selectorsForSite() {
    const selectors = [...filters.generic];
    for (const [domain, rules] of Object.entries(filters.perSite)) {
      if (site === domain || site.endsWith(`.${domain}`)) selectors.push(...rules);
    }
    return selectors;
  }

  const selectors = selectorsForSite();

  function injectStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = `${selectors.join(',')}{display:none !important;}`;
    // documentElement exists at document_start; head usually does not yet.
    (document.head || document.documentElement).appendChild(style);
  }

  function removeStyle() {
    document.getElementById(STYLE_ID)?.remove();
  }

  injectStyle();

  let hiddenCount = 0;
  let reportTimer = null;

  function reportSoon() {
    if (reportTimer) return;
    reportTimer = setTimeout(() => {
      reportTimer = null;
      if (hiddenCount === 0) return;
      const count = hiddenCount;
      hiddenCount = 0;
      chrome.runtime.sendMessage({ type: 'cosmetic-hidden', count }).catch(() => {});
    }, 1000);
  }

  /**
   * Collapse frames and images pointing at ad hosts. The request itself is
   * already blocked; this removes the reserved space it would still occupy.
   */
  function collapseAdElements(root) {
    const nodes = root.querySelectorAll?.('iframe[src], img[src], embed[src], object[data]') ?? [];
    for (const node of nodes) {
      if (node.dataset.adblockCollapsed) continue;
      const source = node.src || node.data || '';
      if (!source) continue;
      if (filters.adSourceFragments.some((fragment) => source.includes(fragment))) {
        node.dataset.adblockCollapsed = '1';
        node.style.setProperty('display', 'none', 'important');
        hiddenCount += 1;
      }
    }
  }

  function countHidden(root) {
    for (const selector of selectors) {
      let matches;
      try {
        matches = root.querySelectorAll?.(selector) ?? [];
      } catch {
        continue; // a malformed selector must not take the whole pass down
      }
      for (const node of matches) {
        if (node.dataset && !node.dataset.adblockCounted) {
          node.dataset.adblockCounted = '1';
          hiddenCount += 1;
        }
      }
    }
  }

  let scanScheduled = false;
  function scheduleScan() {
    if (scanScheduled) return;
    scanScheduled = true;
    const run = () => {
      scanScheduled = false;
      countHidden(document);
      collapseAdElements(document);
      reportSoon();
    };
    // Coalesce bursts of mutations from a single render into one pass. A
    // background tab never paints, so rAF would never fire there.
    if (document.hidden) setTimeout(run, 250);
    else requestAnimationFrame(run);
  }

  const observer = new MutationObserver(scheduleScan);

  function start() {
    observer.observe(document.documentElement, { childList: true, subtree: true });
    scheduleScan();
  }

  if (document.documentElement) {
    start();
  } else {
    document.addEventListener('readystatechange', start, { once: true });
  }

  // Stand down if the user has switched the blocker off for this site.
  chrome.runtime
    .sendMessage({ type: 'is-active-for', site })
    .then((response) => {
      if (response && response.active === false) {
        removeStyle();
        observer.disconnect();
      }
    })
    .catch(() => { /* worker asleep or extension reloading: keep filtering */ });
})();
