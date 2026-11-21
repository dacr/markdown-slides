// Every slide on its own page, every fragment shown, for the PDF export (/print) ; or the first slide alone, for the
// thumbnails (/render). The headless browser waits for the page to settle.

import { api, params, useTheme } from './common.js';
import { prepare, drawDiagrams } from './markdown.js';
import { buildSection, stacksOf } from './deck.js';

async function main() {
  const single = window.location.pathname === '/render';
  const [config, data] = await Promise.all([api('/api/config'), api(`/api/deck?id=${encodeURIComponent(params.get('id'))}`)]);
  const { slideWidth: width, slideHeight: height } = config;
  document.title = data.deck.title;
  const page = document.createElement('style');
  page.textContent = `@page { size: ${width}px ${height}px; margin: 0; } .print-page { width: ${width}px; height: ${height}px; }`;
  document.head.appendChild(page);
  await useTheme(data.theme);
  await prepare(data.slides.map(s => s.markdown));
  const context = { base: data.deck.base, stamp: data.deck.assetsStamp };
  const slides = single ? data.slides.slice(0, 1) : stacksOf(data.slides).flat();
  const root = document.getElementById('pages');
  const sections = slides.map(slide => {
    const pageElement = document.createElement('div');
    pageElement.className = 'print-page';
    const reveal = document.createElement('div');
    reveal.className = 'reveal';
    const container = document.createElement('div');
    container.className = 'slides';
    container.style.width = `${width}px`;
    container.style.height = `${height}px`;
    const section = buildSection(slide, context);
    section.classList.add('present');
    section.querySelectorAll('.fragment').forEach(f => f.classList.add('visible'));
    const background = section.getAttribute('data-background-color') || section.getAttribute('data-background');
    if (background && !/\.(png|jpe?g|gif|svg|webp)/i.test(background)) reveal.style.background = background;
    const image = section.getAttribute('data-background-image');
    if (image) Object.assign(reveal.style, { backgroundImage: `url("${image}")`, backgroundSize: section.getAttribute('data-background-size') || 'cover', backgroundPosition: 'center' });
    container.appendChild(section);
    reveal.appendChild(container);
    pageElement.appendChild(reveal);
    root.appendChild(pageElement);
    return section;
  });
  for (const section of sections) await drawDiagrams(section);
  const images = [...document.images].filter(img => !img.complete).map(img => new Promise(resolve => { img.onload = img.onerror = resolve; }));
  await Promise.race([Promise.all(images), new Promise(resolve => setTimeout(resolve, 5000))]);
  await document.fonts.ready;
  sections.forEach(section => { section.style.top = `${Math.max((height - section.scrollHeight) / 2, 0)}px`; });
  document.body.dataset.ready = 'true';
}

main().catch(e => {
  document.body.dataset.ready = 'error';
  document.body.textContent = `Unable to draw the slides : ${e.message}`;
});
