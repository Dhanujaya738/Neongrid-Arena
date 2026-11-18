/* NeonGrid Network Control Room — admin / evaluator console (separate from the game) */
(function () {
  'use strict';
  const $ = (s) => document.querySelector(s);
  const $$ = (s) => Array.from(document.querySelectorAll(s));
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const enc = new TextEncoder();
  const kb = (b) => (b / 1024).toFixed(1);
  const fmt = (n) => Number(n || 0).toLocaleString();
  const bytes = (b) => b > 1048576 ? (b / 1048576).toFixed(1) + ' MB' : b > 1024 ? (b / 1024).toFixed(1) + ' KB' : b + ' B';
  const age = (s) => s >= 3600 ? Math.floor(s / 3600) + 'h' + Math.floor((s % 3600) / 60) + 'm' : s >= 60 ? Math.floor(s / 60) + 'm' + (s % 60) + 's' : s + 's';
  const time = (ts) => { const d = new Date(ts); return d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0'); };
  const ss = { get(k) { try { return sessionStorage.getItem(k); } catch (e) { return null; } }, set(k, v) { try { sessionStorage.setItem(k, v); } catch (e) { /* ignore */ } }, del(k) { try { sessionStorage.removeItem(k); } catch (e) { /* ignore */ } } };

  function pill(id, cls, text) { const p = $(id); p.className = 'pill ' + cls + (p.classList.contains('hide-sm') ? ' hide-sm' : ''); p.lastElementChild.textContent = text; }
  function toast(title, sub) {
    const el = document.createElement('div');
    el.className = 'toast danger';
    el.innerHTML = `<span class="ti">🛡️</span><div><b>${esc(title)}</b><small>${esc(sub)}</small></div>`;
    $('#toasts').appendChild(el);
    setTimeout(() => el.remove(), 4500);
    while ($('#toasts').children.length > 4) $('#toasts').firstChild.remove();
  }

  // ============================================================ tabs
  $$('#tabs button').forEach((b) => b.addEventListener('click', () => {
    $$('#tabs button').forEach((x) => x.classList.toggle('on', x === b));
    $$('.pane').forEach((p) => p.classList.toggle('on', p.id === 'p-' + b.dataset.p));
    if (b.dataset.p === 'packets') renderCapture(true);
  }));

  // ============================================================ admin connection + login
  let pwHash = ss.get('ng.ctl.pw');   // SHA-256(password) hex — the password itself is never stored
  let profile = null, unlocked = false;
  const net = new NG.SecureSocket({
    storageKey: 'ng.control',
    onClose: () => { pill('#pill-conn', 'bad', 'disconnected · retrying'); unlocked = false; setTimeout(start, 2000); },
  });

  async function start() {
    try {
      profile = await net.connect('control-room', 'dashboard');
      pill('#pill-conn', 'warn', 'locked · secure channel ready');
      if (pwHash) login(pwHash);
    } catch (e) {
      pill('#pill-conn', 'bad', 'server unreachable · retrying');
    }
  }
  net.on('profile', (p) => { profile = p; });

  function login(hashHex) {
    const proof = NG.hex(NG.hmac(NG.unhex(hashHex), enc.encode(profile.nonce)));
    net.send({ t: 'admin_login', proof });
  }

  $('#f-login').addEventListener('submit', (e) => {
    e.preventDefault();
    const pw = $('#in-pw').value;
    if (!pw || !profile) return;
    pwHash = NG.hex(NG.sha256(enc.encode(pw)));
    login(pwHash);
  });

  net.on('admin_ok', () => {
    unlocked = true;
    ss.set('ng.ctl.pw', pwHash);
    $('#login').hidden = true;
    $('#app').hidden = false;
    $('#btn-lock').hidden = false;
    $('#login-err').textContent = '';
    pill('#pill-conn', 'ok', 'unlocked · signed live stream');
    $('#f-url').dispatchEvent(new Event('submit'));
  });
  net.on('admin_fail', (m) => {
    ss.del('ng.ctl.pw');
    pwHash = null;
    $('#login-err').textContent = m.remaining > 0 ? `Wrong password · ${m.remaining} attempt${m.remaining === 1 ? '' : 's'} left` : 'Too many attempts — connection closed';
    $('#in-pw').value = '';
    $('#in-pw').focus();
  });
  $('#btn-lock').addEventListener('click', () => { ss.del('ng.ctl.pw'); location.reload(); });

  // ============================================================ stats rendering
  let seenSec = new Set();
  function spark(id, values, color) {
    const cv = $(id);
    if (!cv || !cv.offsetParent) return;
    const w = cv.clientWidth, h = cv.clientHeight, dpr = devicePixelRatio || 1;
    cv.width = w * dpr; cv.height = h * dpr;
    const ctx = cv.getContext('2d');
    ctx.scale(dpr, dpr);
    if (values.length < 2) return;
    const max = Math.max(1, ...values), step = w / (values.length - 1);
    ctx.beginPath();
    values.forEach((v, i) => { const x = i * step, y = h - 3 - (v / max) * (h - 6); i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
    ctx.strokeStyle = color; ctx.lineWidth = 1.8; ctx.shadowColor = color; ctx.shadowBlur = 8; ctx.stroke();
    ctx.lineTo(w, h); ctx.lineTo(0, h); ctx.closePath();
    const g = ctx.createLinearGradient(0, 0, 0, h); g.addColorStop(0, color + '55'); g.addColorStop(1, color + '00');
    ctx.shadowBlur = 0; ctx.fillStyle = g; ctx.fill();
  }
  function flow(id, active, rate) {
    const el = $(id);
    el.classList.toggle('idle', !active);
    el.style.animationDuration = active ? Math.max(0.25, 2.2 - Math.log10(1 + rate)) + 's' : '';
  }

  net.on('stats', (s) => {
    if (!unlocked) return;
    const c = s.connections, nio = s.nio, w = s.workers, g = s.game, udp = s.udp, h = s.history || [];
    pill('#pill-up', 'ok', 'uptime ' + age(s.uptimeSec));
    $('#srv-line').textContent = `${s.server} · selector thread "${s.selectorThread}" · ${w.poolSize} workers`;
    $('#c-conns').textContent = fmt(c.active);
    $('#c-threads').textContent = fmt(s.jvmThreads);
    $('#a-ws').textContent = `${c.websocket} connections`;
    $('#a-raw').textContent = `${c.raw} connections`;
    $('#a-udp').textContent = `${fmt(udp.requests)} datagrams in · ${fmt(udp.replies)} out`;
    $('#a-loops').textContent = fmt(nio.loopsPerSec);
    $('#a-rooms').textContent = `${fmt(g.rooms)} rooms`;
    $('#a-games').textContent = `${fmt(g.online)} online · ${g.playing} playing`;
    $('#a-work').textContent = `active ${w.active} · queued ${w.queued} · done ${fmt(w.completed)}`;
    let bars = '';
    for (let i = 0; i < w.poolSize; i++) bars += `<rect class="worker ${i < w.active ? 'busy' : ''}" x="${676 + i * 22}" y="62" width="16" height="22" rx="4"/>`;
    $('#a-workers').innerHTML = bars;
    flow('#f-ws', c.websocket > 0, nio.framesInPerSec);
    flow('#f-raw', c.raw > 0, nio.framesInPerSec);
    flow('#f-udp', (udp.recent || []).some((r) => Date.now() - r.ts < 4000), 10);
    flow('#f-work', w.active > 0 || w.queued > 0, 30);
    flow('#f-game', g.playing > 0, g.playing * 5);

    $('#t-in').textContent = kb(nio.bytesInPerSec); $('#t-in-s').textContent = `KB/s · total ${bytes(nio.bytesIn)}`;
    $('#t-out').textContent = kb(nio.bytesOutPerSec); $('#t-out-s').textContent = `KB/s · total ${bytes(nio.bytesOut)}`;
    $('#t-frames').textContent = fmt(nio.framesInPerSec + nio.framesOutPerSec); $('#t-frames-s').textContent = `${fmt(nio.framesInPerSec)} in · ${fmt(nio.framesOutPerSec)} out`;
    $('#t-conns').textContent = fmt(c.active); $('#t-conns-s').textContent = `ws ${c.websocket} · raw ${c.raw} · http ${c.http} · accepted ${fmt(c.accepted)}`;
    $('#t-compact').textContent = fmt(nio.compactions); $('#t-compact-s').textContent = `partial writes ${fmt(nio.partialWrites)} · keys/s ${fmt(nio.keysPerSec)}`;
    $('#t-pool').textContent = `${w.active}/${w.poolSize}`; $('#t-pool-s').textContent = `queued ${w.queued} · AI jobs ${fmt(w.aiJobs)} · DNS ${fmt(w.dnsLookups)}`;
    $('#t-hs').textContent = fmt(w.handshakes); $('#t-hs-s').textContent = `2048-bit modPow on workers · ${fmt(s.http.wsUpgrades)} WS upgrades`;
    $('#t-games').textContent = fmt(g.started); $('#t-games-s').textContent = `${g.playing} live · ${fmt(g.finished)} finished · ${g.queued} queued`;
    spark('#sp-in', h.map((x) => x[0]), '#22d3ee');
    spark('#sp-out', h.map((x) => x[1]), '#f472b6');
    spark('#sp-frames', h.map((x) => x[2] + x[3]), '#a78bfa');
    spark('#sp-conns', h.map((x) => x[5]), '#a3e635');

    $('#conn-count').textContent = c.active;
    $('#conn-body').innerHTML = (s.connList || []).map((x) => `<tr><td>${x.id}</td><td>${esc(x.kind)}</td><td><span class="mode ${x.mode}">${x.mode}</span>${x.secure ? ' 🔐' : ''}</td>
      <td>${esc(x.ip)}:${x.port}</td><td title="${esc(x.host)}">${esc(x.host || '…')}</td><td>${esc(x.name || '—')}</td>
      <td>${x.rtt >= 0 ? x.rtt + ' ms' : '—'}</td><td>${bytes(x.in)} / ${bytes(x.out)}</td><td>${age(x.ageSec)}</td></tr>`).join('');
    const rl = g.roomList || [];
    S.rooms = rl;
    $('#room-count').textContent = g.rooms;
    $('#room-body').innerHTML = rl.map((r) => `<tr><td style="color:var(--violet)">${esc(r.code)}</td><td>${r.mode}</td><td>${r.phase}</td>
      <td style="color:var(--x)">${esc(r.x || '—')}</td><td style="color:var(--o)">${esc(r.o || '—')}</td><td>${r.moves}</td><td>${r.spectators}</td>
      <td><a href="/?room=${encodeURIComponent(r.code)}&spectate=1" target="_blank">watch →</a></td></tr>`).join('') || '<tr><td colspan="8" class="muted">No rooms</td></tr>';

    const counts = s.security.counts || {};
    const total = Object.entries(counts).filter(([k]) => k !== 'ADMIN_LOGIN').reduce((a, [, b]) => a + b, 0);
    $('#sec-count').textContent = total ? fmt(total) + ' blocked' : '';
    $('#sec-bars').innerHTML = Object.entries(counts).sort((a, b) => b[1] - a[1]).map(([k, v]) => `<span>${esc(k)} · ${fmt(v)}</span>`).join('');
    const recent = s.security.recent || [];
    if (recent.length) {
      $('#sec-feed').innerHTML = recent.map((e) => `<div class="sec ${esc(e.kind)}"><span class="k">${esc(e.kind)}${e.n > 1 ? ` <span class="n">×${fmt(e.n)}</span>` : ''}</span><span class="t">${new Date(e.ts).toTimeString().slice(0, 8)}</span><span class="d" title="${esc(e.who + ' — ' + e.detail)}">${esc(e.who)} — ${esc(e.detail)}</span></div>`).join('');
      const nw = recent[0], id = nw.kind + nw.who + nw.detail;
      if (!seenSec.has(id) && seenSec.size && nw.kind !== 'RATE_LIMIT' && nw.kind !== 'ADMIN_LOGIN') toast(nw.kind + ' blocked', nw.who + ' — ' + nw.detail);
      seenSec.add(id);
    } else seenSec.add('init');

    $('#udp-port').textContent = udp.port;
    const ur = udp.recent || [];
    if (ur.length) $('#udp-list').innerHTML = ur.map((r) => `<div>${new Date(r.ts).toTimeString().slice(0, 8)} · query from <b>${esc(r.from)}</b> → beacon ${r.bytes} bytes</div>`).join('');

    const ai = s.aiRecent || [];
    if (ai.length) $('#ai-list').innerHTML = ai.map((a) => `<div class="ai-row"><span>${new Date(a.ts).toTimeString().slice(0, 8)}</span><span><b>${esc(a.algorithm)}</b> · ${fmt(a.iterations)} ${String(a.algorithm).startsWith('Monte') ? 'playouts' : 'nodes'} · ${a.millis} ms · ${esc(a.level)} · room ${esc(a.room)}</span><span style="color:var(--x)">${esc(a.thread)}</span></div>`).join('');
    $('#thread-kv').innerHTML = [
      ['I/O thread', `${s.selectorThread} (1 thread, all sockets)`], ['worker pool', `${w.poolSize} threads · ThreadPoolExecutor`],
      ['active / queued', `${w.active} / ${w.queued}`], ['tasks completed', fmt(w.completed)], ['AI jobs', fmt(w.aiJobs)],
      ['DH handshakes', fmt(w.handshakes)], ['DNS lookups', fmt(w.dnsLookups)], ['JVM threads total', s.jvmThreads], ['open sockets', c.active],
    ].map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join('');
    if (profile) {
      const sv = profile.server;
      $('#addr-kv').innerHTML = [['host name', sv.host], ['LAN IPv4', (sv.lan || []).join(', ')], ['TCP port', sv.tcpPort + ' (HTTP + WebSocket + NGA1)'], ['UDP port', sv.udpPort + ' (discovery)'], ['this console', sv.you], ['game URL', (sv.lan && sv.lan[0] ? 'http://' + sv.lan[0] + ':' + sv.tcpPort + '/' : location.origin)]]
        .map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join('');
    }
  });
  const S = { rooms: [] };

  // ============================================================ packet capture
  const cap = { rows: [], paused: false, sel: null };
  net.on('capture', (m) => {
    if (!unlocked) return;
    for (const it of m.items) cap.rows.push(it);
    if (cap.rows.length > 800) cap.rows.splice(0, cap.rows.length - 800);
    if (!cap.paused) renderCapture();
  });
  const isBad = (r) => r.status !== 'ok' && r.status !== 'plain';
  function visible(r) {
    if ($('#f-bots').checked && String(r.kind).startsWith('bot')) return false;
    if ($('#f-beats').checked && (r.type === 'ping' || r.type === 'pong') && !isBad(r)) return false;
    if ($('#f-bad').checked && !isBad(r)) return false;
    const q = $('#f-who').value.trim().toLowerCase();
    if (q && !String(r.who).toLowerCase().includes(q)) return false;
    return true;
  }
  function renderCapture(force) {
    if (!$('#p-packets').classList.contains('on') && !force) return;
    const rows = cap.rows.filter(visible).slice(-250);
    $('#cap-count').textContent = `${rows.length} shown · ${cap.rows.length} captured`;
    const sc = $('#cap-scroll');
    const atBottom = sc.scrollTop + sc.clientHeight >= sc.scrollHeight - 40;
    $('#cap-body').innerHTML = rows.map((r) => {
      const st = r.status === 'ok' ? '✓ verified' : r.status === 'plain' ? 'plain (handshake)' : '✗ ' + r.status;
      const cls = r.status === 'ok' ? 'ok' : r.status === 'plain' ? 'plain' : 'bad';
      return `<tr data-id="${r.id}" class="${cap.sel === r.id ? 'sel' : ''}"><td>${time(r.ts)}</td><td>#${r.conn}</td><td>${esc(r.who)}</td><td class="dir ${r.dir}">${r.dir === 'in' ? '⬇ in' : '⬆ out'}</td><td>${esc(r.type)}</td><td>${r.seq != null ? r.seq : '—'}</td><td>${r.bytes}</td><td>${r.mac ? esc(r.mac) + '…' : '—'}</td><td class="st ${cls}">${esc(st)}</td></tr>`;
    }).join('');
    if (atBottom) sc.scrollTop = sc.scrollHeight;
  }
  $('#cap-body').addEventListener('click', (e) => {
    const tr = e.target.closest('tr[data-id]');
    if (!tr) return;
    const r = cap.rows.find((x) => x.id === +tr.dataset.id);
    if (!r) return;
    cap.sel = r.id;
    $$('#cap-body tr').forEach((x) => x.classList.toggle('sel', x === tr));
    $('#cap-detail').innerHTML = `<div class="muted">${r.dir === 'in' ? 'client → server' : 'server → client'} · connection #${r.conn} (${esc(r.kind)}) · ${r.bytes} bytes</div>`
      + (r.mac ? `<div><span class="muted">HMAC-SHA256 (first 8 bytes): </span><span class="mac">${esc(r.mac)}…</span></div>` : '<div class="muted">unsigned handshake frame</div>')
      + `<div style="margin-top:6px">${esc(r.body)}</div>`;
  });
  ['#f-bots', '#f-beats', '#f-bad'].forEach((id) => $(id).addEventListener('change', () => renderCapture(true)));
  $('#f-who').addEventListener('input', () => renderCapture(true));
  $('#cap-pause').addEventListener('click', (e) => { cap.paused = !cap.paused; e.target.textContent = cap.paused ? 'Resume' : 'Pause'; if (!cap.paused) renderCapture(true); });
  $('#cap-clear').addEventListener('click', () => { cap.rows = []; renderCapture(true); });

  // ============================================================ tools
  $('#f-url').addEventListener('submit', (e) => { e.preventDefault(); if (unlocked) net.send({ t: 'url', u: $('#in-url').value }); });
  net.on('url_result', (a) => {
    if (!a.ok) { $('#out-url').innerHTML = `<span style="color:var(--red)">${esc(a.error)}</span>`; return; }
    const parts = [];
    if (a.scheme) parts.push(['scheme', a.scheme], ['plain', a.kind.startsWith('URN') ? ':' : '://']);
    if (a.userInfo) parts.push(['user', a.userInfo], ['plain', '@']);
    if (a.host) parts.push(['host', a.host]);
    if (a.port != null) parts.push(['plain', ':'], ['port', a.port + (a.portNote ? '*' : '')]);
    if (a.path) parts.push(['path', a.path]);
    if (a.query) parts.push(['plain', '?'], ['query', a.query]);
    if (a.fragment) parts.push(['plain', '#'], ['fragment', a.fragment]);
    let html = `<div class="anatomy">${parts.map(([k, v]) => `<span class="${k}" data-k="${k === 'plain' ? '' : k}">${esc(v)}</span>`).join('')}</div>`;
    html += `<div style="margin-top:8px">${esc(a.kind)}${a.portNote ? ' · *' + esc(a.portNote) : ''}</div>`;
    if (a.dnsHierarchy) { const d = a.dnsHierarchy; html += `<div class="muted">DNS: root <b>.</b> → TLD <b>.${esc(d.tld)}</b> → domain <b>${esc(d.domain)}</b>${d.subdomains.length ? ' → subdomain(s) <b>' + d.subdomains.map(esc).join('.') + '</b>' : ''}</div>`; }
    const pk = Object.keys(a.params || {});
    if (pk.length) html += `<div class="muted">params: ${pk.map((k) => esc(k) + ' = ' + esc(a.params[k])).join(' · ')}</div>`;
    $('#out-url').innerHTML = html;
  });
  $('#f-dns').addEventListener('submit', (e) => {
    e.preventDefault();
    if (!unlocked) return;
    $('#out-dns').innerHTML = '<span class="muted">resolving on a worker thread…</span>';
    net.send({ t: 'dns', host: $('#in-dns').value.trim() });
  });
  net.on('dns_result', (r) => {
    if (!r.ok) { $('#out-dns').innerHTML = `<span style="color:var(--red)">${esc(r.error)}</span>`; return; }
    $('#out-dns').innerHTML = r.addresses.map((a) => `<span class="ip">${esc(a.ip)} <span class="muted">${a.version}</span></span>`).join('')
      + `<div class="muted" style="margin-top:6px">${r.millis} ms on ${esc(r.resolvedOn)} · reverse: ${esc(r.reverse)}</div>`;
  });

  // ============================================================ attack lab (separate attacker connection)
  const STAGES = ['tcp', 'upgrade', 'dh', 'key', 'secure'];
  let hs0 = 0;
  function onStage(stage, text, ms) {
    const idx = STAGES.indexOf(stage);
    $$('#hs-steps li').forEach((li, i) => { li.classList.toggle('done', i < idx || (stage === 'secure' && i === idx)); li.classList.toggle('active', i === idx && stage !== 'secure'); });
    const li = $(`#hs-steps li[data-s="${stage}"]`);
    if (li && text) li.querySelector('.sub').textContent = text;
    if (li && ms != null) li.querySelector('.ms').textContent = ms.toFixed(0) + ' ms';
    if (stage === 'tcp') hs0 = performance.now();
    if (stage === 'secure') $('#hs-total').textContent = (performance.now() - hs0).toFixed(0) + ' ms total';
  }
  const atk = new NG.SecureSocket({ storageKey: 'ng.attacker', onStage, onClose: () => { $('#lab-state').textContent = 'attacker offline'; } });
  function labLog(kind, html) {
    const l = document.createElement('div');
    l.className = 'l ' + (kind || '');
    l.innerHTML = html;
    const log = $('#lab-log');
    log.appendChild(l);
    while (log.children.length > 90) log.firstChild.remove();
    log.scrollTop = log.scrollHeight;
  }
  atk.on('rejected', (m) => labLog('def', `<b>🛡 server rejected:</b> ${esc(m.reason)} — ${esc(m.detail)}`));
  atk.on('error', (m) => labLog('def', `<b>🛡 server validation:</b> ${esc(m.code)} — ${esc(m.msg)}`));
  atk.on('admin_fail', (m) => labLog('def', `<b>🛡 login refused:</b> wrong password · ${m.remaining} attempts left before the server drops the connection`));
  atk.on('room', (r) => { atk.room = r; });

  async function ensureAttacker() {
    if (atk.key && atk.ws && atk.ws.readyState === 1) return true;
    try {
      ss.del('ng.attacker');
      const p = await atk.connect('Mallory', 'lab');
      atk.profile = p;
      $('#lab-state').textContent = 'attacker online · conn #' + p.server.connId;
      labLog('', `Attacker connected as <b>Mallory</b> (conn #${p.server.connId}) · key fingerprint ${esc(atk.fingerprint)}`);
      return true;
    } catch (e) { labLog('atk', 'Could not connect the attacker: ' + esc(e.message)); return false; }
  }
  const short = (s, n = 90) => esc(s.length > n ? s.slice(0, n) + '…' : s);
  $$('[data-atk]').forEach((b) => b.addEventListener('click', () => attack(b.dataset.atk)));

  async function attack(kind) {
    if (kind === 'path') {
      labLog('atk', '<b>⚠ path traversal:</b> GET /..%2fbuild.ps1 (trying to read a file outside the web folder)');
      const r = await fetch('/..%2fbuild.ps1').catch(() => null);
      labLog('def', `<b>🛡 server answered:</b> HTTP ${r ? r.status + ' ' + r.statusText : 'error'}`);
      return;
    }
    if (!(await ensureAttacker())) return;
    if (kind === 'connect') return;
    if (!atk.lastFrame) atk.send({ t: 'chat', text: 'hello from Mallory' });
    if (kind === 'replay') {
      const f = atk.lastByType.move || atk.lastByType.chat || atk.lastFrame;
      const body = JSON.parse(f.slice(64));
      labLog('atk', `<b>⚠ replay:</b> resending captured <i>${esc(body.t)}</i> frame seq #${body.seq} (MAC ${esc(f.slice(0, 16))}…) byte for byte`);
      atk.sendRaw(f, 'REPLAY');
    } else if (kind === 'tamper') {
      const t = atk.forgeTampered({ t: 'move', cell: 4 }, (o) => { o.cell = 8; });
      labLog('atk', `<b>⚠ MITM:</b> signed <span class="mono">${short(t.original)}</span>`);
      labLog('atk', `… changed in transit to <span class="mono">${short(t.changed)}</span> (MAC left unchanged)`);
      atk.sendRaw(t.frame, 'TAMPER');
    } else if (kind === 'forge') {
      const f = atk.forgeWithoutKey({ t: 'move', cell: 0 });
      labLog('atk', `<b>⚠ forgery:</b> frame signed with a guessed 256-bit key → MAC ${esc(f.slice(0, 16))}…`);
      atk.sendRaw(f, 'FORGED');
    } else if (kind === 'flood') {
      const t0 = performance.now();
      for (let i = 0; i < 150; i++) atk.send({ t: 'ping', ts: performance.now(), flood: i });
      labLog('atk', `<b>⚠ flood:</b> 150 signed messages in ${(performance.now() - t0).toFixed(0)} ms (limit: 40 burst, 20 per second)`);
    } else if (kind === 'cheat') {
      const live = (S.rooms || []).find((r) => r.phase === 'PLAYING');
      if (live && (!atk.room || atk.room.code !== live.code)) {
        labLog('atk', `Mallory joins live room <b>${esc(live.code)}</b> as a spectator…`);
        atk.send({ t: 'spectate', code: live.code });
        await new Promise((r) => setTimeout(r, 500));
      }
      const cell = atk.room ? Math.max(0, atk.room.cells.findIndex((v) => v === 0)) : 0;
      labLog('atk', `<b>⚠ cheat:</b> correctly signed move (cell ${cell}) from a player who is not allowed to move${atk.room ? ' (a spectator in room ' + esc(atk.room.code) + ')' : ' (not in any room)'}`);
      atk.send({ t: 'move', cell });
    } else if (kind === 'priv') {
      labLog('atk', '<b>⚠ privilege escalation:</b> Mallory requests admin statistics and a DNS lookup without logging in');
      atk.send({ t: 'stats' });
      atk.send({ t: 'dns', host: 'www.google.com' });
    } else if (kind === 'badpw') {
      const guess = ['admin', '123456', 'password', 'neongrid'][Math.floor(Math.random() * 4)];
      const proof = NG.hex(NG.hmac(NG.sha256(enc.encode(guess)), enc.encode(atk.profile.nonce)));
      labLog('atk', `<b>⚠ guessing:</b> control-room login with password "${esc(guess)}" → proof ${esc(proof.slice(0, 16))}…`);
      atk.send({ t: 'admin_login', proof });
    }
  }

  start();
})();
