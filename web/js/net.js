/*
 * NeonGrid secure transport for the browser.
 *
 *  WebSocket (RFC 6455, upgraded from HTTP by our NIO server)
 *   └─ Diffie-Hellman key agreement (RFC 3526 group 14, 2048-bit) using BigInt
 *       └─ session key = SHA-256(hex(g^ab mod p))
 *           └─ every frame = HMAC-SHA256(key, json) + json, with a strictly increasing "seq"
 *
 * SHA-256 / HMAC are implemented in plain JS because crypto.subtle is only available on
 * HTTPS/localhost, and phones join over plain http://192.168.x.x on the LAN.
 */
(function (global) {
  'use strict';

  // ------------------------------------------------------------------ SHA-256
  const K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2]);

  function sha256(msg) {
    const len = msg.length;
    const padded = new Uint8Array(((len + 9 + 63) >> 6) << 6);
    padded.set(msg);
    padded[len] = 0x80;
    const bits = len * 8;
    const dv = new DataView(padded.buffer);
    dv.setUint32(padded.length - 4, bits >>> 0);
    dv.setUint32(padded.length - 8, Math.floor(bits / 0x100000000));
    const H = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
    const W = new Uint32Array(64);
    for (let off = 0; off < padded.length; off += 64) {
      for (let i = 0; i < 16; i++) W[i] = dv.getUint32(off + i * 4);
      for (let i = 16; i < 64; i++) {
        const a = W[i - 15], b = W[i - 2];
        const s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
        const s1 = ((b >>> 17) | (b << 15)) ^ ((b >>> 19) | (b << 13)) ^ (b >>> 10);
        W[i] = (W[i - 16] + s0 + W[i - 7] + s1) | 0;
      }
      let a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (let i = 0; i < 64; i++) {
        const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
        const ch = (e & f) ^ (~e & g);
        const t1 = (h + S1 + ch + K[i] + W[i]) | 0;
        const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
        const maj = (a & b) ^ (a & c) ^ (b & c);
        const t2 = (S0 + maj) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] += a; H[1] += b; H[2] += c; H[3] += d; H[4] += e; H[5] += f; H[6] += g; H[7] += h;
    }
    const out = new Uint8Array(32);
    const odv = new DataView(out.buffer);
    for (let i = 0; i < 8; i++) odv.setUint32(i * 4, H[i]);
    return out;
  }

  function hmac(key, msg) {
    if (key.length > 64) key = sha256(key);
    const k = new Uint8Array(64);
    k.set(key);
    const ipad = new Uint8Array(64 + msg.length), opad = new Uint8Array(64 + 32);
    for (let i = 0; i < 64; i++) { ipad[i] = k[i] ^ 0x36; opad[i] = k[i] ^ 0x5c; }
    ipad.set(msg, 64);
    opad.set(sha256(ipad), 64);
    return sha256(opad);
  }

  const enc = new TextEncoder();
  const hex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  const unhex = (s) => Uint8Array.from(s.match(/../g) || [], (h) => parseInt(h, 16));
  const concat = (a, b) => { const r = new Uint8Array(a.length + b.length); r.set(a); r.set(b, a.length); return r; };
  const xor = (a, b) => a.map((v, i) => v ^ b[i]);

  // ------------------------------------------------------------------ Diffie-Hellman
  const P_HEX =
    'FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1' +
    '29024E088A67CC74020BBEA63B139B22514A08798E3404DD' +
    'EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245' +
    'E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED' +
    'EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D' +
    'C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F' +
    '83655D23DCA3AD961C62F356208552BB9ED529077096966D' +
    '670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B' +
    'E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9' +
    'DE2BCBF6955817183995497CEA956AE515D2261898FA0510' +
    '15728E5A8AACAA68FFFFFFFFFFFFFFFF';
  const P = BigInt('0x' + P_HEX);
  const G = 2n;

  function modPow(base, exp, mod) {
    let r = 1n;
    base %= mod;
    while (exp > 0n) {
      if (exp & 1n) r = (r * base) % mod;
      exp >>= 1n;
      base = (base * base) % mod;
    }
    return r;
  }

  function randomPrivate() {
    const b = new Uint8Array(32);
    crypto.getRandomValues(b);
    b[0] |= 0x80;
    return BigInt('0x' + hex(b));
  }

  // ------------------------------------------------------------------ secure socket
  class SecureSocket {
    constructor(opts = {}) {
      this.handlers = {};
      this.onFrame = opts.onFrame || (() => {});
      this.onStage = opts.onStage || (() => {});
      this.onClose = opts.onClose || (() => {});
      this.key = null;
      this.outSeq = 0;
      this.inSeq = 0;
      this.lastByType = {};
      this.rtt = -1;
      this.storageKey = opts.storageKey || 'ng.session';
      this.stats = { framesIn: 0, framesOut: 0, bytesIn: 0, bytesOut: 0, badMac: 0 };
    }

    on(type, fn) { (this.handlers[type] = this.handlers[type] || []).push(fn); return this; }

    emit(type, msg) { (this.handlers[type] || []).forEach((f) => f(msg)); (this.handlers['*'] || []).forEach((f) => f(msg)); }

    savedSession() {
      try { return JSON.parse(sessionStorage.getItem(this.storageKey) || 'null'); } catch (e) { return null; }
    }

    forget() { try { sessionStorage.removeItem(this.storageKey); } catch (e) { /* ignore */ } }

    connect(name, client = 'web') {
      return new Promise((resolve, reject) => {
        const t0 = performance.now();
        const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
        this.key = null; this.outSeq = 0; this.inSeq = 0;
        this.onStage('tcp', 'Opening TCP connection to ' + location.host);
        const ws = new WebSocket(url);
        this.ws = ws;
        let priv, pubHex, settled = false;

        ws.onopen = () => {
          this.onStage('upgrade', 'HTTP/1.1 101 Switching Protocols', performance.now() - t0);
          const td = performance.now();
          priv = randomPrivate();
          pubHex = modPow(G, priv, P).toString(16);
          const hello = { t: 'hello', name, client, dh: pubHex };
          const saved = this.savedSession();
          if (saved && saved.sid && saved.token) {
            hello.resume = { sid: saved.sid, proof: hex(hmac(unhex(saved.token), enc.encode(pubHex))) };
          }
          const raw = JSON.stringify(hello);
          ws.send(raw);
          this.track('out', 'hello', raw, 'plain', null);
          this.onStage('dh', 'Sent gᵃ mod p (' + (pubHex.length * 4) + ' bits) in ' + (performance.now() - td).toFixed(0) + ' ms');
        };

        ws.onmessage = (ev) => {
          const data = ev.data;
          if (!this.key) {
            let w;
            try { w = JSON.parse(data); } catch (e) { return; }
            this.track('in', w.t, data, 'plain', null);
            if (w.t !== 'welcome') return;
            const tk = performance.now();
            const shared = modPow(BigInt('0x' + w.dh), priv, P);
            this.key = sha256(enc.encode(shared.toString(16)));
            const token = xor(unhex(w.tokenWrapped), sha256(concat(this.key, enc.encode('token'))));
            try { sessionStorage.setItem(this.storageKey, JSON.stringify({ sid: w.sid, token: hex(token), name })); } catch (e) { /* ignore */ }
            this.fingerprint = hex(sha256(this.key)).slice(0, 16).match(/..../g).join(' ');
            this.onStage('key', 'Session key derived · fingerprint ' + this.fingerprint, performance.now() - tk);
            this.resumed = !!w.resumed;
            return;
          }
          const mac = data.slice(0, 64), body = data.slice(64);
          const ok = hex(hmac(this.key, enc.encode(body))) === mac;
          let msg;
          try { msg = JSON.parse(body); } catch (e) { return; }
          const fresh = typeof msg.seq === 'number' && msg.seq > this.inSeq;
          this.track('in', msg.t, data, ok && fresh ? 'ok' : 'bad', msg.seq, mac);
          if (!ok || !fresh) { this.stats.badMac++; return; }
          this.inSeq = msg.seq;
          if (msg.t === 'pong') this.rtt = Math.round(performance.now() - msg.ts);
          if (msg.t === 'profile' && !settled) {
            settled = true;
            this.onStage('secure', 'HMAC-SHA256 channel live', performance.now() - t0);
            resolve(msg);
          }
          this.emit(msg.t, msg);
        };

        ws.onerror = () => { if (!settled) { settled = true; reject(new Error('Cannot reach the server')); } };
        ws.onclose = (ev) => {
          if (this.pinger) clearInterval(this.pinger);
          if (!settled) { settled = true; reject(new Error('Connection closed during handshake')); }
          this.onClose(ev);
        };

        this.pinger = setInterval(() => { if (this.key) this.send({ t: 'ping', ts: performance.now(), rtt: this.rtt }); }, 2000);
      });
    }

    /** Signs and sends a message. */
    send(obj) {
      if (!this.key || !this.ws || this.ws.readyState !== 1) return null;
      obj.seq = ++this.outSeq;
      const body = JSON.stringify(obj);
      const frame = hex(hmac(this.key, enc.encode(body))) + body;
      this.ws.send(frame);
      this.lastByType[obj.t] = frame;
      this.lastFrame = frame;
      this.track('out', obj.t, frame, 'ok', obj.seq, frame.slice(0, 64));
      return frame;
    }

    /** Sends an arbitrary pre-built frame (used by the Security Lab attacks). */
    sendRaw(frame, label) {
      if (!this.ws || this.ws.readyState !== 1) return;
      this.ws.send(frame);
      let seq = null;
      try { seq = JSON.parse(frame.slice(64)).seq; } catch (e) { /* ignore */ }
      this.track('out', label, frame, 'attack', seq, frame.slice(0, 64));
    }

    /** Builds a frame signed for `signed` but carrying `sent` - a man-in-the-middle edit. */
    forgeTampered(signedObj, mutate) {
      signedObj.seq = ++this.outSeq;
      const body = JSON.stringify(signedObj);
      const mac = hex(hmac(this.key, enc.encode(body)));
      const changed = JSON.parse(body);
      mutate(changed);
      return { frame: mac + JSON.stringify(changed), original: body, changed: JSON.stringify(changed) };
    }

    /** A frame signed with a random key: an attacker who never learned the session key. */
    forgeWithoutKey(obj) {
      obj.seq = ++this.outSeq;
      const fake = new Uint8Array(32);
      crypto.getRandomValues(fake);
      const body = JSON.stringify(obj);
      return hex(hmac(fake, enc.encode(body))) + body;
    }

    track(dir, type, raw, status, seq, mac) {
      const bytes = enc.encode(raw).length;
      if (dir === 'in') { this.stats.framesIn++; this.stats.bytesIn += bytes; } else { this.stats.framesOut++; this.stats.bytesOut += bytes; }
      this.onFrame({ dir, type, raw, bytes, status, seq, mac, time: new Date() });
    }

    close() { if (this.ws) this.ws.close(); }
  }

  global.NG = { SecureSocket, sha256, hmac, hex, unhex, modPow, P, G };
})(window);
