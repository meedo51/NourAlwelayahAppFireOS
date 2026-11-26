// Android WebView has no Web Bluetooth, so this gives PTclock.html the part of
// navigator.bluetooth it uses, backed by BleUart through the AndroidBle bridge.
(function () {
  const native = window.AndroidBle;
  const calls = {};
  let nextCall = 0;
  let connected = false;
  let autoRequest = false;
  let lostListeners = [];

  function call(method, ...args) {
    return new Promise((resolve, reject) => {
      const id = ++nextCall;
      calls[id] = { resolve, reject };
      native[method](id, ...args);
    });
  }

  function toBase64(bytes) {
    return btoa(String.fromCharCode.apply(null, bytes));
  }

  function fromBase64(text) {
    const raw = atob(text);
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return bytes;
  }

  const rx = {
    writeValueWithResponse(data) {
      return call('write', toBase64(new Uint8Array(data.buffer || data, data.byteOffset || 0, data.byteLength)));
    }
  };

  const tx = {
    value: null,
    listeners: [],
    addEventListener(type, fn) { this.listeners.push(fn); },
    removeEventListener(type, fn) { this.listeners = this.listeners.filter(l => l !== fn); },
    startNotifications() { return Promise.resolve(tx); } // BleUart turns them on when connecting
  };

  const service = {
    getCharacteristic(uuid) { return Promise.resolve(uuid.startsWith('6e400002') ? rx : tx); }
  };

  const server = {
    get connected() { return connected; },
    connect() {
      return call('connect').then(() => {
        connected = true;
        localStorage.setItem('lastMode', 'ble');
        return server;
      });
    },
    disconnect() { native.disconnect(); },
    getPrimaryService() { return Promise.resolve(service); }
  };

  const device = {
    name: '',
    gatt: server,
    addEventListener(type, fn) { lostListeners.push(fn); },
    removeEventListener(type, fn) { lostListeners = lostListeners.filter(l => l !== fn); }
  };

  Object.defineProperty(navigator, 'bluetooth', {
    configurable: true,
    value: {
      requestDevice() {
        const auto = autoRequest;
        autoRequest = false;
        return call('requestDevice', auto).then(name => { device.name = name; return device; });
      }
    }
  });

  window.__bleDone = (id, ok, value) => {
    const c = calls[id];
    if (!c) return;
    delete calls[id];
    if (ok) c.resolve(value); else c.reject(new Error(value));
  };

  window.__bleData = text => {
    tx.value = fromBase64(text);
    tx.listeners.slice().forEach(fn => fn({ target: tx }));
  };

  window.__bleLost = () => {
    connected = false;
    lostListeners.forEach(fn => fn({ target: device }));
  };

  // The page saves Bluetooth downloads through a blob: link, which WebView can't
  // follow, so hand the bytes to the app. Replaced once the page has defined its own.
  window.addEventListener('DOMContentLoaded', () => {
    window.saveBlob = (parts, name) => {
      const reader = new FileReader();
      reader.onload = () => native.saveFile(name, reader.result.split(',')[1]);
      reader.readAsDataURL(new Blob(parts));
    };

    const pageWifiIp = window.wifiIp;
    window.wifiIp = () => {
      pageWifiIp();
      if (MODE === 'wifi') localStorage.setItem('lastMode', 'wifi');
    };

    keepPins();
    autoConnect();
  });

  // The page holds the PIN in memory only, so the app keeps it, one for each clock.
  // BASE is empty on Bluetooth, where the app goes by the selected clock instead.
  function keepPins() {
    const pageAuth = window.auth;
    window.auth = () => {
      P = native.savedPin(BASE);
      return pageAuth();
    };

    // The page starts only once the clock has accepted P
    const pageStart = window.start;
    window.start = () => {
      if (P) native.savePin(BASE, P);
      pageStart();
    };

    const pageApi = window.api;
    window.api = (path, args, post) => {
      const reply = pageApi(path, args, post);
      if (args && 'newpin' in args) {
        reply.then(d => { if (d.ok) native.savePin(BASE, String(args.newpin)); }, () => {});
      }
      return reply;
    };

    const pageLogout = window.logout;
    window.logout = () => {
      pageLogout();
      if (!P) native.savePin(BASE, '');
    };
  }

  async function connectBluetooth() {
    if (!native.hasSavedClock()) return false;
    autoRequest = true;
    await bleConnect();
    return MODE === 'ble';
  }

  async function connectWifi() {
    const ip = localStorage.getItem('ip');
    if (!ip) return false;
    try {
      // Any answer will do. Without the PIN the clock replies 401, and the page handles that.
      await fetch('http://' + ip + '/api/status', { signal: AbortSignal.timeout(3000) });
    } catch (e) {
      return false;
    }
    if (MODE) return true; // Connected by hand while waiting
    $('ip').value = ip;
    wifiIp();
    return true;
  }

  // Tries the way that worked last, then the other one. The page's own status line
  // and Connect buttons take over if neither gets through.
  async function autoConnect() {
    const steps = localStorage.getItem('lastMode') === 'wifi'
      ? [connectWifi, connectBluetooth]
      : [connectBluetooth, connectWifi];
    if (!native.hasSavedClock() && !localStorage.getItem('ip')) return;
    conn('Reconnecting...');
    for (const step of steps) {
      if (MODE) return;
      if (await step()) return;
    }
    if (!MODE && $('cst').textContent === 'Reconnecting...') {
      conn('Clock not found - press Connect Bluetooth');
    }
  }
})();
