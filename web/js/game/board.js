/* The game screen: board, move preview, hints, player cards, turn banner, friend-room panel, turn clock. */
import { $, $$, esc, hue, LEVEL, SX, SO, current, toast } from './util.js';
import { S, net } from './state.js';
import { Sound } from './sound.js';
import { rematch } from './result.js';

// ------------------------------------------------------------------ board

export function buildBoard(r) {
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
    net.send({ t: 'move', cell: g });   // the server decides whether the move is legal
    setTimeout(() => { S.pending = false; }, 400);
  });
  c.addEventListener('mouseenter', () => { S.hover = g; preview(); });
  c.addEventListener('mouseleave', () => { if (S.hover === g) { S.hover = -1; preview(); } });
  return c;
}

const playableBoard = (r, b) => r.macro[b] === 0 && (r.active === -1 || r.active === b);

export function renderBoard(r) {
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
  drawWinLine(r);
}

function drawWinLine(r) {
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

// ------------------------------------------------------------------ move preview & hints

/** Hover preview: show which board this move sends the opponent to (or warn about a free move). */
export function preview() {
  const r = S.room;
  $$('.mini.target').forEach((m) => m.classList.remove('target'));
  if (!r || r.mode !== 'ultimate' || S.hover < 0) return renderHint(r);
  const c = $(`.cell[data-g="${S.hover}"]`);
  if (!c || !c.classList.contains('can')) return renderHint(r);
  const target = S.hover % 9;
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

/** true if playing g wins or fills its own small board (so it is no longer open afterwards). */
function wouldClose(r, g) {
  const b = Math.floor(g / 9), cells = r.cells.slice(b * 9, b * 9 + 9);
  cells[g % 9] = r.you;
  const L = [[0, 1, 2], [3, 4, 5], [6, 7, 8], [0, 3, 6], [1, 4, 7], [2, 5, 8], [0, 4, 8], [2, 4, 6]];
  return L.some((l) => cells[l[0]] === r.you && cells[l[1]] === r.you && cells[l[2]] === r.you) || cells.every((v) => v !== 0);
}

function setHint(html, warn) {
  const h = $('#hint');
  h.innerHTML = html;
  h.classList.toggle('warn', !!warn);
}

function renderHint(r) {
  if (!r) return setHint('');
  const opp = r.players[3 - r.you];
  const oppName = opp ? (opp.bot ? 'NeonBot' : esc(opp.name)) : '';
  if (r.phase === 'WAITING') return setHint('');
  if (r.phase === 'FINISHED') return setHint(r.you > 0 ? '<button class="mini-btn go" id="hint-again">🔁 Play again</button>' : 'Game over');
  if (r.you === 0) return setHint('👀 You are watching this game');
  if (r.turn !== r.you) return setHint(opp && opp.bot ? 'NeonBot is planning its move…' : `Waiting for <b>${oppName}</b>…`);
  if (r.mode === 'classic') return setHint('Get <b>three in a row</b> to win');
  if (r.active === -1) return setHint('✨ <b>Free move!</b> Play in any glowing board');
  return setHint('Play inside the <b>glowing board</b>');
}

// ------------------------------------------------------------------ players, turn banner, friend room

export function renderPlayers(r) {
  [1, 2].forEach((side) => {
    const el = $('#p' + side), p = r.players[side];
    el.querySelector('.pname').textContent = p ? (p.bot ? 'NeonBot' : p.name) : 'Waiting…';
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

export function renderTurn(r) {
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

/** Private room waiting panel: code tiles, invite link and QR code. */
export function renderWaiting(r) {
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

/** Animates the timer rings and the "⏱ 0:32" countdown. */
function startClock() {
  (function tick() {
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
    requestAnimationFrame(tick);
  })();
}

export function initBoard() {
  $('#hint').addEventListener('click', (e) => { if (e.target.id === 'hint-again') rematch(); });
  $('#btn-copy').addEventListener('click', async () => {
    const inp = $('#invite-link');
    try { await navigator.clipboard.writeText(inp.value); } catch (e) { inp.select(); document.execCommand('copy'); }
    toast('Invite link copied!', 'good', '🔗');
  });
  $('#btn-share').addEventListener('click', () => {
    if (S.room) navigator.share({ title: 'NeonGrid', text: 'Play Ultimate Tic-Tac-Toe with me! Room ' + S.room.code, url: $('#invite-link').value }).catch(() => {});
  });
  startClock();
}
