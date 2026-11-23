// Markdown of a slide to HTML, reveal-md compatible, with extensions :
//  - code highlighting (highlight.js, its rarer languages loaded on demand), with highlighted lines : ```scala [2-4]
//  - mermaid diagrams : ```mermaid
//  - maths (KaTeX) : $inline$, $$display$$, ```math
//  - svg : ```svg, or inline <svg> html
//  - terminal sessions : ```terminal (or console, shell-session), prompts `$ ` and `# ` highlighted
//  - callouts : > [!NOTE], [!TIP], [!WARNING], [!IMPORTANT], [!CAUTION]
//  - ==marked text==, :emoji: shortcodes, GFM tables and task lists (Obsidian's `- [/]` in progress too)
//  - reveal attributes : <!-- .element: class="fragment" --> after an element
// Relative pictures and links are resolved against the deck directory ; links to other decks open them in the player.

const libraries = {
  katex: { scripts: ['/webjars/katex/0.17.0/dist/katex.min.js'], styles: ['/webjars/katex/0.17.0/dist/katex.min.css'], global: 'katex' },
  mermaid: { scripts: ['/webjars/mermaid/11.17.2/dist/mermaid.min.js'], styles: [], global: 'mermaid' },
};
const loading = {};

/** Loads a browser library once, on demand : mermaid is large and few decks need it. */
export function ensureLibrary(name) {
  if (loading[name]) return loading[name];
  const lib = libraries[name];
  if (window[lib.global]) return (loading[name] = Promise.resolve(window[lib.global]));
  lib.styles.forEach(href => {
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = href;
    document.head.prepend(link);
  });
  loading[name] = lib.scripts.reduce(
    (previous, src) => previous.then(() => new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = src;
      script.onload = resolve;
      script.onerror = () => reject(new Error(`Unable to load ${src}`));
      document.head.appendChild(script);
    })),
    Promise.resolve()
  ).then(() => {
    if (name === 'mermaid') window.mermaid.initialize({ startOnLoad: false, securityLevel: 'loose', theme: 'default', fontFamily: 'inherit' });
    return window[lib.global];
  });
  return loading[name];
}

