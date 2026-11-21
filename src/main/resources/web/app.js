// The library : every presentation by year, the search, the live talks, the look and feel.

import { api, debounce, escapeHtml, formatDate, listen, toast, tokens } from './common.js';

const library = document.getElementById('library');
const results = document.getElementById('results');
const liveSection = document.getElementById('live');
const sourcesNav = document.getElementById('sources');
const input = document.getElementById('q');

let config;
let catalog;
let source = new URLSearchParams(window.location.search).get('source') || '';
let lastQuery = '';

const playUrl = (id, hash = '') => `/play?id=${encodeURIComponent(id)}${hash}`;

// ====================================================================================================================
// Thumbnails : drawn by the server, or the first slide drawn by this page meanwhile

const frames = new ResizeObserver(entries => entries.forEach(entry => {
  entry.target.style.setProperty('--frame-scale', entry.contentRect.width / config.slideWidth);
}));

function thumbnail(deck, className = 'mount') {
  const a = document.createElement('a');
  a.className = className;
  a.href = playUrl(deck.id);
  a.dataset.deck = deck.id;
  a.setAttribute('aria-label', `Play ${deck.title}`);
  a.style.setProperty('--slide-ratio', `${config.slideWidth} / ${config.slideHeight}`);
  if (deck.thumbnail) {
    const img = document.createElement('img');
    img.src = deck.thumbnail;
    img.alt = '';
    img.loading = 'lazy';
    img.decoding = 'async';
    a.appendChild(img);
  } else {
    // the render page shows the first slide with its theme, isolated from this page's styles
    const frame = document.createElement('iframe');
    frame.src = `/render?id=${encodeURIComponent(deck.id)}`;
    frame.loading = 'lazy';
    frame.tabIndex = -1;
    frame.title = '';
    frame.setAttribute('aria-hidden', 'true');
    frame.width = config.slideWidth;
    frame.height = config.slideHeight;
    a.appendChild(frame);
    a.classList.add('live-drawn');
    frames.observe(a);
  }
  return a;
}

// ====================================================================================================================
// Library

function yearOf(presentation) { return presentation.sortKey.slice(0, 4); }

function card(presentation) {
  const deck = presentation.main;
  const li = document.createElement('li');
  li.className = 'card';
  li.dataset.key = presentation.key;
  li.appendChild(thumbnail(deck));
  const body = document.createElement('div');
  body.className = 'card-body';
  const when = deck.date ? formatDate(deck.date) : formatDate(deck.sortKey.slice(0, 7));
  body.innerHTML = `
    <h3><a href="${playUrl(deck.id)}">${escapeHtml(deck.title)}</a></h3>
    ${deck.subtitle ? `<p class="subtitle" title="${escapeHtml(deck.subtitle)}">${escapeHtml(deck.subtitle)}</p>` : ''}
    <p class="meta"><time datetime="${escapeHtml(deck.date || deck.sortKey)}">${escapeHtml(when)}</time><span>${deck.slideCount} slides</span>${deck.directory ? `<span class="path" title="${escapeHtml(deck.source + '/' + deck.path)}">${escapeHtml(deck.directory)}</span>` : ''}</p>`;
  if (presentation.others.length) {
    const chapters = document.createElement('p');
    chapters.className = 'chapters';
    chapters.innerHTML = `<span>With</span> ${presentation.others.map(other =>
      `<a href="${playUrl(other.id)}" title="${escapeHtml(other.title)}, ${other.slideCount} slides">${escapeHtml(other.fileName.replace(/\.(md|markdown)$/i, ''))}</a>`).join(' ')}`;
    body.appendChild(chapters);
  }
  li.appendChild(body);
  return li;
}

function renderSources() {
  const names = catalog.sources;
  sourcesNav.hidden = names.length < 2;
  if (names.length < 2) return;
  sourcesNav.replaceChildren(...['', ...names].map(name => {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = name || 'All sources';
    button.setAttribute('aria-pressed', String(source === name));
    button.onclick = () => {
      source = name;
      const url = new URL(window.location);
      if (source) url.searchParams.set('source', source); else url.searchParams.delete('source');
      history.replaceState(null, '', url);
      renderSources();
      renderLibrary();
      if (lastQuery) runSearch(lastQuery);
    };
    return button;
  }));
}

