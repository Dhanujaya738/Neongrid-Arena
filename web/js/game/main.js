/*
 * NeonGrid game — entry point.
 * Connects to the server, routes every server message to the right part of the UI,
 * and switches between the splash, home and game screens.
 *
 *   util.js      DOM helpers, toasts, X/O artwork        state.js   shared state + the secure connection
 *   sound.js     sound effects                            effects.js background, splash animation, confetti
 *   home.js      home screen                              board.js   board, hints, players, turn, friend room
 *   chat.js      chat + emoji reactions                   result.js  end-of-game screen
 *   tutorial.js  how-to-play overlay
 */
import { $, $$, hue, overlay, current, store, toast } from './util.js';
import { S, net, onConnectionLost } from './state.js';
import { Sound } from './sound.js';
import { startSky, startHero } from './effects.js';
import { renderHome, initHome } from './home.js';
import { buildBoard, renderBoard, renderPlayers, renderTurn, renderWaiting, preview, initBoard } from './board.js';
import { showResult, updateResultButtons, initResult } from './result.js';
import { addChat, closeChat, floatEmoji, initChat } from './chat.js';
import { initTutorial } from './tutorial.js';

// ================================================================ screens

function show(name) {
  $$('.view').forEach((v) => v.classList.toggle('on', v.id === 'v-' + name));
  $('#me').hidden = name === 'splash' || !S.me;
  if (name !== 'game') closeChat();
  window.scrollTo(0, 0);
}

function leaveRoom() {
  net.send({ t: 'leave' });
  S.room = null;
  S.builtFor = null;
  overlay('ov-result', false);
  history.replaceState(null, '', '/');
  show('home');
}

// ================================================================ connection

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

/** Connection dropped: show the overlay and reconnect. The server keeps our seat for 30 s. */
onConnectionLost(() => {
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
    } catch (e) { /* the next close event schedules another attempt */ }
  }, Math.min(5000, 700 * S.retry));
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

// ================================================================ server messages

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
net.on('thinking', () => renderTurn(S.room));
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

/** Every room update from the server: redraw the game screen. */
function onRoom(r) {
  const prev = S.room;
  S.room = r;
  S.pending = false;
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
  renderTurn(r);
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

// ================================================================ boot

startSky();
startHero();
initHome();
initBoard();
initResult(leaveRoom);
initChat();
initTutorial();

$('#brand').addEventListener('click', () => { if (S.me && !S.room) show('home'); });
$('#btn-leave').addEventListener('click', () => {
  const r = S.room;
  if (r && r.phase === 'PLAYING' && r.you > 0 && Date.now() - S.leaveArmed > 3000) {
    S.leaveArmed = Date.now();
    toast('Tap Leave again to forfeit this game', 'warn', '🏳️');
    return;
  }
  leaveRoom();
});
$('#btn-sound').textContent = S.sound ? '🔊' : '🔇';
$('#btn-sound').addEventListener('click', (e) => {
  S.sound = !S.sound;
  store.set('ng.sound', S.sound ? 'on' : 'off');
  e.currentTarget.textContent = S.sound ? '🔊' : '🔇';
});
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