/** The libraries the slides need, loaded before rendering them. */
export async function prepare(markdowns) {
  const all = markdowns.join('\n');
  const wanted = [];
  if (/\$\$|\$[^\s$][^$\n]*\$|```\s*(math|latex|katex|tex)\b/.test(all)) wanted.push(ensureLibrary('katex').catch(() => null));
  wanted.push(ensureLanguages(all));
  await Promise.all(wanted);
}

/**
 * The languages of the code blocks : highlight.js common ones are always there (rust, typescript, java, go, python...),
 * the others (scala, nix, haskell, dockerfile...) are loaded on demand, one small file each.
 */
const LANGUAGES = '/webjars/highlightjs__cdn-assets/11.11.1/languages/';
/** The grammars to load, those of highlight.js 11.11.1 not in its common bundle */
const GRAMMARS = new Set(`
  1c abnf accesslog actionscript ada angelscript apache applescript arcade arduino armasm asciidoc aspectj autohotkey
  autoit avrasm awk axapta basic bnf brainfuck cal capnproto ceylon clean clojure clojure-repl cmake coffeescript coq
  cos crmsh crystal csp d dart delphi django dns dockerfile dos dsconfig dts dust ebnf elixir elm erb erlang
  erlang-repl excel fix flix fortran fsharp gams gauss gcode gherkin glsl gml golo gradle groovy haml handlebars
  haskell haxe hsp http hy inform7 irpf90 isbl jboss-cli julia julia-repl lasso latex ldif leaf lisp livecodeserver
  livescript llvm lsl mathematica matlab maxima mel mercury mipsasm mizar mojolicious monkey moonscript n1ql
  nestedtext nginx nim nix node-repl nsis ocaml openscad oxygene parser3 pf pgsql pony powershell processing profile
  prolog properties protobuf puppet purebasic q qml reasonml rib roboconf routeros rsl ruleslanguage sas scala scheme
  scilab smali smalltalk sml sqf stan stata step21 stylus subunit taggerscript tap tcl thrift tp twig vala vbscript
  vbscript-html verilog vhdl vim wren x86asm xl xquery zephir
`.trim().split(/\s+/));
/** The names of the languages not matching their file, the aliases of highlight.js 11.11.1 grammars */
const LANGUAGE_FILES = {
  as: 'actionscript', asc: 'angelscript', apacheconf: 'apache', osascript: 'applescript', ino: 'arduino',
  arm: 'armasm', adoc: 'asciidoc', ahk: 'autohotkey', 'x++': 'axapta', bf: 'brainfuck', capnp: 'capnproto',
  icl: 'clean', dcl: 'clean', clj: 'clojure', edn: 'clojure', 'cmake.in': 'cmake', coffee: 'coffeescript',
  cson: 'coffeescript', iced: 'coffeescript', cls: 'cos', crm: 'crmsh', pcmk: 'crmsh', cr: 'crystal', dpr: 'delphi',
  dfm: 'delphi', pas: 'delphi', pascal: 'delphi', jinja: 'django', bind: 'dns', zone: 'dns', docker: 'dockerfile',
  bat: 'dos', cmd: 'dos', dst: 'dust', ex: 'elixir', exs: 'elixir', erl: 'erlang', xlsx: 'excel', xls: 'excel',
  f90: 'fortran', f95: 'fortran', fs: 'fsharp', 'f#': 'fsharp', gms: 'gams', gss: 'gauss', nc: 'gcode',
  feature: 'gherkin', hbs: 'handlebars', 'html.hbs': 'handlebars', 'html.handlebars': 'handlebars',
  htmlbars: 'handlebars', hs: 'haskell', hx: 'haxe', https: 'http', hylang: 'hy', i7: 'inform7',
  'wildfly-cli': 'jboss-cli', jldoctest: 'julia-repl', ls: 'livescript', lassoscript: 'lasso', tex: 'latex',
  mma: 'mathematica', wl: 'mathematica', m: 'mercury', moo: 'mercury', mips: 'mipsasm', moon: 'moonscript',
  nt: 'nestedtext', nginxconf: 'nginx', nixos: 'nix', ml: 'sml', scad: 'openscad', 'pf.conf': 'pf',
  postgres: 'pgsql', postgresql: 'pgsql', pwsh: 'powershell', ps: 'powershell', ps1: 'powershell', pde: 'processing',
  proto: 'protobuf', pp: 'puppet', pb: 'purebasic', pbi: 'purebasic', k: 'q', kdb: 'q', qt: 'qml', re: 'reasonml',
  graph: 'roboconf', instances: 'roboconf', mikrotik: 'routeros', scm: 'scheme', sci: 'scilab', st: 'smalltalk',
  stanfuncs: 'stan', do: 'stata', ado: 'stata', p21: 'step21', step: 'step21', stp: 'step21', styl: 'stylus',
  tk: 'tcl', craftcms: 'twig', vbs: 'vbscript', v: 'verilog', sv: 'verilog', svh: 'verilog', tao: 'xl',
  xpath: 'xquery', xq: 'xquery', xqm: 'xquery', zep: 'zephir'
};
/** Code blocks drawn otherwise than by highlight.js */
const NOT_CODE = new Set(['mermaid', 'math', 'latex', 'katex', 'tex', 'svg', 'terminal', 'console', 'shell-session', 'term', 'plaintext', 'text', 'txt']);
const FENCE_LANGUAGE = /^ {0,3}(?:`{3,}|~{3,})[ \t]*([^\s`[{]+)/gm;
const languages = {};

/** Loads the highlight.js grammar of a language once, when the bundle has not got it ; unknown languages stay plain. */
export function ensureLanguage(language) {
  const name = language.toLowerCase();
  if (!window.hljs || window.hljs.getLanguage(name) || NOT_CODE.has(name)) return Promise.resolve();
  const file = LANGUAGE_FILES[name] || (GRAMMARS.has(name) ? name : null);
  if (!file) return Promise.resolve();
  languages[file] = languages[file] || new Promise(resolve => {
    const script = document.createElement('script');
    script.src = `${LANGUAGES}${file}.min.js`;
    script.onload = resolve;
    script.onerror = resolve;
    document.head.appendChild(script);
  });
  return languages[file];
}

/** The languages of the code blocks of some markdown, loaded. */
function ensureLanguages(markdown) {
  const names = new Set([...markdown.matchAll(FENCE_LANGUAGE)].map(m => m[1].toLowerCase()));
  return Promise.all([...names].map(ensureLanguage));
}

const escapeHtml = text => String(text).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function math(text, display) {
  if (!window.katex) return `<code class="math-source">${escapeHtml(text)}</code>`;
  try {
    return window.katex.renderToString(text, { displayMode: display, throwOnError: false, output: 'html' });
  } catch (e) {
    return `<code class="math-error">${escapeHtml(text)}</code>`;
  }
}

/** `1,3-5` as line numbers. */
function lineSet(spec) {
  const lines = new Set();
  spec.split(',').map(s => s.trim()).filter(Boolean).forEach(part => {
    const [a, b] = part.split('-').map(n => parseInt(n, 10));
    if (!isNaN(a)) for (let i = a; i <= (isNaN(b) ? a : b); i++) lines.add(i);
  });
  return lines;
}

function highlighted(code, language) {
  if (window.hljs && language && window.hljs.getLanguage(language)) {
    try { return window.hljs.highlight(code, { language, ignoreIllegals: true }).value; } catch (e) { /* plain */ }
  }
  if (window.hljs && !language && code.length < 20000) {
    try { return window.hljs.highlightAuto(code).value; } catch (e) { /* plain */ }
  }
  return escapeHtml(code);
}

/** Highlighted html split by lines, each line keeping its open spans. */
function splitLines(html) {
  const lines = [];
  let open = [];
  html.split('\n').forEach(line => {
    const prefix = open.join('');
    const tags = line.match(/<\/?span[^>]*>/g) || [];
    tags.forEach(tag => { if (tag.startsWith('</')) open.pop(); else open.push(tag); });
    lines.push(prefix + line + '</span>'.repeat(open.length));
  });
  return lines;
}

function terminal(code, title) {
  const lines = code.replace(/\n$/, '').split('\n').map(line => {
    const prompt = /^(\s*(?:[\w.@~:/-]*[$#%>❯]|\$|#|>|❯))(\s.*)?$/.exec(line);
    if (prompt && /[$#%>❯]$/.test(prompt[1].trim())) {
      return `<span class="term-line term-input"><span class="term-prompt">${escapeHtml(prompt[1])}</span><span class="term-command">${escapeHtml(prompt[2] || '')}</span></span>`;
    }
    return `<span class="term-line term-output">${escapeHtml(line) || ' '}</span>`;
  });
  return `<div class="terminal"><div class="terminal-bar"><i></i><i></i><i></i><span>${escapeHtml(title || '')}</span></div><pre class="terminal-body"><code>${lines.join('\n')}</code></pre></div>`;
}

const emojis = {
  smile: '😄', grin: '😁', wink: '😉', heart: '❤️', thumbsup: '👍', '+1': '👍', thumbsdown: '👎', '-1': '👎', rocket: '🚀', fire: '🔥',
  warning: '⚠️', bulb: '💡', star: '⭐', sparkles: '✨', tada: '🎉', check: '✔️', white_check_mark: '✅', x: '❌', question: '❓',
  exclamation: '❗', bug: '🐛', lock: '🔒', unlock: '🔓', key: '🔑', zap: '⚡', hammer: '🔨', wrench: '🔧', gear: '⚙️', book: '📖',
  memo: '📝', chart: '📈', computer: '💻', cloud: '☁️', coffee: '☕', eyes: '👀', thinking: '🤔', clap: '👏', muscle: '💪',
  point_right: '👉', point_left: '👈', arrow_right: '➡️', arrow_left: '⬅️', snail: '🐌', turtle: '🐢', robot: '🤖', brain: '🧠',
  package: '📦', calendar: '📅', hourglass: '⌛', stop_sign: '🛑', construction: '🚧', trophy: '🏆', boom: '💥', link: '🔗',
};

let configured = false;

function configure() {
  if (configured) return;
  configured = true;
  const { marked } = window;
  marked.use({
    gfm: true,
    breaks: false,
    extensions: [
      {
        name: 'mathBlock', level: 'block',
        start: src => { const i = src.indexOf('$$'); return i < 0 ? undefined : i; },
        tokenizer(src) {
          const m = /^\$\$([\s\S]+?)\$\$[ \t]*(?:\n|$)/.exec(src);
          if (m) return { type: 'mathBlock', raw: m[0], text: m[1].trim() };
        },
        renderer: token => `<div class="math-display">${math(token.text, true)}</div>`,
      },
      {
        name: 'mathInline', level: 'inline',
        start: src => { const i = src.indexOf('$'); return i < 0 ? undefined : i; },
        tokenizer(src) {
          const m = /^\$\$([^$\n]+?)\$\$/.exec(src) || /^\$(?![\s$])((?:\\.|[^\\$\n])+?)(?<!\s)\$(?![\w$])/.exec(src);
          if (m) return { type: 'mathInline', raw: m[0], text: m[1], display: m[0].startsWith('$$') };
        },
        renderer: token => math(token.text, token.display),
      },
      {
        name: 'mark', level: 'inline',
        start: src => { const i = src.indexOf('=='); return i < 0 ? undefined : i; },
        tokenizer(src) {
          const m = /^==(?!\s)([^=\n]+?)(?<!\s)==/.exec(src);
          if (m) return { type: 'mark', raw: m[0], tokens: this.lexer.inlineTokens(m[1]) };
        },
        renderer(token) { return `<mark>${this.parser.parseInline(token.tokens)}</mark>`; },
      },
      {
        name: 'emoji', level: 'inline',
        start: src => { const m = /:[a-z0-9_+-]+:/.exec(src); return m ? m.index : undefined; },
        tokenizer(src) {
          const m = /^:([a-z0-9_+-]+):/.exec(src);
          if (m && emojis[m[1]]) return { type: 'emoji', raw: m[0], name: m[1] };
        },
        renderer: token => `<span class="emoji" title=":${token.name}:">${emojis[token.name]}</span>`,
      },
    ],
    renderer: {
      code(token) {
        const info = (token.lang || '').trim();
        const language = (info.split(/[\s[{]/)[0] || '').toLowerCase();
        const rest = info.slice(language.length).trim();
        const code = token.text;
        if (language === 'mermaid') return `<div class="mermaid-diagram"><pre class="mermaid">${escapeHtml(code)}</pre></div>`;
        if (['math', 'latex', 'katex', 'tex'].includes(language)) return `<div class="math-display">${math(code, true)}</div>`;
        if (language === 'svg' && /^\s*<svg[\s>]/.test(code)) return `<div class="svg-figure">${code}</div>`;
        if (['terminal', 'console', 'shell-session', 'term'].includes(language)) return terminal(code, rest.replace(/^["']|["']$/g, ''));
        // reveal.js highlighted lines : ```js [1|3-4], only the first step is kept
        const lines = /\[([\d,\s|-]*)\]/.exec(rest);
        const lang = language === 'plaintext' || language === 'text' || language === 'txt' ? 'plaintext' : language;
        const html = highlighted(code.replace(/\n$/, ''), lang === 'plaintext' ? '' : lang);
        if (lines) {
          const marked = lineSet(lines[1].split('|')[0]);
          const rows = splitLines(html).map((line, i) =>
            `<span class="code-line${marked.has(i + 1) ? ' highlight-line' : ''}${marked.size && !marked.has(i + 1) ? ' dim-line' : ''}" data-line="${i + 1}">${line || ' '}</span>`);
          return `<pre class="code-wrapper"><code class="hljs line-numbers language-${escapeHtml(lang)}">${rows.join('\n')}</code></pre>`;
        }
        return `<pre class="code-wrapper"><code class="hljs language-${escapeHtml(lang || 'plaintext')}">${html}</code></pre>`;
      },
    },
  });
}

