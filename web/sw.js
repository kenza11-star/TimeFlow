// TimeFlow service worker: cache offline + tombol Tunda/Stop pada notifikasi alarm.
const C='timeflow-v18',ASSETS=['./','index.html','manifest.json','icon.svg','icon-192.png','icon-512.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(C).then(c=>c.addAll(ASSETS)).catch(()=>{}).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==C).map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const r=e.request;if(r.method!=='GET'||!r.url.startsWith(self.location.origin))return;
  e.respondWith(fetch(r).then(res=>{if(res&&res.ok){const c=res.clone();caches.open(C).then(x=>x.put(r,c)).catch(()=>{})}return res})
    .catch(()=>caches.match(r).then(x=>x||caches.match('index.html'))));
});
self.addEventListener('notificationclick',e=>{
  e.notification.close();const action=e.action||'open';
  e.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(cs=>{
    if(cs.length){cs[0].postMessage({tf:'act',action});return cs[0].focus?cs[0].focus():null}
    return self.clients.openWindow('./');
  }));
});
