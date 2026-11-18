/* End-of-game screen: VICTORY / DEFEAT / DRAW, rating change, Play again. */
import { $, LEVEL, overlay } from './util.js';
import { S, net } from './state.js';
import { Sound } from './sound.js';
import { confetti } from './effects.js';

export function rematch() { net.send({ t: 'rematch' }); }

export function showResult(r) {
  if (!r || r.phase !== 'FINISHED') return;
  const you = r.you, title = $('#r-title'), em = $('#r-emblem'), card = $('#ov-result .result');
  const reason = { line: 'Three in a row!', draw: 'Nobody could break through', timeout: 'Ran out of time', resign: 'Opponent left the game', disconnect: 'Opponent disconnected' }[r.endReason] || '';
  let sub = reason;
  if (r.winner === 3) {
    em.textContent = '🤝'; title.textContent = 'DRAW'; title.className = 'r-title draw'; card.style.setProperty('--rc', 'rgba(139,92,246,.6)');
  } else if (you === 0) {
    const w = r.players[r.winner];
    em.textContent = '🏁'; title.textContent = ((w && (w.bot ? 'NEONBOT' : w.name.toUpperCase())) || '') + ' WINS'; title.className = 'r-title win'; card.style.setProperty('--rc', 'rgba(34,211,238,.5)');
  } else if (r.winner === you) {
    em.textContent = '🏆'; title.textContent = 'VICTORY'; title.className = 'r-title win'; card.style.setProperty('--rc', 'rgba(251,191,36,.6)');
    confetti(); Sound.win();
  } else {
    em.textContent = r.endReason === 'timeout' ? '⏱️' : '💥'; title.textContent = 'DEFEAT'; title.className = 'r-title lose'; card.style.setProperty('--rc', 'rgba(251,113,133,.5)');
    Sound.lose();
    if (r.endReason === 'timeout') sub = 'You ran out of time';
  }
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

export function updateResultButtons(r) {
  const you = r.you, again = $('#r-again');
  const oppPresent = you > 0 && r.players[3 - you] && r.players[3 - you].online;
  again.hidden = you === 0;
  const waiting = you > 0 && r.players[you] && r.players[you].rematch;
  again.disabled = !!waiting || !oppPresent;
  again.firstElementChild.textContent = !oppPresent ? 'OPPONENT LEFT' : waiting ? 'WAITING…' : 'PLAY AGAIN';
}

/** leaveRoom comes from main.js (it also switches screens). */
export function initResult(leaveRoom) {
  $('#r-again').addEventListener('click', rematch);
  $('#r-view').addEventListener('click', () => overlay('ov-result', false));
  $('#r-home').addEventListener('click', leaveRoom);
}
