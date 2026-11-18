/* Chat drawer and emoji reactions. */
import { $, esc, hue } from './util.js';
import { S, net } from './state.js';
import { Sound } from './sound.js';

const EMOJI = ['🔥', '😂', '😮', '👏', '😡', '🤯', '🎉', '💀', '🧠', '👀'];

export function addChat(item, silent) {
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

export function openChat() {
  $('#chat').classList.add('open');
  S.unread = 0;
  $('#chat-badge').hidden = true;
  setTimeout(() => $('#in-chat').focus(), 300);
}

export function closeChat() { $('#chat').classList.remove('open'); }

/** An emoji that floats up from the player card of whoever sent it. */
export function floatEmoji(emoji, side) {
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

export function initChat() {
  $('#btn-chat').addEventListener('click', () => ($('#chat').classList.contains('open') ? closeChat() : openChat()));
  $('#chat-close').addEventListener('click', closeChat);
  $('#f-chat').addEventListener('submit', (e) => {
    e.preventDefault();
    const v = $('#in-chat').value.trim();
    if (!v) return;
    net.send({ t: 'chat', text: v });
    $('#in-chat').value = '';
  });
  $('#reacts').innerHTML = EMOJI.map((e) => `<button data-e="${e}" aria-label="react ${e}">${e}</button>`).join('');
  $('#reacts').addEventListener('click', (e) => {
    const b = e.target.closest('button');
    if (!b || Date.now() - S.lastReact < 400) return;
    S.lastReact = Date.now();
    net.send({ t: 'react', emoji: b.dataset.e });
  });
}
