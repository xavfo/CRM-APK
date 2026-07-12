(function () {
  'use strict';
  if (window.__crmOfflineQueue) return;
  window.__crmOfflineQueue = true;

  var Q = {
    items: [],
    storageKey: '__crm_offline_queue',

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
        var data = AndroidBridge.fetchOffline(this.storageKey);
        if (data) { this.items = JSON.parse(data); }
      } catch (e) { this.items = []; }
    },

    save: function () {
      try {
        AndroidBridge.storeOffline(this.storageKey, JSON.stringify(this.items));
      } catch (e) {}
    },

    add: function (url, method, body, contentType) {
      this.items.push({
        id: Date.now() + '_' + Math.random().toString(36).substr(2, 9),
        url: url,
        method: method || 'POST',
        body: body,
        contentType: contentType || 'application/json',
        timestamp: Date.now(),
        retries: 0
      });
      this.save();
      this.updateBadge();
      try {
        AndroidBridge.showToast('Guardado offline. Pendientes: ' + this.items.length);
      } catch (e) {}
    },

    remove: function (id) {
      this.items = this.items.filter(function (i) { return i.id !== id; });
      this.save();
      this.updateBadge();
    },

    isWriteRequest: function (url, method) {
      method = (method || 'GET').toUpperCase();
      return method !== 'GET' && method !== 'HEAD' && url.indexOf('/api/') !== -1;
    },

    /* ---- FETCH INTERCEPT ---- */
    patchFetch: function () {
      var self = this;
      var origFetch = window.fetch;
      window.fetch = function (url, opts) {
        opts = opts || {};
        if (typeof url !== 'string') {
          if (url && url.url) url = url.url;
          else return origFetch.apply(this, arguments);
        }
        if (!navigator.onLine && self.isWriteRequest(url, opts.method)) {
          var body = opts.body;
          if (body && typeof body === 'string') {
            try { body = JSON.parse(body); } catch (e) {}
          }
          self.add(url, opts.method, body, (opts.headers && opts.headers['Content-Type']) || 'application/json');
          return Promise.resolve(new Response(JSON.stringify({ status: 'queued_offline', offlineId: 'offline_' + Date.now() }), {
            status: 200, headers: { 'Content-Type': 'application/json' }
          }));
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
        return origOpen.apply(this, arguments);
      };

      OrigProto.setRequestHeader = function (header, value) {
        this.__headers = this.__headers || {};
        this.__headers[header] = value;
        return origSetRH.apply(this, arguments);
      };

      OrigProto.send = function (body) {
        if (!navigator.onLine && self.isWriteRequest(this.__url, this.__method)) {
          var parsedBody = body;
          if (typeof body === 'string') {
            try { parsedBody = JSON.parse(body); } catch (e) {}
          }
          self.add(this.__url, this.__method, parsedBody, this.__headers['Content-Type'] || 'application/json');
          var xhr = this;
          setTimeout(function () {
            try {
              Object.defineProperty(xhr, 'readyState', { value: 4, configurable: true });
              Object.defineProperty(xhr, 'status', { value: 200, configurable: true });
              Object.defineProperty(xhr, 'responseText', {
                value: JSON.stringify({ status: 'queued_offline' }), configurable: true
              });
              if (xhr.onreadystatechange) xhr.onreadystatechange();
              if (xhr.onload) xhr.onload();
            } catch (e) {}
          }, 100);
          return;
        }
        return origSend.apply(this, arguments);
      };
    },

    /* ---- GPS AUTO-FILL ---- */
    autoFillGPS: function () {
      var self = this;
      var observer = new MutationObserver(function () {
        if (!navigator.onLine) return;
        var locStr;
        try { locStr = AndroidBridge.getLocation(); } catch (e) { return; }
        if (!locStr || locStr === '{}' || locStr.indexOf('error') !== -1) return;
        var gps;
        try { gps = JSON.parse(locStr); } catch (e) { return; }
        if (!gps.latitude) return;

        var filled = false;
        var inputs = document.querySelectorAll('input:not([type=hidden]):not([type=password]), textarea');
        Array.prototype.forEach.call(inputs, function (el) {
          var name = (el.name || '').toLowerCase();
          var id = (el.id || '').toLowerCase();
          var cls = (el.className || '').toLowerCase();
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
      document.body && document.body.appendChild(badge);
      this.updateBadge();
    },

    updateBadge: function () {
      var badge = document.getElementById('__crm_offline_badge');
      if (!badge) return;
      if (this.items.length === 0) {
        badge.style.display = 'none';
        return;
      }
      badge.textContent = this.items.length;
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
      document.head && document.head.appendChild(style);
    },

    /* ---- SYNC ---- */
    syncNext: function () {
      var self = this;
      if (this.items.length === 0) {
        this.updateBadge();
        try { AndroidBridge.showToast('Sincronización completada'); } catch (e) {}
        return;
      }
      var item = this.items[0];
      this.items.shift();
      this.save();

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
          self.items.unshift(item);
          item.retries++;
          self.save();
          self.syncNext();
        }
      }).catch(function () {
        self.items.unshift(item);
        item.retries++;
        self.save();
        try { AndroidBridge.showToast('Error de red, reintentando más tarde'); } catch (e) {}
        self.updateBadge();
      });
    },

    sync: function () {
      if (this.items.length === 0) {
        this.updateBadge();
        try { AndroidBridge.showToast('No hay registros pendientes'); } catch (e) {}
        return;
      }
      try { AndroidBridge.showToast('Sincronizando ' + this.items.length + ' registro(s)...'); } catch (e) {}
      this.syncNext();
    }
  };

  Q.init();

  window.__syncOfflineQueue = function () { Q.sync(); };

  window.onSyncTriggered = function () {
    setTimeout(function () { Q.sync(); }, 1500);
  };
})();
