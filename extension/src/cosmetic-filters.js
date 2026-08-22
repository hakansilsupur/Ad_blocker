/**
 * Cosmetic filter rules.
 *
 * Blocking the request stops the ad from loading but leaves the hole it was
 * going to fill: a blank 300x250 box, a sticky footer bar, a "sponsored" rail.
 * These selectors remove the container itself.
 *
 * The bar for adding a selector is that it must be unambiguous -- an ID or
 * class that exists only to hold advertising. Anything that could match real
 * content belongs in a per-site rule, not in the generic list.
 */

globalThis.COSMETIC_FILTERS = {
  /** Applied on every site. */
  generic: [
    // Google Publisher Tag / AdSense
    'ins.adsbygoogle',
    '[id^="google_ads_"]',
    '[id^="google_ads_iframe"]',
    '[id^="div-gpt-ad"]',
    '[id^="gpt-ad"]',
    'iframe[id^="google_ads_frame"]',
    'iframe[name^="google_ads_iframe"]',
    '[data-ad-client]',
    '[data-ad-slot]',
    '[data-adunit]',
    '[data-google-query-id]',

    // Content recommendation widgets
    '[id^="taboola-"]',
    '.trc_rbox_container',
    '.trc_related_container',
    '[id^="outbrain_widget"]',
    '.OUTBRAIN',
    '.ob-widget',
    '[data-widget-id^="AR_"]',

    // Conventional ad containers
    '.ad-slot',
    '.ad-unit',
    '.ad-banner',
    '.ad-container',
    '.ad-wrapper',
    '.ad-placeholder',
    '.adsbox',
    '.advertisement',
    '.advert-container',
    '#ad-container',
    '#adBanner',
    '#banner-ad',
    '[class*="sponsored-post"]',
    '[aria-label="Advertisement" i]',
    '[data-testid="ad-banner"]',

    // Ad iframes that slipped past the network layer
    'iframe[src*="doubleclick.net"]',
    'iframe[src*="googlesyndication.com"]',
    'iframe[src*="amazon-adsystem.com"]',
    'iframe[src*="adnxs.com"]',
    'iframe[src*="/adframe"]',
  ],

  /**
   * Per-site rules, keyed by registrable domain; subdomains inherit.
   * These need upkeep -- sites rename their classes, and a stale selector
   * silently stops matching rather than breaking anything.
   */
  perSite: {
    'youtube.com': [
      'ytd-display-ad-renderer',
      'ytd-promoted-video-renderer',
      'ytd-promoted-sparkles-web-renderer',
      'ytd-ad-slot-renderer',
      'ytd-in-feed-ad-layout-renderer',
      'ytd-banner-promo-renderer',
      '#masthead-ad',
      '#player-ads',
      '.ytp-ad-module',
    ],
    'reddit.com': [
      'shreddit-ad-post',
      'shreddit-comments-page-ad',
      '[data-testid="search-post-unit-ad"]',
      '.promotedlink',
    ],
    'twitch.tv': [
      '[data-a-target="video-ad-label"]',
      '.video-player__ad-overlay',
    ],
    'imdb.com': [
      '[data-testid="ad-slot"]',
      '.ipc-ad-slot',
    ],
    'weather.com': [
      '.ad-wrap',
      '[id^="WX-Ad"]',
    ],
  },

  /**
   * Substrings that mark an element's source as advertising. Used to collapse
   * frames and images whose request was already blocked, so they do not leave
   * a reserved gap in the layout.
   */
  adSourceFragments: [
    'doubleclick.net',
    'googlesyndication.com',
    'googleadservices.com',
    'amazon-adsystem.com',
    'adnxs.com',
    'pubmatic.com',
    'rubiconproject.com',
    'criteo.',
    'taboola.com',
    'outbrain.com',
    'adsrvr.org',
    'moatads.com',
    '/ad-frame',
    '/adframe',
    '/adserver',
  ],
};