function renderLibrary() {
  const shown = catalog.presentations.filter(p => !source || p.source === source);
  if (!shown.length) {
    library.innerHTML = `<div class="empty"><p>No presentations found yet.</p><p>Markdown files of the source directories appear here as soon as they are found. Check the sources from <button type="button" class="link" id="empty-status">Synchronization</button>.</p></div>`;
    document.getElementById('empty-status').onclick = openStatus;
    return;
  }
  const byYear = new Map();
  shown.forEach(p => { const y = yearOf(p); if (!byYear.has(y)) byYear.set(y, []); byYear.get(y).push(p); });
  // the thumbnails already shown are kept : no flicker when the catalog is refreshed
  const existing = new Map([...library.querySelectorAll('.card')].map(el => [el.dataset.key + '|' + el.dataset.version, el]));
  const sections = [...byYear.entries()].map(([year, presentations]) => {
    const section = document.createElement('section');
    section.className = 'year';
    section.setAttribute('aria-labelledby', `year-${year}`);
    const count = presentations.length === 1 ? '1 presentation' : `${presentations.length} presentations`;
    section.innerHTML = `<h2 class="year-mark" id="year-${year}"><span class="year-number">${year}</span><span class="year-count">${count}</span></h2>`;
    const list = document.createElement('ol');
    list.className = 'grid';
    presentations.forEach(p => {
      const version = [p.main.thumbnail, p.main.title, p.main.subtitle, p.main.slideCount, p.others.map(o => o.id).join(',')].join('|');
      const reused = existing.get(p.key + '|' + version);
      const element = reused || card(p);
      element.dataset.version = version;
      list.appendChild(element);
    });
    section.appendChild(list);
    return section;
  });
  library.replaceChildren(...sections);
}

function renderLive() {
  const rooms = catalog.live;
  liveSection.hidden = rooms.length === 0;
  if (!rooms.length) return;
  liveSection.innerHTML = `<h2>Live now</h2><ul>${rooms.map(room => {
    const followers = room.followers === 1 ? '1 participant' : `${room.followers} participants`;
    const mine = tokens.get(room.id);
    return `<li>
      <span class="live-title">${escapeHtml(room.title)}</span>
      <span class="live-meta">${followers}</span>
      <span class="live-meta">Slide ${room.state.h + 1}</span>
      <span class="live-code">${escapeHtml(room.id)}</span>
      <a class="primary" href="/live/${encodeURIComponent(room.id)}">${mine ? 'Slides' : 'Follow'}</a>
      ${mine ? `<a href="/speaker?room=${encodeURIComponent(room.id)}" target="speaker-${escapeHtml(room.id)}">Speaker view</a>` : ''}
    </li>`;
  }).join('')}</ul>`;
}

async function refresh() {
  catalog = await api('/api/catalog');
  renderSources();
  renderLibrary();
  renderLive();
}

const refreshSoon = debounce(() => refresh().catch(e => console.warn(e)), 400);

// ====================================================================================================================
// Search

const fold = text => text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();