export function toHtml(markdown) {
  configure();
  return window.marked.parse(markdown || '');
}

// ====================================================================================================================
// After rendering

/** `class="fragment" data-x='y'` as attributes. */
export function parseAttributes(text) {
  const attributes = [];
  const pattern = /([^"=\s]+?)=(?:"([^"]*)"|'([^']*)'|(\S+))|([^"=\s]+)/g;
  let m;
  while ((m = pattern.exec(text || ''))) {
    if (m[5]) attributes.push([m[5], '']);
    else attributes.push([m[1], m[2] ?? m[3] ?? m[4] ?? '']);
  }
  return attributes;
}

export function applyAttributes(element, text) {
  parseAttributes(text).forEach(([name, value]) => {
    if (!/^[a-zA-Z_:][-a-zA-Z0-9_:.]*$/.test(name) || /^on/i.test(name)) return;
    if (name === 'class') value.split(/\s+/).filter(Boolean).forEach(c => element.classList.add(c));
    else element.setAttribute(name, value);
  });
}

/** reveal.js `<!-- .element: ... -->` comments : the attributes go to the element before the comment, or to its parent. */
function elementAttributes(root) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_COMMENT);
  const comments = [];
  while (walker.nextNode()) comments.push(walker.currentNode);
  comments.forEach(comment => {
    const m = /^\s*\.element\s*:\s*([\s\S]*?)\s*$/.exec(comment.nodeValue);
    if (!m) return;
    let target = null;
    for (let node = comment.previousSibling; node; node = node.previousSibling) {
      if (node.nodeType === Node.ELEMENT_NODE) { target = node; break; }
      if (node.nodeType === Node.TEXT_NODE && node.nodeValue.trim()) break;
    }
    target = target || comment.parentElement;
    if (target && target !== root) applyAttributes(target, m[1]);
    comment.remove();
  });
}

