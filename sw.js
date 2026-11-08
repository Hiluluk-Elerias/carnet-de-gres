const SHELL='shell-v5',MEDIA='media-v1';
const FILES=['./','index.html','manifest.webmanifest','icon-192.png','icon-512.png','apple-touch-icon.png'];
self.addEventListener('install',e=>e.waitUntil(caches.open(SHELL).then(c=>c.addAll(FILES.map(f=>new Request(f,{cache:'reload'})))).then(()=>self.skipWaiting())));
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(n=>n!==SHELL&&n!==MEDIA).map(n=>caches.delete(n)))).then(()=>self.clients.claim())));
const isMedia=u=>u.hostname==='assets.boolder.com'||u.hostname==='data.geopf.fr'||u.hostname==='fonts.googleapis.com'||u.hostname==='fonts.gstatic.com';
self.addEventListener('fetch',e=>{
  if(e.request.method!=='GET')return;
  const u=new URL(e.request.url);
  if(isMedia(u)){
    e.respondWith(caches.open(MEDIA).then(c=>c.match(u.href).then(h=>h||fetch(e.request).then(r=>{if(r.ok||r.type==='opaque')c.put(u.href,r.clone());return r}))));
    return;
  }
  if(u.origin!==location.origin)return;
  e.respondWith(caches.open(SHELL).then(c=>c.match(e.request,{ignoreSearch:true}).then(h=>{
    const net=fetch(e.request).then(r=>{if(r.ok)c.put(e.request,r.clone());return r});
    if(h){e.waitUntil(net.catch(()=>{}));return h}
    return net;
  })));
});
