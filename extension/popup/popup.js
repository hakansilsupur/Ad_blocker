/** Popup UI: current state, counts, and the two switches. */

const els = {
  pill: document.getElementById('state-pill'),
  tabBlocked: document.getElementById('tab-blocked'),
  totalBlocked: document.getElementById('total-blocked'),
  toggleEnabled: document.getElementById('toggle-enabled'),
  toggleSite: document.getElementById('toggle-site'),
  siteName: document.getElementById('site-name'),
  reload: document.getElementById('reload'),
  reset: document.getElementById('reset'),
};

let activeTab = null;
let site = null;

function siteFromUrl(url) {
  try {
    const { hostname, protocol } = new URL(url);
    if (protocol !== 'http:' && protocol !== 'https:') return null;
    return hostname.replace(/^www\./, '');
  } catch {
    return null;
  }
}

/**
 * onRuleMatchedDebug only fires for unpacked builds, so ask the matched-rules
 * API too and show whichever count is higher.
 */
async function matchedRuleCount(tabId) {
  try {
    const { rulesMatchedInfo } = await chrome.declarativeNetRequest.getMatchedRules({ tabId });
    return rulesMatchedInfo?.length ?? 0;
  } catch {
    return 0;
  }
}

function render(status) {
  const enabled = status.enabled;
  els.pill.textContent = enabled ? (status.siteAllowed ? 'paused here' : 'on') : 'off';
  els.pill.classList.toggle('off', !enabled || status.siteAllowed);
  els.toggleEnabled.checked = enabled;
  els.toggleSite.checked = status.siteAllowed;
  els.toggleSite.disabled = !site || !enabled;
  els.siteName.textContent = site || 'this site';
  els.tabBlocked.textContent = status.tabBlocked.toLocaleString();
  els.totalBlocked.textContent = status.totalBlocked.toLocaleString();
}

async function refresh() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  activeTab = tab;
  site = tab ? siteFromUrl(tab.url || '') : null;

  const status = await chrome.runtime.sendMessage({
    type: 'get-status',
    site,
    tabId: tab?.id,
  });
  if (!status || status.error) return;

  if (tab?.id !== undefined) {
    status.tabBlocked = Math.max(status.tabBlocked, await matchedRuleCount(tab.id));
  }
  render(status);
}

els.toggleEnabled.addEventListener('change', async () => {
  await chrome.runtime.sendMessage({ type: 'toggle-enabled' });
  await refresh();
});

els.toggleSite.addEventListener('change', async () => {
  if (!site) return;
  await chrome.runtime.sendMessage({ type: 'toggle-site', site });
  await refresh();
});

els.reload.addEventListener('click', async () => {
  if (activeTab?.id !== undefined) await chrome.tabs.reload(activeTab.id);
  window.close();
});

els.reset.addEventListener('click', async () => {
  await chrome.runtime.sendMessage({ type: 'reset-counter' });
  await refresh();
});

refresh();