function callouts(root) {
  const kinds = { NOTE: 'Note', TIP: 'Tip', IMPORTANT: 'Important', WARNING: 'Warning', CAUTION: 'Caution' };
  root.querySelectorAll('blockquote').forEach(quote => {
    const first = quote.firstElementChild;
    if (!first || first.tagName !== 'P') return;
    const m = /^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*/i.exec(first.innerHTML);
    if (!m) return;
    const kind = m[1].toUpperCase();
    first.innerHTML = first.innerHTML.slice(m[0].length);
    if (!first.innerHTML.trim()) first.remove();
    quote.classList.add('callout', `callout-${kind.toLowerCase()}`);
    const title = document.createElement('div');
    title.className = 'callout-title';
    title.textContent = kinds[kind];
    quote.prepend(title);
  });
}

/** Task lists : GitHub's `- [ ]` and `- [x]`, and Obsidian's `- [/]` in progress, as boxes drawn in the text color. */
const TASK_LABELS = { todo: 'To do', done: 'Done', 'in-progress': 'In progress' };
const TASK_LABEL = state => TASK_LABELS[state];

function taskBox(state) {
  const box = document.createElement('span');
  box.className = `task-box task-${state}`;
  box.setAttribute('role', 'img');
  box.setAttribute('aria-label', TASK_LABEL(state));
  return box;
}

