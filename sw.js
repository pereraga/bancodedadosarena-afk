// Service Worker oficial compatível com PWABuilder e Android WebAPK
const CACHE_NAME = 'totem-central-v9';
const OFFLINE_URL = '/screen';

const ASSETS = [
  '/',
  '/screen',
  '/manifest.json',
  '/manifest-screen.json',
  '/icon-192.png',
  '/icon-512.png',
  '/totem-central.js'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(ASSETS);
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
            console.log('Removendo cache antigo:', key);
            return caches.delete(key);
          }
        })
      );
    })
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const url = event.request.url;

  // Não interceptar requisições de stream de vídeo ou Supabase API
  if (url.includes('/api/') || url.includes('supabase.co') || url.includes('googlevideo.com') || url.includes('drive.google.com') || url.includes('youtube.com')) {
    return;
  }

  // Network-First para páginas e assets: sempre busca o código atualizado da Vercel
  event.respondWith(
    fetch(event.request)
      .then((networkResponse) => {
        if (networkResponse && networkResponse.status === 200 && event.request.method === 'GET') {
          const responseToCache = networkResponse.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(event.request, responseToCache);
          });
        }
        return networkResponse;
      })
      .catch(() => {
        return caches.match(event.request).then((cached) => {
          if (cached) return cached;
          if (event.request.mode === 'navigate') {
            return caches.match(OFFLINE_URL);
          }
        });
      })
  );
});
