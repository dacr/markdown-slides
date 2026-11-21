// The speaker view : the current slide, the next step, the notes, the timers ; it drives the live presentation.

import { api, listen, params, presenter, toast, tokens, useTheme } from './common.js';
import { Deck, renderStatic } from './deck.js';
import { prepare, renderInto } from './markdown.js';

const room = params.get('room');
const elements = {
  current: document.getElementById('current'),
  next: document.getElementById('next'),
  nextLabel: document.getElementById('next-label'),
  notes: document.getElementById('notes'),
  elapsed: document.getElementById('elapsed'),
  slideTime: document.getElementById('slide-time'),
  remaining: document.getElementById('remaining'),
  clock: document.getElementById('clock'),
  position: document.getElementById('position'),
  followers: document.getElementById('followers'),
  code: document.getElementById('code'),
  title: document.getElementById('title'),
};

let config;
let deckId;
let data;
let deck;
let driver;
let themeName;
let lastSeq = 0;
let lastKey = '';

const timer = { started: Date.now(), pausedAt: null, offset: 0, slideStarted: Date.now() };

const format = ms => {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return `${h ? h + ':' : ''}${String(m).padStart(h ? 2 : 1, '0')}:${String(s).padStart(2, '0')}`;
};

function elapsed() { return (timer.pausedAt ?? Date.now()) - timer.started - timer.offset; }

function tick() {
  elements.elapsed.textContent = format(elapsed());
  elements.slideTime.textContent = format(Date.now() - timer.slideStarted);
  elements.clock.textContent = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const minutes = parseFloat(data?.frontMatter?.duration || '');
  if (minutes > 0) {
    const left = minutes * 60000 - elapsed();
    elements.remaining.hidden = false;
    elements.remaining.textContent = left >= 0 ? `${format(left)} left` : `${format(-left)} over`;
    // where the presenter should be by now, compared to where they are
    const expected = (elapsed() / (minutes * 60000)) * Math.max(deck.count - 1, 1);
    elements.remaining.dataset.pace = left < 0 ? 'over' : deck.index + 1 < expected - 1 ? 'behind' : 'ok';
  }
}

document.getElementById('timer-pause').onclick = event => {
  if (timer.pausedAt) { timer.offset += Date.now() - timer.pausedAt; timer.pausedAt = null; event.currentTarget.textContent = 'Pause'; }
  else { timer.pausedAt = Date.now(); event.currentTarget.textContent = 'Resume'; }
  tick();
};
document.getElementById('timer-reset').onclick = () => {
  Object.assign(timer, { started: Date.now(), offset: 0, slideStarted: Date.now(), pausedAt: timer.pausedAt ? Date.now() : null });
  tick();
};

/** What comes next : the next fragment of this slide, or the next slide. */
function nextStep(state) {
  const count = deck.fragmentCount(state.h, state.v);
  if (state.f < count - 1) return { h: state.h, v: state.v, f: state.f + 1, label: 'Next fragment' };
  if (state.v < deck.sections[state.h].length - 1) return { h: state.h, v: state.v + 1, f: -1, label: 'Next slide, below' };
  if (state.h < deck.sections.length - 1) return { h: state.h + 1, v: 0, f: -1, label: 'Next slide' };
  return null;
}

function showSide(state) {
  const key = `${state.h}/${state.v}/${state.f}`;
  if (key === lastKey) return;
  if (lastKey.split('/').slice(0, 2).join('/') !== `${state.h}/${state.v}`) timer.slideStarted = Date.now();
  lastKey = key;
  const next = nextStep(state);
  elements.nextLabel.textContent = next ? next.label : 'End of the presentation';
  if (next) renderStatic(elements.next, data, next.h, next.v, { width: config.slideWidth, height: config.slideHeight }, { fragments: next.f });
  else elements.next.replaceChildren();
  const slide = data.slides.find(s => s.h === state.h && s.v === state.v);
  if (slide?.notes) renderInto(elements.notes, slide.notes, { base: data.deck.base, stamp: data.deck.assetsStamp });
  else elements.notes.innerHTML = '<p class="no-notes">No notes for this slide.</p>';
  elements.position.textContent = `${deck.index + 1} / ${deck.count}`;
  tick();
}

function onChange(state, origin) {
  if (origin === 'local') driver?.send(state);
  showSide(state);
}