function tasks(root) {
  root.querySelectorAll('li').forEach(li => {
    // the first content of the item, or of its first paragraph (loose lists)
    let holder = li;
    let first = li.firstChild;
    while (first && first.nodeType === Node.TEXT_NODE && !first.nodeValue.trim()) first = first.nextSibling;
    if (first?.nodeType === Node.ELEMENT_NODE && first.tagName === 'P') { holder = first; first = first.firstChild; }
    let state = null;
    if (first?.nodeType === Node.ELEMENT_NODE && first.matches('input[type="checkbox"]')) {
      state = first.hasAttribute('checked') ? 'done' : 'todo';
      const next = first.nextSibling;
      first.remove();
      if (next?.nodeType === Node.TEXT_NODE) next.nodeValue = next.nodeValue.replace(/^\s+/, '');
      holder.insertBefore(taskBox(state), next);
    } else if (first?.nodeType === Node.TEXT_NODE) {
      const m = /^\s*\[\/\](\s+|$)/.exec(first.nodeValue);
      if (!m) return;
      state = 'in-progress';
      first.nodeValue = first.nodeValue.slice(m[0].length);
      holder.insertBefore(taskBox(state), first);
    }
    if (state) li.classList.add('task-list-item', `task-${state}`);
  });
}

/** reveal-md anchors : `## My Title` is reached by `#my-title`. Not ids, which the player and the themes use. */
export function anchorOf(text) {
  return text.trim().toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, '').replace(/\s+/g, '-');
}

