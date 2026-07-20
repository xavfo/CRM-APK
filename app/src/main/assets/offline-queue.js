(function () {
  'use strict';
  if (window.__crmOfflineQueueV2) return;
  window.__crmOfflineQueueV2 = true;

  var Q = {
    pendingKey: '__crm_offline_queue',
    cacheKey: '__crm_get_cache',
    pending: [],
    getCache: {},

    init: function () {
      this.load();
      this.patchFetch();
      this.patchXHR();
      this.autoFillGPS();
      this.injectBadge();
      this.injectStyles();
    },

    load: function () {
      try {
        var p = AndroidBridge.fetchOffline(this.pendingKey);
        if (p) this.pending = JSON.parse(p);
      } catch (e) { this.pending = []; }
      try {
        var c = AndroidBridge.fetchOffline(this.cacheKey);
        if (c) this.getCache = JSON.parse(c);
      } catch (e) { this.getCache = {}; }
    },

    savePending: function () {
      try { AndroidBridge.storeOffline(this.pendingKey, JSON.stringify(this.pending)); } catch (e) {}
      this.updateBadge();
    },

    saveCache: function () {
      try { AndroidBridge.storeOffline(this.cacheKey, JSON.stringify(this.getCache)); } catch (e) {}
    },

    add: function (url, method, body, contentType) {
      this.pending.push({
        id: Date.now() + '_' + Math.random().toString(36).substr(2, 9),
        url: url,
        method: method || 'POST',
        body: body,
        contentType: contentType || 'application/json',
        timestamp: Date.now(),
        retries: 0
      });
      this.savePending();
      try { AndroidBridge.showToast('Guardado offline. Pendientes: ' + this.pending.length); } catch (e) {}
    },

    remove: function (id) {
      this.pending = this.pending.filter(function (i) { return i.id !== id; });
      this.savePending();
    },

    isWriteRequest: function (url, method) {
      method = (method || 'GET').toUpperCase();
      return method !== 'GET' && method !== 'HEAD';
    },

    isApiRequest: function (url) {
      return url.indexOf('/api/') !== -1;
    },

    cacheGetResponse: function (url, body, contentType) {
      if (!body) return;
      if (this.getCache[url]) return;
      this.getCache[url] = {
        body: body,
        contentType: contentType || 'application/json',
        cachedAt: Date.now()
      };
      this.saveCache();
    },

    serveCachedGet: function (url) {
      var cached = this.getCache[url];
      if (!cached) return null;
      try {
        return new Response(cached.body, {
          status: 200,
          headers: { 'Content-Type': cached.contentType || 'application/json' }
        });
      } catch (e) { return null; }
    },

    generateFakeId: function () {
      return 'offline_' + Date.now().toString(36) + '_' + Math.random().toString(36).substr(2, 6);
    },

    generateFakeResponse: function (url, body) {
      var id = this.generateFakeId();
      var data = { id: id };
      if (body && typeof body === 'object') {
        Object.keys(body).forEach(function (k) { data[k] = body[k]; });
      }
      data._offline = true;
      data._synced = false;
      return new Response(JSON.stringify(data), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    },

    /* ---- FETCH INTERCEPT ---- */
    patchFetch: function () {
      var self = this;
      var origFetch = window.fetch;

      window.fetch = function (url, opts) {
        opts = opts || {};
        var urlStr = (typeof url === 'string') ? url : (url && url.url ? url.url : '');

        if (!navigator.onLine) {
          if ((!opts.method || opts.method === 'GET') && self.isApiRequest(urlStr)) {
            var cached = self.serveCachedGet(urlStr);
            if (cached) return Promise.resolve(cached);
          }
          if (self.isWriteRequest(urlStr, opts.method)) {
            var body = opts.body;
            if (body && typeof body === 'string') {
              try { body = JSON.parse(body); } catch (e) {}
            }
            self.add(urlStr, opts.method, body,
              (opts.headers && opts.headers['Content-Type']) || 'application/json');
            return Promise.resolve(self.generateFakeResponse(urlStr, body));
          }
        } else {
          if ((!opts.method || opts.method === 'GET') && self.isApiRequest(urlStr)) {
            return origFetch.call(this, url, opts).then(function (resp) {
              if (resp.ok) {
                resp.clone().text().then(function (text) {
                  self.cacheGetResponse(urlStr, text, 'application/json');
                });
              }
              return resp;
            });
          }
        }
        return origFetch.call(this, url, opts);
      };
    },

    /* ---- XHR INTERCEPT ---- */
    patchXHR: function () {
      var self = this;
      var OrigProto = XMLHttpRequest.prototype;
      var origOpen = OrigProto.open;
      var origSend = OrigProto.send;
      var origSetRH = OrigProto.setRequestHeader;

      OrigProto.open = function (method, url) {
        this.__method = method;
        this.__url = (typeof url === 'string') ? url : (url ? url.toString() : '');
        this.__headers = {};
        this.__body = null;
        return origOpen.apply(this, arguments);
      };

      OrigProto.setRequestHeader = function (header, value) {
        this.__headers = this.__headers || {};
        this.__headers[header] = value;
        return origSetRH.apply(this, arguments);
      };

      OrigProto.send = function (body) {
        if (!navigator.onLine) {
          if ((!this.__method || this.__method === 'GET') && self.isApiRequest(this.__url)) {
            var cached = self.serveCachedGet(this.__url);
            if (cached) {
              var xhr = this;
              cached.text().then(function (text) {
                try {
                  Object.defineProperty(xhr, 'readyState', { value: 4, configurable: true });
                  Object.defineProperty(xhr, 'status', { value: 200, configurable: true });
                  Object.defineProperty(xhr, 'responseText', { value: text, configurable: true });
                  if (xhr.onreadystatechange) xhr.onreadystatechange();
                  if (xhr.onload) xhr.onload();
                } catch (e) {}
              });
              return;
            }
          }
          if (self.isWriteRequest(this.__url, this.__method)) {
            var parsedBody = body;
            if (typeof body === 'string') {
              try { parsedBody = JSON.parse(body); } catch (e) {}
            }
            self.add(this.__url, this.__method, parsedBody,
              this.__headers['Content-Type'] || 'application/json');

            var id = self.generateFakeId();
            var fakeData = { id: id, _offline: true, _synced: false };
            if (parsedBody && typeof parsedBody === 'object') {
              Object.keys(parsedBody).forEach(function (k) { fakeData[k] = parsedBody[k]; });
            }
            var fakeText = JSON.stringify(fakeData);

            var xhr = this;
            setTimeout(function () {
              try {
                Object.defineProperty(xhr, 'readyState', { value: 4, configurable: true });
                Object.defineProperty(xhr, 'status', { value: 200, configurable: true });
                Object.defineProperty(xhr, 'responseText', { value: fakeText, configurable: true });
                if (xhr.onreadystatechange) xhr.onreadystatechange();
                if (xhr.onload) xhr.onload();
              } catch (e) {}
            }, 200);
            return;
          }
        } else {
          var xhr = this;
          var origOnReady = xhr.onreadystatechange;
          xhr.onreadystatechange = function () {
            if (xhr.readyState === 4 && xhr.status === 200 &&
                (!xhr.__method || xhr.__method === 'GET') && self.isApiRequest(xhr.__url)) {
              self.cacheGetResponse(xhr.__url, xhr.responseText,
                xhr.getResponseHeader('Content-Type') || 'application/json');
            }
            if (origOnReady) origOnReady.apply(xhr, arguments);
          };
        }
        return origSend.apply(this, arguments);
      };
    },

    /* ---- GPS AUTO-FILL ---- */
    autoFillGPS: function () {
      var observer = new MutationObserver(function () {
        if (!navigator.onLine) return;
        var locStr;
        try { locStr = AndroidBridge.getLocation(); } catch (e) { return; }
        if (!locStr || locStr === '{}' || locStr.indexOf('error') !== -1) return;
        var gps;
        try { gps = JSON.parse(locStr); } catch (e) { return; }
        if (!gps || !gps.latitude) return;

        var filled = false;
        var inputs = document.querySelectorAll('input:not([type=hidden]):not([type=password]), textarea');
        Array.prototype.forEach.call(inputs, function (el) {
          var name = (el.name || '').toLowerCase();
          var id = (el.id || '').toLowerCase();
          var ph = (el.placeholder || '').toLowerCase();
          if (name === 'latitude' || name === 'lat' || id === 'latitude' || id === 'lat') {
            if (!el.value) { el.value = gps.latitude; filled = true; }
          } else if (name === 'longitude' || name === 'lng' || name === 'lon' || id === 'longitude' || id === 'lng' || id === 'lon') {
            if (!el.value) { el.value = gps.longitude; filled = true; }
          } else if (name.indexOf('gps') !== -1 || id.indexOf('gps') !== -1 ||
                     name.indexOf('location') !== -1 || id.indexOf('location') !== -1 ||
                     name.indexOf('coordinates') !== -1 || id.indexOf('coordinates') !== -1 ||
                     ph.indexOf('gps') !== -1 || ph.indexOf('ubicaci') !== -1) {
            if (!el.value) { el.value = gps.latitude + ', ' + gps.longitude; filled = true; }
          }
        });
      });
      observer.observe(document.body || document.documentElement, {
        childList: true, subtree: true, attributes: false
      });
      setTimeout(function () { observer.disconnect(); }, 30000);
    },

    /* ---- BADGE ---- */
    injectBadge: function () {
      var badge = document.createElement('div');
      badge.id = '__crm_offline_badge';
      badge.title = 'Registros pendientes de sincronizar';
      badge.onclick = function () { window.__syncOfflineQueue(); };
      document.addEventListener('DOMContentLoaded', function () {
        document.body.appendChild(badge);
        Q.updateBadge();
      });
      if (document.body) {
        document.body.appendChild(badge);
        this.updateBadge();
      }
    },

    updateBadge: function () {
      var badge = document.getElementById('__crm_offline_badge');
      if (!badge) return;
      if (this.pending.length === 0) {
        badge.style.display = 'none';
        return;
      }
      badge.textContent = this.pending.length;
      badge.style.display = 'flex';
    },

    injectStyles: function () {
      var style = document.createElement('style');
      style.textContent =
        '#__crm_offline_badge{' +
        'position:fixed;top:12px;right:12px;z-index:99999;' +
        'background:#e74c3c;color:#fff;border-radius:50%;' +
        'width:28px;height:28px;font-size:13px;font-weight:bold;' +
        'display:none;align-items:center;justify-content:center;' +
        'cursor:pointer;box-shadow:0 2px 6px rgba(0,0,0,.3);' +
        'font-family:sans-serif;}';
      var append = function () { document.head.appendChild(style); };
      if (document.head) { append(); }
      else { document.addEventListener('DOMContentLoaded', append); }
    },

    /* ---- SYNC (sequential) ---- */
    syncNext: function () {
      var self = this;
      if (this.pending.length === 0) {
        this.updateBadge();
        try { AndroidBridge.showToast('Sincronización completada'); } catch (e) {}
        return;
      }
      var item = this.pending[0];
      this.pending.shift();
      this.savePending();

      var body = item.body;
      if (body && typeof body === 'object') { body = JSON.stringify(body); }
      var headers = { 'Content-Type': item.contentType || 'application/json' };

      fetch(item.url, {
        method: item.method || 'POST',
        headers: headers,
        body: body
      }).then(function (resp) {
        if (resp.ok) {
          try { AndroidBridge.showToast('Sincronizado: ' + (item.url.split('/').pop() || 'ok')); } catch (e) {}
          self.syncNext();
        } else {
          item.retries++;
          self.pending.unshift(item);
          self.savePending();
          self.syncNext();
        }
      }).catch(function () {
        item.retries++;
        self.pending.unshift(item);
        self.savePending();
        try { AndroidBridge.showToast('Error de red, reintentando más tarde'); } catch (e) {}
        self.updateBadge();
      });
    },

    sync: function () {
      if (this.pending.length === 0) {
        this.updateBadge();
        try { AndroidBridge.showToast('No hay registros pendientes'); } catch (e) {}
        return;
      }
      try { AndroidBridge.showToast('Sincronizando ' + this.pending.length + ' registro(s)...'); } catch (e) {}
      this.syncNext();
    }
  };

  Q.init();

  window.__syncOfflineQueue = function () { Q.sync(); };

  window.onSyncTriggered = function () {
    setTimeout(function () { Q.sync(); }, 1500);
  };
})();
