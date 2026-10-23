// Defines window.StripeTerminal (the frozen bridge interface used by the Base44 web app)
// on top of the Capacitor plugin at window.Capacitor.Plugins.StripeTerminal.
// Injected by the native shell on every page load; safe to run more than once.
(function () {
  if (window.__otheStripeTerminalShim) return;
  window.__otheStripeTerminalShim = true;

  function plugin() {
    var C = window.Capacitor;
    if (!C || !C.Plugins) return null;
    if (typeof C.isPluginAvailable === 'function' && !C.isPluginAvailable('StripeTerminal')) return null;
    return C.Plugins.StripeTerminal || null;
  }

  function toError(e) {
    return {
      code: (e && e.code) || 'UNKNOWN',
      message: (e && (e.message || e.errorMessage)) || String(e),
    };
  }

  function invoke(method, args) {
    var p = plugin();
    if (!p) return Promise.reject({ code: 'UNAVAILABLE', message: 'Stripe Terminal is not available in this app' });
    listen();
    return p[method](args || {}).catch(function (e) { throw toError(e); });
  }

  function noValue() {}

  var listening = false;
  function listen() {
    if (listening) return;
    var p = plugin();
    if (!p || typeof p.addListener !== 'function') return;
    listening = true;
    p.addListener('readersDiscovered', function (data) {
      if (typeof api.onReadersDiscovered === 'function') api.onReadersDiscovered((data && data.readers) || []);
    });
    // Optional extras (additive, not part of the frozen interface).
    p.addListener('unexpectedDisconnect', function (data) {
      if (typeof api.onUnexpectedDisconnect === 'function') api.onUnexpectedDisconnect(data || {});
    });
    p.addListener('readerMessage', function (data) {
      if (typeof api.onReaderMessage === 'function') api.onReaderMessage(data || {});
    });
    p.addListener('connectionStatusChange', function (data) {
      if (typeof api.onConnectionStatusChange === 'function') api.onConnectionStatusChange(data || {});
    });
    // The native SDK needs a connection token: ask the page's provider (logged-in user session).
    p.addListener('connectionTokenRequested', function (data) {
      var requestId = data && data.requestId;
      var provider = api.__tokenProvider;
      var answer = provider
        ? Promise.resolve().then(provider).then(function (secret) {
            if (!secret || typeof secret !== 'string') throw new Error('Connection token provider returned no secret');
            return { requestId: requestId, secret: secret };
          })
        : Promise.reject(new Error('No connection token provider set. Call StripeTerminal.setConnectionTokenProvider() first.'));
      answer
        .catch(function (e) { return { requestId: requestId, error: (e && e.message) || String(e) }; })
        .then(function (result) { return p.provideConnectionToken(result); })
        .catch(function () {});
    });
  }

  // Keep anything the page already attached (e.g. an onReadersDiscovered handler).
  var api = window.StripeTerminal || {};

  // Version 2 adds: setConnectionTokenProvider(), collectPayment({ clientSecret }),
  // onConnectionStatusChange. Everything from version 1 is unchanged.
  api.bridgeVersion = 2;

  api.isAvailable = function () { return !!plugin(); };
  // fn: () => Promise<string>, resolving to a fresh connection token secret from the backend.
  api.setConnectionTokenProvider = function (fn) {
    api.__tokenProvider = typeof fn === 'function' ? fn : null;
    listen();
  };
  api.connect = function (config) { return invoke('connect', config); };
  api.discoverReaders = function () { return invoke('discoverReaders').then(noValue); };
  api.connectReader = function (readerSerialNumber) {
    return invoke('connectReader', { readerSerialNumber: readerSerialNumber });
  };
  api.collectPayment = function (opts) { return invoke('collectPayment', opts); };
  api.cancelCollect = function () { return invoke('cancelCollect').then(noValue); };
  api.getConnectionStatus = function () { return invoke('getConnectionStatus'); };
  api.disconnect = function () { return invoke('disconnect').then(noValue); };
  if (!('onReadersDiscovered' in api)) api.onReadersDiscovered = null;

  window.StripeTerminal = api;

  // Capacitor's own bridge script may load after this one; attach listeners once it's there.
  var tries = 0;
  (function waitForCapacitor() {
    listen();
    if (!listening && tries++ < 100) setTimeout(waitForCapacitor, 100);
  })();
})();
