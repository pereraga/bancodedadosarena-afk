// Service Worker básico para permitir instalação PWA no Android
const CACHE_NAME = 'totemplay-v1';
const ASSETS_TO_CACHE = [
  './',
  './index.html',
  './player.html',
  './admin.html',
  './manifest.json'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(ASSETS_TO_CACHE);
    })
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME) {
            return caches.delete(key);
          }
        })
      );
    })
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  // Para requisições normais de página, tenta rede primeiro, depois cache
  event.respondWith(
    fetch(event.request).catch(() => caches.match(event.request))
  );
});
