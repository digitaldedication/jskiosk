// Jos Scholman Showroom — service worker.
//
// Doel: alle media (foto's + video's) vooraf naar de lokale cache halen zodat
// het kiosk-scherm nooit tijdens het afspelen iets van internet hoeft te
// laden. Bij elke start van de app stuurt de pagina een 'sync'-bericht: de
// worker haalt dan de nieuwste index per categorie op, downloadt nieuwe
// bestanden en ruimt verwijderde bestanden op.
//
// De Android-APK gebruikt zijn eigen native cache (MediaCache.kt); daar wordt
// deze worker niet geregistreerd.

const CACHE_NAME = 'jskiosk-media-v1';
const CATEGORIES = ['infra', 'groen', 'sport'];
const SHELL = ['index.html', 'background-home.jpg', 'back-button.png', 'swipe-icon.png'];

function scopeUrl(path) {
  return new URL(path, self.registration.scope).href;
}

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    // Best-effort: één ontbrekend shell-bestand mag installatie niet blokkeren.
    await Promise.all(SHELL.map((p) =>
      cache.add(scopeUrl(p)).catch(() => {})
    ));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names.filter((n) => n !== CACHE_NAME).map((n) => caches.delete(n)));
    await self.clients.claim();
  })());
});

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'sync') {
    event.waitUntil(syncAll());
  }
});

async function broadcast(msg) {
  const clients = await self.clients.matchAll({ includeUncontrolled: true });
  clients.forEach((c) => c.postMessage(msg));
}

async function syncAll() {
  const cache = await caches.open(CACHE_NAME);
  const keep = new Set();
  const toDownload = [];

  try {
    // 1. Shell altijd verversen (klein, en zo komen HTML-updates binnen).
    for (const p of SHELL) {
      const url = scopeUrl(p);
      keep.add(url);
      try {
        const res = await fetch(url, { cache: 'no-store' });
        if (res.ok) await cache.put(url, res);
      } catch { /* offline: bestaande cache blijft geldig */ }
    }

    // 2. Per categorie: index ophalen en bepalen wat er nog mist.
    for (const cat of CATEGORIES) {
      const indexUrl = scopeUrl(`media/${cat}/index.json`);
      keep.add(indexUrl);
      let items = null;
      try {
        const res = await fetch(indexUrl, { cache: 'no-store' });
        if (res.ok) {
          const body = await res.text();
          items = JSON.parse(body);
          await cache.put(indexUrl, new Response(body, {
            headers: { 'Content-Type': 'application/json' },
          }));
        }
      } catch { /* offline */ }
      if (!items) continue;

      const files = items.filter((i) => i.file).map((i) => ({ name: i.file, size: i.size }));
      files.push({ name: 'cover.jpg', size: null });
      for (const f of files) {
        const url = scopeUrl(`media/${cat}/${encodeURIComponent(f.name)}`);
        keep.add(url);
        const cached = await cache.match(url);
        if (!cached) { toDownload.push(url); continue; }
        // Bestand veranderd op de server (bv. geoptimaliseerde versie)?
        // Vergelijk groottes en vervang de cache-kopie bij verschil. De
        // index levert de grootte; voor cover.jpg doen we een HEAD-request.
        let expected = f.size;
        if (expected == null) {
          try {
            const head = await fetch(url, { method: 'HEAD', cache: 'no-store' });
            expected = head.ok ? Number(head.headers.get('content-length')) : null;
          } catch { expected = null; }
        }
        const have = Number(cached.headers.get('content-length'));
        if (expected && have && expected !== have) toDownload.push(url);
      }
    }

    // 3. Ontbrekende media downloaden, met voortgang naar de pagina.
    const total = toDownload.length;
    let done = 0;
    if (total > 0) await broadcast({ type: 'sync-progress', done, total });
    for (const url of toDownload) {
      try {
        const res = await fetch(url, { cache: 'no-store' });
        if (res.ok) await cache.put(url, res);
      } catch { /* volgende sync opnieuw proberen */ }
      done += 1;
      await broadcast({ type: 'sync-progress', done, total });
    }

    // 4. Verwijderde bestanden opruimen.
    for (const req of await cache.keys()) {
      if (!keep.has(req.url)) await cache.delete(req);
    }

    await broadcast({ type: 'sync-done', downloaded: total });
  } catch (e) {
    await broadcast({ type: 'sync-done', downloaded: 0, error: String(e) });
  }
}

// Cache-first voor alles binnen de eigen site: media speelt altijd direct
// vanaf schijf. Nieuw materiaal komt via syncAll() binnen, niet via de
// fetch-handler.
self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (!req.url.startsWith(self.registration.scope)) return;

  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME);
    // Navigaties naar de scope-root op index.html laten uitkomen
    // (maar bv. /admin/ met rust laten).
    const isRootNav = req.mode === 'navigate' &&
      (req.url.split('?')[0] === self.registration.scope ||
       req.url.split('?')[0] === scopeUrl('index.html'));
    const lookup = isRootNav ? scopeUrl('index.html') : req;
    // Query strings (zoals oude cache-busters) negeren bij het matchen.
    const cached = await cache.match(lookup, { ignoreSearch: true });
    if (cached) {
      const range = req.headers.get('range');
      if (range) return rangeResponse(cached, range);
      return cached;
    }
    try {
      const res = await fetch(req);
      // Alleen volledige 200-antwoorden cachen (geen 206-fragmenten).
      if (res.ok && res.status === 200) {
        cache.put(req.url.split('?')[0], res.clone()).catch(() => {});
      }
      return res;
    } catch (e) {
      return new Response('offline', { status: 503 });
    }
  })());
});

// Video-elementen vragen om byte-ranges; een cache geeft alleen volledige
// antwoorden. Zonder dit stukje weigert de videospeler soms te starten of
// te zoeken.
async function rangeResponse(cached, rangeHeader) {
  const m = /bytes=(\d+)-(\d*)/.exec(rangeHeader);
  if (!m) return cached;
  const blob = await cached.blob();
  const start = Number(m[1]);
  const end = m[2] ? Math.min(Number(m[2]), blob.size - 1) : blob.size - 1;
  if (start >= blob.size) {
    return new Response(null, {
      status: 416,
      headers: { 'Content-Range': `bytes */${blob.size}` },
    });
  }
  const part = blob.slice(start, end + 1);
  return new Response(part, {
    status: 206,
    statusText: 'Partial Content',
    headers: {
      'Content-Type': cached.headers.get('Content-Type') || 'application/octet-stream',
      'Content-Range': `bytes ${start}-${end}/${blob.size}`,
      'Content-Length': String(part.size),
      'Accept-Ranges': 'bytes',
    },
  });
}
