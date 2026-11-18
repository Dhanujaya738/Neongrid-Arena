/* NeonGrid — game client (player-facing). All networking lives in net.js. */
(function () {
  'use strict';

  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const hue = (h) => `hsl(${h} 85% 68%)`;
  const params = new URLSearchParams(location.search);
  const LEVEL = { easy: 'Rookie', medium: 'Pro', hard: 'Legend' };
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } },
  };

  const S = {
    me: null,
    mode: store.get('ng.mode') || 'ultimate',
    room: null,
    lobby: null,
    prevCells: null,
    prevMacro: null,
    builtFor: null,
    deadline: 0,
    sound: store.get('ng.sound') !== 'off',
    wantRoom: (params.get('room') || '').toUpperCase().replace(/[^A-Z0-9]/g, '') || null,
    wantSpectate: params.get('spectate') === '1',
    shownResult: null,
    hover: -1,
    unread: 0,
    qrFor: null,
    leaveArmed: 0,
    lastReact: 0,
    retry: 0,
  };

  // ================================================================ background sky
  (function sky() {
    const cv = $('#sky'), ctx = cv.getContext('2d');
    let w, h, pts;
    const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
    function size() {
      const d = devicePixelRatio || 1;
      w = innerWidth; h = innerHeight; cv.width = w * d; cv.height = h * d; ctx.setTransform(d, 0, 0, d, 0, 0);
      const n = Math.min(90, Math.round(w * h / 16000));
      pts = Array.from({ length: n }, () => ({ x: Math.random() * w, y: Math.random() * h, vx: (Math.random() - .5) * .25, vy: (Math.random() - .5) * .25, r: Math.random() * 1.6 + .4, t: Math.random() * 6 }));
    }
    function frame() {
      ctx.clearRect(0, 0, w, h);
      for (const p of pts) {
        p.x = (p.x + p.vx + w) % w; p.y = (p.y + p.vy + h) % h; p.t += .02;
        ctx.globalAlpha = .35 + Math.sin(p.t) * .25;
        ctx.fillStyle = '#c7d2fe';
        ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, 7); ctx.fill();
      }
      ctx.globalAlpha = 1;
      ctx.lineWidth = .6;
      for (let i = 0; i < pts.length; i++) for (let j = i + 1; j < pts.length; j++) {
        const a = pts[i], b = pts[j], dx = a.x - b.x, dy = a.y - b.y, d2 = dx * dx + dy * dy;
        if (d2 < 11000) { ctx.strokeStyle = `rgba(129,140,248,${.16 * (1 - d2 / 11000)})`; ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke(); }
      }
      if (!reduce) requestAnimationFrame(frame);
    }
    addEventListener('resize', size);
    size(); frame();
  })();

  // ================================================================ sound
  const Sound = {
    ctx: null,
    tone(f, d = .12, type = 'sine', v = .05, delay = 0) {
      if (!S.sound) return;
      try {
        this.ctx = this.ctx || new (window.AudioContext || window.webkitAudioContext)();
        const t = this.ctx.currentTime + delay, o = this.ctx.createOscillator(), g = this.ctx.createGain();
        o.type = type; o.frequency.setValueAtTime(f, t);
        g.gain.setValueAtTime(v, t); g.gain.exponentialRampToValueAtTime(.0001, t + d);
        o.connect(g).connect(this.ctx.destination); o.start(t); o.stop(t + d + .03);
      } catch (e) { /* no audio */ }
    },
    place(side) { side === 1 ? (this.tone(660, .08, 'triangle'), this.tone(880, .1, 'triangle', .035, .05)) : (this.tone(440, .08, 'triangle'), this.tone(330, .12, 'triangle', .035, .05)); },
    claim() { [523, 784].forEach((f, i) => this.tone(f, .18, 'triangle', .05, i * .08)); },
    win() { [523, 659, 784, 1046, 1318].forEach((f, i) => this.tone(f, .25, 'triangle', .06, i * .09)); },
    lose() { [392, 330, 262].forEach((f, i) => this.tone(f, .3, 'sawtooth', .022, i * .13)); },
    turn() { this.tone(988, .07, 'sine', .035); },
    nope() { this.tone(160, .14, 'square', .025); },
    join() { this.tone(880, .08, 'sine', .04); this.tone(1320, .12, 'sine', .03, .08); },
  };

  // ================================================================ toasts
  function toast(text, kind, icon) {
    const el = document.createElement('div');
    el.className = 'toast ' + (kind || '');
    el.innerHTML = `<span>${icon || (kind === 'warn' ? '⚠️' : kind === 'good' ? '✨' : '💬')}</span><span>${esc(text)}</span>`;
    $('#toasts').appendChild(el);
    setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 300); }, 2800);
    while ($('#toasts').children.length > 3) $('#toasts').firstChild.remove();
  }

  // ================================================================ views & overlays
  function show(name) {
    $$('.view').forEach((v) => v.classList.toggle('on', v.id === 'v-' + name));
    $('#me').hidden = name === 'splash' || !S.me;
    if (name !== 'game') closeChat();
    window.scrollTo(0, 0);
  }
  const current = () => ($('.view.on') || {}).id;
  function overlay(id, on) { $('#' + id).classList.toggle('on', on); }

  // ================================================================ splash hero animation
  const SX = (anim, cls = '') => `<svg viewBox="0 0 100 100" class="${cls}"><path class="piece x ${anim ? 'draw' : ''}" d="M24 24 L76 76"/><path class="piece x ${anim ? 'draw d2' : ''}" d="M76 24 L24 76"/></svg>`;
  const SO = (anim, cls = '') => `<svg viewBox="0 0 100 100" class="${cls}"><circle class="piece o ${anim ? 'draw' : ''}" cx="50" cy="50" r="27" transform="rotate(-90 50 50)"/></svg>`;
  (function hero() {
    const grid = $('#hero-grid');
    grid.innerHTML = '<span></span>'.repeat(9);
    const cells = $$('span', grid);
    const seq = [[4, 1], [1, 2], [0, 1], [2, 2], [8, 1]];
    let i = 0;
    function step() {
      if (!$('#v-splash').classList.contains('on')) return setTimeout(step, 1000);
      if (i < seq.length) {
        const [c, p] = seq[i++];
        cells[c].innerHTML = p === 1 ? SX(true) : SO(true);
        setTimeout(step, 650);
      } else if (i === seq.length) {
        [0, 4, 8].forEach((c) => cells[c].classList.add('win'));
        i++;
        setTimeout(step, 1800);
      } else {
        cells.forEach((c) => { c.innerHTML = ''; c.classList.remove('win'); });
        i = 0;
        setTimeout(step, 500);
      }
    }
    setTimeout(step, 600);
  })();

  // ================================================================ network
  const net = new NG.SecureSocket({ onClose });

  async function connect(name) {
    const btn = $('#btn-play');
    btn.disabled = true;
    btn.firstElementChild.textContent = 'JOINING…';
    try {
      await net.connect(name, 'web');
      S.retry = 0;
    } catch (e) {
      if (!S.me) toast("Can't reach the game server. Is it running?", 'warn', '📡');
      throw e;
    } finally {
      btn.disabled = false;
      btn.firstElementChild.textContent = 'PLAY';
    }
  }

  function onClose() {
    $('#net-dot').classList.add('bad');
    if (!S.me) return;
    overlay('ov-reconnect', true);
    S.retry++;
    setTimeout(async () => {
      try {
        await connect(S.me.name);
        overlay('ov-reconnect', false);
        $('#net-dot').classList.remove('bad');
        toast('Back online!', 'good', '⚡');
      } catch (e) { /* onClose schedules the next attempt */ }
    }, Math.min(5000, 700 * S.retry));
  }

  net.on('profile', (p) => {
    S.me = p;
    store.set('ng.name', p.name);
    $('#net-dot').classList.remove('bad');
    $('#me-name').textContent = p.name;
    $('#me-elo').textContent = p.elo;
    const av = $('#me-av');
    av.textContent = p.name.charAt(0).toUpperCase();
    av.style.background = hue(p.hue);
    $('#h-name').textContent = p.name;
    $('#search-av').textContent = av.textContent;
    $('#search-av').style.background = hue(p.hue);
    if (S.wantRoom) {
      net.send({ t: S.wantSpectate ? 'spectate' : 'join', code: S.wantRoom });
      S.wantRoom = null;
    } else if (!p.resumed || !S.room) {
      setTimeout(() => { if (!S.room) show('home'); }, p.resumed ? 0 : 150);
    }
  });

  net.on('lobby', (m) => {
    S.lobby = m;
    renderHome();
    if (!S.room && current() !== 'v-home' && S.me && current() !== 'v-splash') show('home');
  });

  net.on('queue', (m) => {
    overlay('ov-search', !!m.waiting);
    if (m.matched) { Sound.join(); toast('Opponent found — good luck!', 'good', '⚔️'); }
  });

  net.on('room', onRoom);
  net.on('chat_history', (m) => { $('#chat-log').innerHTML = ''; m.items.forEach((it) => addChat(it, true)); });
  net.on('chat', (m) => addChat(m.item));
  net.on('react', (m) => floatEmoji(m.emoji, m.side));
  net.on('thinking', () => { S.botThinking = true; renderTurn(S.room); });
  net.on('error', (m) => { Sound.nope(); toast(friendly(m), 'warn'); });
  net.on('rejected', () => { Sound.nope(); toast('That action was blocked', 'warn', '🛡️'); });
  net.on('room_closed', () => {
    S.room = null;
    S.builtFor = null;
    overlay('ov-result', false);
    history.replaceState(null, '', '/');
    toast('The room was closed', '', '🚪');
    show('home');
    net.send({ t: 'lobby' });
  });

  function friendly(m) {
    switch (m.code) {
      case 'NOT_YOUR_TURN': return "Hold on — it's not your turn yet";
      case 'ILLEGAL_MOVE': return m.msg && m.msg.indexOf('mini-board') >= 0 ? 'Play inside the glowing board' : m.msg || "You can't play there";
      case 'SPECTATOR': return "You're watching this game";
      case 'NO_ROOM': return m.msg || "That room doesn't exist";
      case 'OPPONENT_LEFT': return 'Your opponent has left';
      default: return m.msg || 'Something went wrong';
    }
  }

  // ================================================================ home
  function renderHome() {
    const m = S.lobby;
    if (!m) return;
    $('#online-line').textContent = `${m.online} player${m.online === 1 ? '' : 's'} online`;
    const lb = (m.leaderboard || []).slice(0, 6);
    const medal = ['🥇', '🥈', '🥉'];
    $('#lb').innerHTML = lb.length ? lb.map((e, i) => `<li class="${S.me && e.name === S.me.name ? 'me' : ''}"><span class="rk">${medal[i] || e.rank}</span><span class="nm">${esc(e.name)}</span><span class="pt">${e.elo}</span></li>`).join('')
      : '<div class="empty">Win a match against a friend to be the first legend here.</div>';
    if (S.me) { const mine = lb.find((e) => e.name === S.me.name); if (mine) $('#me-elo').textContent = mine.elo; }
    const rooms = (m.rooms || []).filter((r) => r.phase !== 'FINISHED').slice(0, 5);
    $('#live').innerHTML = rooms.length ? rooms.map((r) => `<div class="live-row"><div class="vs"><span class="x">${esc(botName(r.x))}</span> vs <span class="o">${esc(botName(r.o) || '…')}</span><small>${r.mode === 'ultimate' ? 'Ultimate' : 'Classic'} · ${r.phase === 'WAITING' ? 'waiting for player' : 'move ' + r.moves}</small></div><button data-room="${esc(r.code)}" data-act="${r.phase === 'WAITING' ? 'join' : 'spectate'}">${r.phase === 'WAITING' ? 'Join' : 'Watch'}</button></div>`).join('')
      : '<div class="empty">No live games right now — start one!</div>';
  }
  const botName = (n) => (n && n.indexOf('NeonBot') === 0 ? 'NeonBot' : n);

  $('#live').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-room]');
    if (b) net.send({ t: b.dataset.act, code: b.dataset.room });
  });
  $$('#modes .mode').forEach((b) => {
    b.classList.toggle('on', b.dataset.mode === S.mode);
    b.addEventListener('click', () => {
      S.mode = b.dataset.mode;
      store.set('ng.mode', S.mode);
      $$('#modes .mode').forEach((x) => x.classList.toggle('on', x === b));
      Sound.tone(720, .05);
    });
  });
  $('#a-quick').addEventListener('click', () => { maybeTutorial(); net.send({ t: 'quick', mode: S.mode }); });
  $$('[data-ai]').forEach((b) => b.addEventListener('click', () => { maybeTutorial(); net.send({ t: 'ai', mode: S.mode, level: b.dataset.ai }); }));
  $('#a-create').addEventListener('click', () => net.send({ t: 'create', mode: S.mode, private: true }));
  $('#f-join').addEventListener('submit', (e) => {
    e.preventDefault();
    const code = $('#in-code').value.trim().toUpperCase();
    if (code.length !== 4) return toast('Room codes have 4 letters', 'warn');
    net.send({ t: 'join', code });
    $('#in-code').value = '';
  });
  $('#search-cancel').addEventListener('click', () => { net.send({ t: 'cancel' }); overlay('ov-search', false); });
  $('#search-bot').addEventListener('click', () => { net.send({ t: 'cancel' }); net.send({ t: 'ai', mode: S.mode, level: 'medium' }); overlay('ov-search', false); });
  $('#brand').addEventListener('click', () => { if (S.me && !S.room) show('home'); });

  // ================================================================ game: board
  function buildBoard(r) {
    const b = $('#board');
    b.innerHTML = '';
    b.className = 'board ' + r.mode;
    if (r.mode === 'classic') {
      for (let g = 0; g < 9; g++) b.appendChild(cell(g));
    } else {
      for (let m = 0; m < 9; m++) {
        const mini = document.createElement('div');
        mini.className = 'mini';
        mini.dataset.b = m;
        for (let i = 0; i < 9; i++) mini.appendChild(cell(m * 9 + i));
        const big = document.createElement('div');
        big.className = 'big';
        mini.appendChild(big);
        b.appendChild(mini);
      }
    }
    S.builtFor = r.code + ':' + r.mode + ':' + r.gameNo;
    S.prevCells = null;
    S.prevMacro = null;
    $('#win-line').innerHTML = '';
  }

  function cell(g) {
    const c = document.createElement('button');
    c.className = 'cell';
    c.dataset.g = g;
    c.setAttribute('aria-label', 'cell ' + (g + 1));
    c.addEventListener('click', () => {
      if (!c.classList.contains('can') || S.pending) return;
      S.pending = true;
      net.send({ t: 'move', cell: g });
      setTimeout(() => { S.pending = false; }, 400);
    });
    c.addEventListener('mouseenter', () => { S.hover = g; preview(); });
    c.addEventListener('mouseleave', () => { if (S.hover === g) { S.hover = -1; preview(); } });
    return c;
  }

  function onRoom(r) {
    const prev = S.room;
    S.room = r;
    S.pending = false;
    S.botThinking = false;
    overlay('ov-search', false);
    if (current() !== 'v-game') { show('game'); Sound.join(); }
    const key = r.code + ':' + r.mode + ':' + r.gameNo;
    if (S.builtFor !== key) {
      buildBoard(r);
      overlay('ov-result', false);
    }
    history.replaceState(null, '', '/?room=' + r.code);
    S.deadline = r.deadlineIn != null ? performance.now() + r.deadlineIn : 0;
    renderBoard(r);
    renderPlayers(r);
    renderTurn(r, prev);
    renderWaiting(r);
    preview();
    if (prev && prev.code === r.code && prev.gameNo === r.gameNo && r.moveCount > prev.moveCount) {
      Sound.place(r.cells[r.lastMove]);
      if (r.phase === 'PLAYING' && r.you > 0 && r.turn === r.you) setTimeout(() => Sound.turn(), 250);
    }
    if (r.phase === 'FINISHED' && S.shownResult !== key) {
      S.shownResult = key;
      setTimeout(() => showResult(S.room), 1100);
    }
    if (r.phase === 'FINISHED' && $('#ov-result').classList.contains('on')) updateResultButtons(r);
  }

  function playableBoard(r, b) {
    return r.macro[b] === 0 && (r.active === -1 || r.active === b);
  }

  function renderBoard(r) {
    const board = $('#board');
    const playing = r.phase === 'PLAYING' && r.winner === 0;
    const myTurn = playing && r.you > 0 && r.turn === r.you;
    const ult = r.mode === 'ultimate';
    board.classList.toggle('playing', playing);
    board.classList.toggle('turn-x', playing && r.turn === 1);
    board.classList.toggle('turn-o', playing && r.turn === 2);
    const prev = S.prevCells;
    $$('.cell', board).forEach((c) => {
      const g = +c.dataset.g, v = r.cells[g];
      const can = myTurn && v === 0 && (!ult || playableBoard(r, Math.floor(g / 9)));
      const changed = !prev || prev[g] !== v;
      const ghostKey = String(can ? r.you : 0);
      if (changed || c.dataset.ghost !== ghostKey) {
        const fresh = !!prev && changed && v !== 0;
        if (v === 1) c.innerHTML = SX(fresh, fresh ? 'placed' : '');
        else if (v === 2) c.innerHTML = SO(fresh, fresh ? 'placed' : '');
        else c.innerHTML = can ? `<span class="ghost">${r.you === 1 ? SX(false) : SO(false)}</span>` : '';
        if (fresh) { const rp = document.createElement('span'); rp.className = 'ripple ' + (v === 1 ? 'x' : 'o'); c.appendChild(rp); }
        c.dataset.ghost = ghostKey;
      }
      c.classList.toggle('can', can);
      c.disabled = !can;
      c.classList.toggle('last', g === r.lastMove && r.moveCount > 0);
    });
    if (ult) {
      $$('.mini', board).forEach((mini) => {
        const b = +mini.dataset.b, mv = r.macro[b];
        const pl = playing && playableBoard(r, b);
        mini.classList.toggle('playable', pl);
        mini.classList.toggle('dim', playing && !pl && mv === 0);
        mini.classList.toggle('won-x', mv === 1);
        mini.classList.toggle('won-o', mv === 2);
        mini.classList.toggle('drawn', mv === 3);
        if (String(mv) !== mini.dataset.state) {
          const fresh = !!S.prevMacro;
          mini.querySelector('.big').innerHTML = mv === 1 ? SX(fresh) : mv === 2 ? SO(fresh) : '';
          if (fresh && (mv === 1 || mv === 2)) { mini.classList.remove('claimed'); void mini.offsetWidth; mini.classList.add('claimed'); Sound.claim(); }
          mini.dataset.state = String(mv);
        }
      });
      S.prevMacro = r.macro.slice();
    }
    S.prevCells = r.cells.slice();

    const wl = $('#win-line');
    if ((r.winner === 1 || r.winner === 2) && r.winLine && !wl.innerHTML) {
      const ctr = (i) => [(i % 3) * 100 + 50, Math.floor(i / 3) * 100 + 50];
      const a = ctr(r.winLine[0]), b = ctr(r.winLine[2]);
      const dx = (b[0] - a[0]) * .2, dy = (b[1] - a[1]) * .2;
      const col = r.winner === 1 ? '#22d3ee' : '#f472b6';
      wl.innerHTML = `<line x1="${a[0] - dx}" y1="${a[1] - dy}" x2="${b[0] + dx}" y2="${b[1] + dy}" stroke="#fff" style="filter:drop-shadow(0 0 10px ${col}) drop-shadow(0 0 4px ${col})"/>`;
    } else if (r.winner === 0) {
      wl.innerHTML = '';
    }
  }

  /** Hover preview: show which board this move sends the opponent to. */
  function preview() {
    const r = S.room;
    $$('.mini.target').forEach((m) => m.classList.remove('target'));
    if (!r || r.mode !== 'ultimate' || S.hover < 0) return renderHint(r);
    const c = $(`.cell[data-g="${S.hover}"]`);
    if (!c || !c.classList.contains('can')) return renderHint(r);
    const target = S.hover % 9;
    // would this move decide its own mini-board? (then a free move might follow)
    const opp = r.players[3 - r.you];
    const who = opp ? (opp.bot ? 'NeonBot' : opp.name) : 'your opponent';
    if (r.macro[target] === 0 && !(target === Math.floor(S.hover / 9) && wouldClose(r, S.hover))) {
      const mini = $(`.mini[data-b="${target}"]`);
      mini.dataset.tip = '→ ' + who + ' plays here';
      mini.classList.add('target');
      setHint(`This move sends <b>${esc(who)}</b> to the highlighted board`);
    } else {
      setHint(`⚠ This move gives <b>${esc(who)}</b> a free move anywhere`, true);
    }
  }

  function wouldClose(r, g) {
    // true if playing g fills/wins its own mini-board (so it is no longer open afterwards)
    const b = Math.floor(g / 9), cells = r.cells.slice(b * 9, b * 9 + 9);
    cells[g % 9] = r.you;
    const L = [[0, 1, 2], [3, 4, 5], [6, 7, 8], [0, 3, 6], [1, 4, 7], [2, 5, 8], [0, 4, 8], [2, 4, 6]];
    return L.some((l) => cells[l[0]] === r.you && cells[l[1]] === r.you && cells[l[2]] === r.you) || cells.every((v) => v !== 0);
  }

  function setHint(html, warn) { const h = $('#hint'); h.innerHTML = html; h.classList.toggle('warn', !!warn); }

  function renderHint(r) {
    if (!r) return setHint('');
    const opp = r.players[3 - r.you];
    const oppName = opp ? (opp.bot ? 'NeonBot' : esc(opp.name)) : '';
    if (r.phase === 'WAITING') return setHint('');
    if (r.phase === 'FINISHED') {
      return setHint(r.you > 0 ? '<button class="mini-btn go" id="hint-again">🔁 Play again</button>' : 'Game over');
    }
    if (r.you === 0) return setHint('👀 You are watching this game');
    if (r.turn !== r.you) return setHint(opp && opp.bot ? 'NeonBot is planning its move…' : `Waiting for <b>${oppName}</b>…`);
    if (r.mode === 'classic') return setHint('Get <b>three in a row</b> to win');
    if (r.active === -1) return setHint('✨ <b>Free move!</b> Play in any glowing board');
    return setHint('Play inside the <b>glowing board</b>');
  }
  $('#hint').addEventListener('click', (e) => { if (e.target.id === 'hint-again') rematch(); });

  function renderPlayers(r) {
    [1, 2].forEach((side) => {
      const el = $('#p' + side), p = r.players[side];
      const name = p ? (p.bot ? 'NeonBot' : p.name) : 'Waiting…';
      el.querySelector('.pname').textContent = name;
      const sub = el.querySelector('.psub');
      if (!p) sub.textContent = 'open seat';
      else if (p.bot) sub.textContent = '🤖 ' + (LEVEL[p.level] || 'Bot');
      else sub.innerHTML = (r.you === side ? '<span class="you">YOU</span> · ' : '') + (p.online ? '★ ' + p.elo : '📡 reconnecting');
      const av = el.querySelector('.av');
      av.textContent = p ? (p.bot ? '🤖' : p.name.charAt(0).toUpperCase()) : '?';
      av.style.background = p ? hue(p.hue) : 'rgba(255,255,255,.1)';
      el.classList.toggle('offline', !!p && !p.online);
      el.classList.toggle('turn', r.phase === 'PLAYING' && r.turn === side);
    });
  }

  function renderTurn(r, prev) {
    if (!r) return;
    const main = $('#turn-main'), sub = $('#turn-sub');
    let text = '', cls = '';
    const p = r.players[r.turn];
    if (r.phase === 'WAITING') text = 'WAITING';
    else if (r.phase === 'FINISHED') text = 'GAME OVER';
    else if (r.you > 0 && r.turn === r.you) { text = 'YOUR TURN'; cls = r.you === 1 ? 'x' : 'o'; }
    else { text = p ? ((p.bot ? 'NEONBOT' : p.name.toUpperCase().slice(0, 10)) + "'S TURN") : ''; cls = r.turn === 1 ? 'x' : 'o'; }
    if (main.textContent !== text) { main.textContent = text; main.classList.remove('pop'); void main.offsetWidth; main.classList.add('pop'); }
    main.className = 'turn-main pop ' + cls;
    if (r.phase === 'PLAYING' && p && p.bot) sub.innerHTML = '<span class="thinking"><i></i><i></i><i></i></span> thinking';
    else if (r.phase === 'PLAYING') sub.textContent = '';
    else if (r.phase === 'WAITING') sub.textContent = 'for your friend';
    else sub.textContent = r.winner === 3 ? "It's a draw" : (r.players[r.winner] ? (r.players[r.winner].bot ? 'NeonBot' : r.players[r.winner].name) + ' wins' : '');
  }

  function renderWaiting(r) {
    const on = r.phase === 'WAITING' && r.you > 0;
    $('#waiting').classList.toggle('on', on);
    $('#reacts').style.visibility = on ? 'hidden' : '';
    if (!on) return;
    $('#code-tiles').innerHTML = r.code.split('').map((ch) => `<span>${esc(ch)}</span>`).join('');
    const link = (r.invite && r.invite[0]) || location.origin + '/r/' + r.code;
    $('#invite-link').value = link;
    $('#btn-share').hidden = !navigator.share;
    if (S.qrFor !== link) {
      S.qrFor = link;
      const q = $('#qr');
      q.innerHTML = '';
      const draw = () => { try { new window.QRCode(q, { text: link, width: 116, height: 116, colorDark: '#060714', colorLight: '#ffffff' }); } catch (e) { q.innerHTML = ''; } };
      if (window.QRCode) draw(); else setTimeout(() => { if (window.QRCode && S.qrFor === link) draw(); }, 1200);
    }
  }
  $('#btn-copy').addEventListener('click', async () => {
    const inp = $('#invite-link');
    try { await navigator.clipboard.writeText(inp.value); } catch (e) { inp.select(); document.execCommand('copy'); }
    toast('Invite link copied!', 'good', '🔗');
  });
  $('#btn-share').addEventListener('click', () => {
    if (S.room) navigator.share({ title: 'NeonGrid', text: 'Play Ultimate Tic-Tac-Toe with me! Room ' + S.room.code, url: $('#invite-link').value }).catch(() => {});
  });

  // turn clock
  (function clock() {
    const r = S.room;
    if (r && current() === 'v-game') {
      [1, 2].forEach((side) => {
        const ring = $('#p' + side + ' .ring');
        if (r.phase === 'PLAYING' && r.turn === side && S.deadline) {
          const rem = Math.max(0, S.deadline - performance.now());
          ring.style.setProperty('--p', (rem / r.turnLimit) * 100);
          ring.classList.toggle('low', rem < 8000);
          const s = Math.ceil(rem / 1000);
          if (!(r.players[side] && r.players[side].bot)) $('#turn-sub').textContent = `⏱ ${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
        } else {
          ring.style.setProperty('--p', 0);
          ring.classList.remove('low');
        }
      });
    }
    requestAnimationFrame(clock);
  })();

  // ================================================================ result
  function showResult(r) {
    if (!r || r.phase !== 'FINISHED') return;
    const you = r.you, title = $('#r-title'), em = $('#r-emblem'), card = $('#ov-result .result');
    const reason = { line: 'Three in a row!', draw: 'Nobody could break through', timeout: 'Ran out of time', resign: 'Opponent left the game', disconnect: 'Opponent disconnected' }[r.endReason] || '';
    let sub = reason;
    if (r.winner === 3) { em.textContent = '🤝'; title.textContent = 'DRAW'; title.className = 'r-title draw'; card.style.setProperty('--rc', 'rgba(139,92,246,.6)'); }
    else if (you === 0) { em.textContent = '🏁'; const w = r.players[r.winner]; title.textContent = ((w && (w.bot ? 'NEONBOT' : w.name.toUpperCase())) || '') + ' WINS'; title.className = 'r-title win'; card.style.setProperty('--rc', 'rgba(34,211,238,.5)'); }
    else if (r.winner === you) { em.textContent = '🏆'; title.textContent = 'VICTORY'; title.className = 'r-title win'; card.style.setProperty('--rc', 'rgba(251,191,36,.6)'); confetti(); Sound.win(); }
    else { em.textContent = r.endReason === 'timeout' ? '⏱️' : '💥'; title.textContent = 'DEFEAT'; title.className = 'r-title lose'; card.style.setProperty('--rc', 'rgba(251,113,133,.5)'); Sound.lose(); if (r.endReason === 'timeout') sub = 'You ran out of time'; }
    $('#r-sub').textContent = sub;
    const elo = $('#r-elo');
    elo.className = 'r-elo';
    elo.textContent = '';
    if (r.ratingDelta && you > 0) {
      const d = r.ratingDelta[you - 1];
      elo.textContent = `${d >= 0 ? '+' : ''}${d} rating`;
      elo.classList.add(d >= 0 ? 'up' : 'down');
      if (S.me) $('#me-elo').textContent = (+$('#me-elo').textContent || 1200) + d;
    } else if (you > 0 && r.players[3 - you] && r.players[3 - you].bot) {
      elo.textContent = 'vs NeonBot · ' + (LEVEL[r.players[3 - you].level] || '');
    }
    updateResultButtons(r);
    overlay('ov-result', true);
  }

  function updateResultButtons(r) {
    const you = r.you, again = $('#r-again');
    const oppPresent = you > 0 && r.players[3 - you] && r.players[3 - you].online;
    again.hidden = you === 0;
    const waiting = you > 0 && r.players[you] && r.players[you].rematch;
    again.disabled = !!waiting || !oppPresent;
    again.firstElementChild.textContent = !oppPresent ? 'OPPONENT LEFT' : waiting ? 'WAITING…' : 'PLAY AGAIN';
  }

  function rematch() { net.send({ t: 'rematch' }); }
  $('#r-again').addEventListener('click', rematch);
  $('#r-view').addEventListener('click', () => overlay('ov-result', false));
  $('#r-home').addEventListener('click', leaveRoom);

  // ================================================================ leave
  $('#btn-leave').addEventListener('click', () => {
    const r = S.room;
    if (r && r.phase === 'PLAYING' && r.you > 0 && Date.now() - S.leaveArmed > 3000) {
      S.leaveArmed = Date.now();
      toast('Tap Leave again to forfeit this game', 'warn', '🏳️');
      return;
    }
    leaveRoom();
  });
  function leaveRoom() {
    net.send({ t: 'leave' });
    S.room = null;
    S.builtFor = null;
    overlay('ov-result', false);
    history.replaceState(null, '', '/');
    show('home');
  }

  // ================================================================ chat & reactions
  function addChat(item, silent) {
    const log = $('#chat-log');
    const d = document.createElement('div');
    if (item.sys) {
      d.className = 'msg sys';
      d.textContent = item.text.replace(/NeonBot · \w+/g, 'NeonBot');
    } else {
      const mine = S.me && item.from === S.me.name;
      d.className = 'msg' + (mine ? ' mine' : '');
      d.innerHTML = (mine ? '' : `<b style="color:${hue(item.hue)}">${esc(item.from)}</b>`) + esc(item.text);
      if (!silent && !mine && !$('#chat').classList.contains('open')) {
        S.unread++;
        $('#chat-badge').hidden = false;
        $('#chat-badge').textContent = S.unread;
        Sound.tone(1200, .05, 'sine', .02);
      }
    }
    log.appendChild(d);
    while (log.children.length > 120) log.firstChild.remove();
    log.scrollTop = log.scrollHeight;
  }
  function openChat() { $('#chat').classList.add('open'); S.unread = 0; $('#chat-badge').hidden = true; setTimeout(() => $('#in-chat').focus(), 300); }
  function closeChat() { $('#chat').classList.remove('open'); }
  $('#btn-chat').addEventListener('click', () => ($('#chat').classList.contains('open') ? closeChat() : openChat()));
  $('#chat-close').addEventListener('click', closeChat);
  $('#f-chat').addEventListener('submit', (e) => {
    e.preventDefault();
    const v = $('#in-chat').value.trim();
    if (!v) return;
    net.send({ t: 'chat', text: v });
    $('#in-chat').value = '';
  });

  const EMOJI = ['🔥', '😂', '😮', '👏', '😡', '🤯', '🎉', '💀', '🧠', '👀'];
  $('#reacts').innerHTML = EMOJI.map((e) => `<button data-e="${e}" aria-label="react ${e}">${e}</button>`).join('');
  $('#reacts').addEventListener('click', (e) => {
    const b = e.target.closest('button');
    if (!b || Date.now() - S.lastReact < 400) return;
    S.lastReact = Date.now();
    net.send({ t: 'react', emoji: b.dataset.e });
  });
  function floatEmoji(emoji, side) {
    const src = side ? $('#p' + side) : $('#board-wrap');
    const rc = src.getBoundingClientRect();
    const el = document.createElement('div');
    el.className = 'float-emoji';
    el.textContent = emoji;
    el.style.left = rc.left + rc.width * (.3 + Math.random() * .4) - 20 + 'px';
    el.style.top = rc.top + rc.height / 2 + 'px';
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 2300);
  }

  // ================================================================ how to play
  let slide = 0;
  function openHow() { slide = 0; moveSlide(); overlay('ov-how', true); store.set('ng.tutorial', '1'); }
  function moveSlide() {
    $('#how-track').style.transform = `translateX(${-slide * 100}%)`;
    $$('#how-dots i').forEach((d, i) => d.classList.toggle('on', i === slide));
    $('#how-next').firstElementChild.textContent = slide === 2 ? "LET'S PLAY" : 'NEXT';
  }
  function maybeTutorial() { if (S.mode === 'ultimate' && !store.get('ng.tutorial')) openHow(); }
  $('#how-next').addEventListener('click', () => { if (slide < 2) { slide++; moveSlide(); } else overlay('ov-how', false); });
  $('#how-close').addEventListener('click', () => overlay('ov-how', false));
  $('#btn-how').addEventListener('click', openHow);
  $('#btn-how-top').addEventListener('click', openHow);
  $$('.overlay').forEach((o) => o.addEventListener('click', (e) => { if (e.target === o && o.id === 'ov-how') overlay('ov-how', false); }));

  // ================================================================ confetti
  function confetti() {
    const cv = $('#fx'), ctx = cv.getContext('2d'), d = devicePixelRatio || 1;
    cv.width = innerWidth * d; cv.height = innerHeight * d; ctx.setTransform(d, 0, 0, d, 0, 0);
    const colors = ['#22d3ee', '#f472b6', '#a78bfa', '#a3e635', '#fbbf24', '#ffffff'];
    const parts = Array.from({ length: 220 }, (_, i) => ({
      x: innerWidth * (i % 2 ? .25 : .75), y: innerHeight * .55, vx: (Math.random() - .5) * 18, vy: -Math.random() * 18 - 6,
      s: Math.random() * 7 + 4, c: colors[(Math.random() * colors.length) | 0], a: Math.random() * 6, va: (Math.random() - .5) * .35,
    }));
    let f = 0;
    (function loop() {
      ctx.clearRect(0, 0, innerWidth, innerHeight);
      for (const p of parts) {
        p.vy += .38; p.vx *= .99; p.x += p.vx; p.y += p.vy; p.a += p.va;
        ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(p.a); ctx.fillStyle = p.c; ctx.fillRect(-p.s / 2, -p.s / 4, p.s, p.s / 2); ctx.restore();
      }
      if (++f < 230) requestAnimationFrame(loop); else ctx.clearRect(0, 0, innerWidth, innerHeight);
    })();
  }

  // ================================================================ boot
  $('#btn-sound').textContent = S.sound ? '🔊' : '🔇';
  $('#btn-sound').addEventListener('click', (e) => { S.sound = !S.sound; store.set('ng.sound', S.sound ? 'on' : 'off'); e.currentTarget.textContent = S.sound ? '🔊' : '🔇'; });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') { overlay('ov-how', false); closeChat(); } });

  $('#in-name').value = store.get('ng.name') || '';
  if (S.wantRoom) $('#invite-note').textContent = `🎮 You've been invited to room ${S.wantRoom}${S.wantSpectate ? ' to watch' : ''}!`;
  $('#f-name').addEventListener('submit', (e) => {
    e.preventDefault();
    const n = $('#in-name').value.trim();
    if (n) connect(n).catch(() => {});
  });

  // a refreshed tab rejoins its game automatically
  const saved = net.savedSession();
  if (saved && saved.name) {
    $('#in-name').value = saved.name;
    connect(saved.name).catch(() => {});
  }
})();