function anchors(root) {
  root.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach(heading => { heading.dataset.anchor = anchorOf(heading.textContent); });
}

const absolute = /^([a-z][a-z0-9+.-]*:|\/\/|\/|#)/i;

/** A deck relative URL, `images/x.png`, as served, `/files/source/dir/images/x.png?v=stamp`. */
export function resolveUrl(url, context) {
  if (!url || absolute.test(url.trim())) return url;
  const resolved = new URL(url, window.location.origin + context.base);
  if (context.stamp) resolved.searchParams.set('v', context.stamp);
  return resolved.pathname + resolved.search + resolved.hash;
}

/** A link to another deck of a source, `../other/index.md`, as the player URL. */
function deckLink(href, context) {
  if (!href || absolute.test(href.trim())) return null;
  const resolved = new URL(href, window.location.origin + context.base);
  const m = /^\/files\/([^/]+)\/(.+\.(md|markdown))$/i.exec(resolved.pathname);
  if (!m) return null;
  const id = `${decodeURIComponent(m[1])}:${m[2].split('/').map(decodeURIComponent).join('/')}`;
  return `/play?id=${encodeURIComponent(id)}${resolved.hash || ''}`;
}

function resolveUrls(root, context) {
  root.querySelectorAll('[src]').forEach(el => el.setAttribute('src', resolveUrl(el.getAttribute('src'), context)));
  root.querySelectorAll('video[poster]').forEach(el => el.setAttribute('poster', resolveUrl(el.getAttribute('poster'), context)));
  root.querySelectorAll('object[data]').forEach(el => el.setAttribute('data', resolveUrl(el.getAttribute('data'), context)));
  root.querySelectorAll('img[srcset], source[srcset]').forEach(el => el.removeAttribute('srcset'));
  root.querySelectorAll('[data-src]').forEach(el => el.setAttribute('data-src', resolveUrl(el.getAttribute('data-src'), context)));
  root.querySelectorAll('a[href]').forEach(a => {
    const href = a.getAttribute('href');
    const deck = deckLink(href, context);
    if (deck) a.setAttribute('href', deck);
    else if (!absolute.test(href.trim())) a.setAttribute('href', resolveUrl(href, { base: context.base }));
    if (/^https?:/i.test(a.getAttribute('href'))) { a.target = '_blank'; a.rel = 'noopener noreferrer'; }
  });
  root.querySelectorAll('[style*="url("]').forEach(el => {
    el.setAttribute('style', el.getAttribute('style').replace(/url\((['"]?)([^'")]+)\1\)/g, (all, q, url) => `url(${q}${resolveUrl(url, context)}${q})`));
  });
}

/** Renders a slide markdown into the element, then applies the extensions needing the DOM. Mermaid diagrams are drawn by `drawDiagrams`, once visible. */
export function renderInto(element, markdown, context) {
  element.innerHTML = toHtml(markdown);
  elementAttributes(element);
  callouts(element);
  tasks(element);
  resolveUrls(element, context);
  anchors(element);
  element.querySelectorAll('table').forEach(table => {
    if (!table.parentElement.classList.contains('table-wrapper')) table.classList.add('slide-table');
  });
}

let diagramCount = 0;

/** Draws the mermaid diagrams of an element : mermaid measures text, the element must be displayed. */
export async function drawDiagrams(element) {
  const pending = [...element.querySelectorAll('pre.mermaid:not([data-processed])')];
  if (!pending.length) return;
  const mermaid = await ensureLibrary('mermaid').catch(() => null);
  if (!mermaid) return;
  for (const pre of pending) {
    const source = pre.textContent;
    try {
      const { svg } = await mermaid.render(`mermaid-${Date.now()}-${diagramCount++}`, source);
      pre.innerHTML = svg;
      pre.setAttribute('data-processed', 'true');
    } catch (e) {
      pre.setAttribute('data-processed', 'error');
      pre.classList.add('mermaid-error');
      pre.textContent = `${source}\n\n${e.message || e}`;
    }
  }
}

export { escapeHtml };
