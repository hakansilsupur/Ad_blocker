/**
 * Service worker: owns the on/off state, the per-site allowlist, and the
 * counters the popup shows.
 *
 * Network blocking itself is done by declarativeNetRequest from the generated
 * static ruleset, so no request ever passes through JavaScript here -- the
 * worker only steers which rules are enabled.
 */

const RULESET_ID = 'ads';

// Dynamic allow rules live well above the static rule IDs so the two
// namespaces stay readable when debugging with chrome://extensions.
const ALLOW_RULE_ID_BASE = 1000000;

const ALL_RESOURCE_TYPES = [
  'main_frame', 'sub_frame', 'stylesheet', 'script', 'image', 'font',
  'object', 'xmlhttprequest', 'ping', 'csp_report', 'media', 'websocket',
  'webtransport', 'webbundle', 'other',
];

const DEFAULT_STATE = {
  enabled: true,
  allowedSites: [],
  totalBlocked: 0,
};

/** Per-tab counts, rebuilt on navigation; not worth persisting. */
const tabCounts = new Map();

let saveTimer = null;
let cachedState = null;

async function getState() {
  if (cachedState) return cachedState;
  const stored = await chrome.storage.local.get(DEFAULT_STATE);
  cachedState = { ...DEFAULT_STATE, ...stored };
  return cachedState;
}

async function setState(patch) {
  const state = await getState();
  cachedState = { ...state, ...patch };
  await chrome.storage.local.set(cachedState);
  return cachedState;
}

/** Persist the lifetime counter at most once every few seconds. */
function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(async () => {
    saveTimer = null;
    if (cachedState) await chrome.storage.local.set({ totalBlocked: cachedState.totalBlocked });
  }, 5000);
}

/**
 * Rebuild the dynamic allow rules so they match the stored allowlist exactly.
 * One rule per site: everything initiated by that page is exempt.
 */
async function syncAllowRules() {
  const { allowedSites } = await getState();
  const existing = await chrome.declarativeNetRequest.getDynamicRules();
  const removeRuleIds = existing.map((rule) => rule.id);

  const addRules = allowedSites.map((site, index) => ({
    id: ALLOW_RULE_ID_BASE + index,
    priority: 100, // must outrank the static block rules (priority 1)
    action: { type: 'allow' },
    condition: {
      initiatorDomains: [site],
      resourceTypes: ALL_RESOURCE_TYPES,
    },
  }));

  await chrome.declarativeNetRequest.updateDynamicRules({ removeRuleIds, addRules });
}

async function applyEnabledState() {
  const { enabled } = await getState();
  await chrome.declarativeNetRequest.updateEnabledRulesets(
    enabled
      ? { enableRulesetIds: [RULESET_ID] }
      : { disableRulesetIds: [RULESET_ID] },
  );
  // Paths resolve from the extension root, not from this file.
  await chrome.action.setIcon({
    path: enabled
      ? { 16: 'icons/icon16.png', 32: 'icons/icon32.png' }
      : { 16: 'icons/icon16-off.png', 32: 'icons/icon32-off.png' },
  }).catch(() => {});
}

function bumpTab(tabId, amount = 1) {
  if (tabId === undefined || tabId < 0) return;
  const next = (tabCounts.get(tabId) || 0) + amount;
  tabCounts.set(tabId, next);
  updateBadge(tabId, next);
  if (cachedState) {
    cachedState.totalBlocked += amount;
    scheduleSave();
  }
}

function updateBadge(tabId, count) {
  const text = count > 999 ? '999+' : count > 0 ? String(count) : '';
  chrome.action.setBadgeText({ tabId, text }).catch(() => {});
  chrome.action.setBadgeBackgroundColor({ tabId, color: '#2f6f4f' }).catch(() => {});
}

/**
 * Live match feedback. onRuleMatchedDebug only fires for unpacked extensions,
 * so the popup also asks getMatchedRules() for a count that works when the
 * extension is installed from a store build.
 */
if (chrome.declarativeNetRequest.onRuleMatchedDebug) {
  chrome.declarativeNetRequest.onRuleMatchedDebug.addListener(({ request, rule }) => {
    if (rule.rulesetId === RULESET_ID) bumpTab(request.tabId);
  });
}

chrome.runtime.onInstalled.addListener(async () => {
  await getState();
  await applyEnabledState();
  await syncAllowRules();
});

chrome.runtime.onStartup.addListener(async () => {
  cachedState = null;
  await applyEnabledState();
  await syncAllowRules();
});

chrome.tabs.onRemoved.addListener((tabId) => tabCounts.delete(tabId));

chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  // A new document means the old count is stale.
  if (changeInfo.url || changeInfo.status === 'loading') {
    tabCounts.set(tabId, 0);
    updateBadge(tabId, 0);
  }
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  handleMessage(message, sender)
    .then(sendResponse)
    .catch((error) => sendResponse({ error: String(error) }));
  return true; // keep the channel open for the async reply
});

async function handleMessage(message, sender) {
  const state = await getState();

  switch (message?.type) {
    case 'get-status': {
      const site = message.site ?? null;
      return {
        enabled: state.enabled,
        site,
        siteAllowed: site ? state.allowedSites.includes(site) : false,
        totalBlocked: state.totalBlocked,
        tabBlocked: tabCounts.get(message.tabId) || 0,
      };
    }

    case 'is-active-for': {
      // Asked by the content script before it applies cosmetic filtering.
      const site = message.site;
      return { active: state.enabled && !(site && state.allowedSites.includes(site)) };
    }

    case 'toggle-enabled': {
      await setState({ enabled: !state.enabled });
      await applyEnabledState();
      return { enabled: cachedState.enabled };
    }

    case 'toggle-site': {
      const site = message.site;
      if (!site) return { error: 'no site' };
      const allowedSites = state.allowedSites.includes(site)
        ? state.allowedSites.filter((s) => s !== site)
        : [...state.allowedSites, site];
      await setState({ allowedSites });
      await syncAllowRules();
      return { siteAllowed: allowedSites.includes(site) };
    }

    case 'cosmetic-hidden': {
      // The content script hid elements the network layer could not stop.
      bumpTab(sender.tab?.id, message.count || 0);
      return { ok: true };
    }

    case 'reset-counter': {
      await setState({ totalBlocked: 0 });
      tabCounts.clear();
      return { totalBlocked: 0 };
    }

    default:
      return { error: `unknown message: ${message?.type}` };
  }
}

// Cold-start path: the worker can be revived without onInstalled/onStartup.
applyEnabledState().catch(() => {});
