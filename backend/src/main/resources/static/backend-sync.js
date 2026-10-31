const SBT_API=window.SBT_API||'/api';
async function sbtFetch(path,options={}){
  const s=typeof getSession==='function'?getSession():null;
  const h={'Content-Type':'application/json',...(options.headers||{})};
  if(s?.token)h.Authorization='Bearer '+s.token;
  const r=await fetch(SBT_API+path,{...options,headers:h});
  let j=null;try{j=await r.json()}catch{}
  if(!r.ok)throw new Error(j?.message||'Backend request failed');
  return j;
}
async function sbtPull(){
  try{
    const session=typeof getSession==='function'?getSession():null; const c=await sbtFetch(session?.role==='admin'?'/admin/catalog':'/catalog');
    localStorage.setItem('adminBuses',JSON.stringify((c.buses||[]).map(x=>[x.busNumber,x.operator||x.operatorName,x.type,x.route||'',x.status,x.totalSeats,x.registrationNumber||'',x.id])));
    localStorage.setItem('adminRoutes',JSON.stringify((c.routes||[]).map(x=>[x.routeCode,x.origin,x.destination,x.distance||'',x.stops||0,x.status,x.duration||'',x.id])));
    localStorage.setItem('adminSchedules',JSON.stringify((c.schedules||[]).map(x=>[x.busNumber,x.route,x.depart,x.arrive,(x.fare==null?'Not configured':'₹'+Number(x.fare).toFixed(2)),x.seats,x.status,x.travelDate,x.scheduleId,x.busId,x.routeId,x.availableSeats])));
    const s=session;
    if(s?.role==='customer'){
      const b=await sbtFetch('/bookings/my');
      localStorage.setItem('sbtBookings',JSON.stringify(b||[]));
    } else if(s?.role==='admin'){
      const [b,u]=await Promise.all([sbtFetch('/bookings/all'),sbtFetch('/users')]);
      localStorage.setItem('sbtBookings',JSON.stringify(b||[]));
      localStorage.setItem('sbtUsers',JSON.stringify(u||[]));
    }
    window.dispatchEvent(new StorageEvent('storage',{key:'sbtCatalogVersion'}));
  }catch(e){localStorage.setItem('adminBuses','[]');localStorage.setItem('adminRoutes','[]');localStorage.setItem('adminSchedules','[]');console.warn('Backend sync failed:',e.message)}
}
window.addEventListener('DOMContentLoaded',()=>sbtPull());
