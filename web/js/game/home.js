/* Home screen: mode cards, Quick Match, NeonBot levels, friend rooms, Hall of Fame, live games. */
import { $, $$, esc, overlay, store, toast } from './util.js';
import { S, net } from './state.js';
import { Sound } from './sound.js';
import { maybeTutorial } from './tutorial.js';

const botName = (n) => (n && n.indexOf('NeonBot') === 0 ? 'NeonBot' : n);

export function renderHome() {
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

export function initHome() {
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
  // matchmaking overlay
  $('#search-cancel').addEventListener('click', () => { net.send({ t: 'cancel' }); overlay('ov-search', false); });
  $('#search-bot').addEventListener('click', () => { net.send({ t: 'cancel' }); net.send({ t: 'ai', mode: S.mode, level: 'medium' }); overlay('ov-search', false); });
}
