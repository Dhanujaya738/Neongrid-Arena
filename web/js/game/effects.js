/* Visual effects: starry background, splash-screen animation, victory confetti. */
import { $, $$, SX, SO } from './util.js';

/** Drifting stars with faint "network" lines between neighbours. */
export function startSky() {
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
}

/** The little self-playing 3x3 board on the splash screen. */
export function startHero() {
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
}

export function confetti() {
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
