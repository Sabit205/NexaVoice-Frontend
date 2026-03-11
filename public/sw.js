self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(clients.claim());
});

self.addEventListener('fetch', (event) => {
  // Simple pass-through for network requests. 
  // This is enough to satisfy PWA install requirements.
  event.respondWith(fetch(event.request));
});