async function load() {
  data = await api(`/api/deck?id=${encodeURIComponent(deckId)}`);
  if (data.theme !== themeName) { themeName = data.theme; await useTheme(themeName); }
  await prepare(data.slides.map(s => s.markdown));
  document.title = `Speaker · ${data.deck.title}`;
  elements.title.textContent = data.deck.title;
  if (!deck) deck = new Deck(elements.current, { width: config.slideWidth, height: config.slideHeight, mode: 'play', controls: false, progress: false, slideNumber: false, onChange });
  lastKey = '';
  await deck.load(data);
}

document.addEventListener('keydown', event => {
  if (event.target.closest('input, textarea, button, select')) { if (event.key !== ' ' && !event.key.startsWith('Arrow')) return; }
  if (event.ctrlKey || event.metaKey || event.altKey) return;
  const actions = {
    ArrowRight: () => deck.right(), ArrowLeft: () => deck.left(), ArrowDown: () => deck.down(), ArrowUp: () => deck.up(),
    ' ': () => (event.shiftKey ? deck.prev() : deck.next()), PageDown: () => deck.next(), n: () => deck.next(), Enter: () => deck.next(),
    PageUp: () => deck.prev(), p: () => deck.prev(), Backspace: () => deck.prev(), Home: () => deck.first(), End: () => deck.last(),
    b: () => deck.setPaused(!deck.state.paused), '.': () => deck.setPaused(!deck.state.paused),
  };
  const action = actions[event.key];
  if (!action) return;
  event.preventDefault();
  action();
});

document.getElementById('prev').onclick = () => deck.prev();
document.getElementById('next-step').onclick = () => deck.next();
document.getElementById('black').onclick = () => deck.setPaused(!deck.state.paused);
document.getElementById('audience').onclick = () => {
  window.open(`/play?room=${encodeURIComponent(room)}`, `audience-${room}`);
};
document.getElementById('end').onclick = async () => {
  try { await driver.stop(); } catch (e) { toast(e.message, 'error'); }
};
const notesSize = Number(localStorage.getItem('speaker-notes-size') || 22);
document.documentElement.style.setProperty('--notes-size', `${notesSize}px`);
document.getElementById('notes-smaller').onclick = () => resizeNotes(-2);
document.getElementById('notes-larger').onclick = () => resizeNotes(2);
function resizeNotes(delta) {
  const size = Math.max(12, Math.min(60, parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--notes-size')) + delta));
  document.documentElement.style.setProperty('--notes-size', `${size}px`);
  try { localStorage.setItem('speaker-notes-size', String(size)); } catch (e) { /* private mode */ }
}

async function main() {
  config = await api('/api/config');
  document.documentElement.style.setProperty('--slide-ratio', `${config.slideWidth} / ${config.slideHeight}`);
  const info = await api(`/api/live/room?room=${encodeURIComponent(room)}`).catch(() => null);
  if (!info) { document.getElementById('ended').hidden = false; return; }
  const token = window.location.hash.startsWith('#token=') ? decodeURIComponent(window.location.hash.slice(7)) : tokens.get(room);
  if (!token) { document.getElementById('no-token').hidden = false; return; }
  tokens.set(room, token);
  history.replaceState(null, '', `/speaker?room=${encodeURIComponent(room)}`);
  driver = presenter(room, token);
  deckId = info.deckId;
  elements.code.textContent = room;
  await load();
  deck.setState(info.state, 'remote');
  setInterval(tick, 1000);
  listen(`/api/live/events?room=${encodeURIComponent(room)}&presenter=true`, {
    state: message => {
      if (message.state.seq < lastSeq) return;
      lastSeq = message.state.seq;
      elements.followers.textContent = message.followers === 1 ? '1 participant' : `${message.followers} participants`;
      const s = deck.state;
      if (s.h !== message.state.h || s.v !== message.state.v || s.f !== message.state.f || s.paused !== message.state.paused) deck.setState(message.state);
    },
    followers: message => { elements.followers.textContent = message.followers === 1 ? '1 participant' : `${message.followers} participants`; },
    ended: () => { tokens.remove(room); document.getElementById('ended').hidden = false; },
  });
  listen('/api/events', {
    'deck-changed': event => { if (event.deckId === data.deck.id) load().then(() => toast('Slides updated')); },
    'assets-changed': event => { if (event.deckId === data.deck.id) load(); },
    'theme-changed': event => { if (event.stamp === themeName) useTheme(themeName, String(Date.now())); },
    'settings-changed': () => { themeName = null; load(); },
  });
}

main().catch(e => toast(`Unable to open the speaker view : ${e.message}`, 'error'));