/** The text with the query words marked, matched as the index does : case and accents ignored, words as prefixes. */
function highlight(text, terms) {
  if (!terms.length) return escapeHtml(text);
  const chars = [...text];
  const folded = [];
  const owners = [];
  chars.forEach((c, i) => { [...fold(c)].forEach(f => { folded.push(f); owners.push(i); }); });
  const flat = folded.join('');
  const marked = new Array(chars.length).fill(false);
  terms.forEach(term => {
    const pattern = new RegExp(`(^|[^\\p{L}\\p{N}])(${term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gu');
    let m;
    while ((m = pattern.exec(flat))) {
      const start = m.index + m[1].length;
      for (let k = start; k < start + m[2].length; k++) marked[owners[k]] = true;
      if (pattern.lastIndex === m.index) pattern.lastIndex++;
    }
  });
  let html = '';
  let open = false;
  chars.forEach((c, i) => {
    if (marked[i] && !open) { html += '<mark>'; open = true; }
    if (!marked[i] && open) { html += '</mark>'; open = false; }
    html += escapeHtml(c);
  });
  return html + (open ? '</mark>' : '');
}

function deckById(id) {
  for (const p of catalog.presentations) {
    if (p.main.id === id) return p.main;
    const other = p.others.find(o => o.id === id);
    if (other) return other;
  }
  return null;
}

async function runSearch(query) {
  lastQuery = query;
  const url = new URL(window.location);
  if (query) url.searchParams.set('q', query); else url.searchParams.delete('q');
  history.replaceState(null, '', url);
  if (!query.trim()) { results.hidden = true; library.hidden = false; return; }
  const found = await api(`/api/search?q=${encodeURIComponent(query)}${source ? `&source=${encodeURIComponent(source)}` : ''}`);
  if (query !== lastQuery) return;
  library.hidden = true;
  results.hidden = false;
  const slides = found.decks.reduce((n, d) => n + d.slides.length, 0);
  const summary = document.createElement('p');
  summary.className = 'results-summary';
  summary.textContent = found.decks.length
    ? `${found.decks.length} ${found.decks.length === 1 ? 'presentation' : 'presentations'}, ${slides} matching ${slides === 1 ? 'slide' : 'slides'}${found.relaxed ? ', with some of the words only' : ''}${found.semantic ? ', by words and by meaning' : ''}`
    : `Nothing matches “${query}”. Try fewer or other words.`;
  const items = found.decks.map(match => {
    const deck = deckById(match.deckId);
    if (!deck) return null;
    const article = document.createElement('article');
    article.className = 'result';
    article.appendChild(thumbnail(deck, 'mount result-mount'));
    const body = document.createElement('div');
    body.className = 'result-body';
    body.innerHTML = `
      <h3><a href="${playUrl(deck.id)}">${highlight(deck.title, found.terms)}</a></h3>
      <p class="meta"><time>${escapeHtml(formatDate(deck.date || deck.sortKey.slice(0, 7)))}</time><span>${escapeHtml(deck.path)}</span></p>
      <ol class="hits">${match.slides.map(slide => `
        <li><a href="${playUrl(deck.id, `#/${slide.h}${slide.v ? '/' + slide.v : ''}`)}">
          <span class="hit-number">${slide.h + 1}${slide.v ? '.' + (slide.v + 1) : ''}</span>
          <span class="hit-text"><span class="hit-title">${highlight(slide.title || 'Untitled slide', found.terms)}</span>
          <span class="hit-snippet">${highlight(slide.snippet, found.terms)}</span></span>
          ${slide.meaning && !slide.words ? '<span class="hit-via" title="Found by meaning, without the words">by meaning</span>' : ''}
        </a></li>`).join('')}
      </ol>`;
    article.appendChild(body);
    return article;
  }).filter(Boolean);
  results.replaceChildren(summary, ...items);
}

const searchSoon = debounce(query => runSearch(query).catch(e => toast(`Search failed : ${e.message}`, 'error')), 180);

input.addEventListener('input', () => searchSoon(input.value));
document.getElementById('search-form').addEventListener('submit', event => { event.preventDefault(); runSearch(input.value); });
input.addEventListener('keydown', event => {
  if (event.key === 'Escape') { input.value = ''; runSearch(''); }
  if (event.key === 'ArrowDown') { results.querySelector('a')?.focus(); event.preventDefault(); }
});
document.addEventListener('keydown', event => {
  if (event.key === '/' && !event.target.closest('input, textarea, select')) { event.preventDefault(); input.focus(); input.select(); }
});

// ====================================================================================================================
// Dialogs

const lookDialog = document.getElementById('look-dialog');
document.getElementById('look-button').onclick = () => {
  const select = document.getElementById('theme-select');
  select.replaceChildren(...config.themes.map(theme => {
    const option = document.createElement('option');
    option.value = theme.name;
    option.textContent = theme.origin === 'built-in' ? `${theme.name} (built-in)` : theme.name;
    option.selected = theme.name === config.theme;
    return option;
  }));
  document.getElementById('custom-css').value = config.customCss;
  lookDialog.showModal();
};
lookDialog.addEventListener('close', async () => {
  if (lookDialog.returnValue !== 'save') return;
  try {
    await api('/api/settings', { method: 'PUT', body: { theme: document.getElementById('theme-select').value, customCss: document.getElementById('custom-css').value } });
    config = await api('/api/config');
    toast(`Theme ${config.theme} saved : the thumbnails are drawn again`);
  } catch (e) {
    toast(`Unable to save : ${e.message}`, 'error');
  }
});

const joinDialog = document.getElementById('join-dialog');
document.getElementById('join-button').onclick = () => { document.getElementById('join-code').value = ''; joinDialog.showModal(); };
joinDialog.addEventListener('close', () => {
  const code = document.getElementById('join-code').value.trim().toLowerCase();
  if (joinDialog.returnValue === 'join' && code) window.location = `/live/${encodeURIComponent(code)}`;
});

const statusDialog = document.getElementById('status-dialog');
async function openStatus() {
  const details = document.getElementById('status-details');
  details.innerHTML = '<dt>Loading</dt><dd></dd>';
  statusDialog.showModal();
  try {
    const s = await api('/api/status');
    const rows = [
      ['Presentations', `${s.decks} decks, ${s.slides} slides`],
      ...s.sources.map(src => [`Source ${src.name}`, `${src.path}${src.git ? ', git' : ''}, ${src.decks} decks`]),
      ['Last check', s.lastScan ? `${new Date(s.lastScan).toLocaleTimeString()}, ${s.lastScanMillis} ms` : 'not yet'],
      ['Thumbnails', s.browserAvailable ? (s.thumbnailsPending ? `${s.thumbnailsPending} being drawn` : 'up to date') : 'drawn by this page, no browser found for the server'],
      ['Search by meaning', !s.embeddingsEnabled ? 'off, words only' : `${s.embeddingsModel}, ${s.vectors} slides${s.embeddingsPending ? `, ${s.embeddingsPending} decks waiting` : ''}${s.embeddingsAvailable === false ? ', server unavailable' : ''}`],
      ['PDF export', s.browserAvailable ? 'available' : 'unavailable, no browser found'],
    ];
    if (s.lastError) rows.push(['Last problem', s.lastError]);
    details.innerHTML = rows.map(([k, v]) => `<dt>${escapeHtml(k)}</dt><dd>${escapeHtml(v)}</dd>`).join('');
  } catch (e) {
    details.innerHTML = `<dt>Unavailable</dt><dd>${escapeHtml(e.message)}</dd>`;
  }
}
document.getElementById('status-button').onclick = openStatus;
document.getElementById('status-close').onclick = () => statusDialog.close();
document.getElementById('scan-now').onclick = async () => {
  try { const r = await api('/api/scan', { method: 'POST' }); toast(r.message); openStatus(); } catch (e) { toast(e.message, 'error'); }
};

// ====================================================================================================================

function syncStatus(connected) {
  const button = document.getElementById('status-button');
  button.classList.toggle('offline', !connected);
  document.getElementById('sync-text').textContent = connected ? 'In sync' : 'Reconnecting';
}

async function main() {
  config = await api('/api/config');
  await refresh();
  const initial = new URLSearchParams(window.location.search).get('q');
  if (initial) { input.value = initial; await runSearch(initial); }
  listen('/api/events', {
    'catalog-changed': refreshSoon,
    'deck-removed': refreshSoon,
    'thumbnail': refreshSoon,
    'assets-changed': refreshSoon,
    'live-changed': refreshSoon,
    'settings-changed': async () => { config = await api('/api/config'); refreshSoon(); },
    'deck-changed': () => { refreshSoon(); if (lastQuery) searchSoon(lastQuery); },
  }, syncStatus);
  // the followers counts change without rooms starting or ending
  setInterval(() => { if (catalog.live.length) refreshSoon(); }, 15000);
}

main().catch(e => toast(`Unable to load the library : ${e.message}`, 'error'));
