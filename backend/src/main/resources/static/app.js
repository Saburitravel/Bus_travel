/* Saburi Swami Travel - customer application. Backend is authoritative for bookings and seat state; localStorage is used only for UI/cache state. */

const TAX_RATE=.06, CONVENIENCE_FEE=5, MAX_SEATS=6, HOLD_MS=2*60*1000;
const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
let services=[];
let backendCatalog=null;
let state={from:'Nashik',to:'Kopargaon',date:'',service:null,seats:[],method:'UPI',amount:0,bookingId:null,holdId:null};
let ticketToPrint=null;
let seatStatuses=new Map();
let seatOperationPending=false;
let seatRefreshPending=false;
let lastSeatHoldRefresh=0;

function readJSON(key,fallback){try{const v=JSON.parse(localStorage.getItem(key)||'null');return v??fallback}catch{return fallback}}
function writeJSON(key,value){localStorage.setItem(key,JSON.stringify(value));}
function money(n){return new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR',maximumFractionDigits:0}).format(Number(n)||0)}
function normalizeCity(v){return String(v||'').trim().replace(/\s+/g,' ').toLowerCase()}
function routeKey(v){return normalizeCity(v).replace(/\s*→\s*/g,'→')}
function todayISO(){const d=new Date();d.setMinutes(d.getMinutes()-d.getTimezoneOffset());return d.toISOString().slice(0,10)}
function toast(msg){const t=$('#toast');if(!t)return;t.textContent=msg;t.classList.add('show');clearTimeout(window.__toast);window.__toast=setTimeout(()=>t.classList.remove('show'),2400)}
const PAGE_TITLES={home:'Home',results:'Bus Search Results',seats:'Select Seats',passenger:'Passenger Details',payment:'Payment',confirmation:'Booking Status',bookings:'My Trips',offers:'Offers',support:'Help & Support',profile:'My Account',tracking:'Track Bus'};
function setActiveCustomerNav(pageName){
  const nav=$('#customerNav');if(!nav)return;
  const routePages=['results','seats','passenger','payment','confirmation','tracking'];
  const activeKey=routePages.includes(pageName)?'routes':({bookings:'trips',offers:'offers',support:'help',profile:'account'}[pageName]||'home');
  nav.querySelectorAll('a[data-nav-page]').forEach(link=>{const active=link.dataset.navPage===activeKey;link.classList.toggle('is-current',active);if(active)link.setAttribute('aria-current','page');else link.removeAttribute('aria-current')});
}
function show(id){const el=$(id);if(!el)return false;$$('.page').forEach(p=>p.classList.add('hidden'));el.classList.remove('hidden');const pageName=id.replace(/^#/,'');document.title=(PAGE_TITLES[pageName]||'Travel')+' | Saburi Swami Travel';document.body.dataset.currentPage=pageName;setActiveCustomerNav(pageName);window.scrollTo({top:0,behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'});if(pageName==='seats'&&state.service)setTimeout(()=>refreshSeatStatuses({announce:false}),0);return true}
function currentUser(){return typeof getCurrentUser==='function'?getCurrentUser():null}
function saveBookingReturnState(){try{sessionStorage.setItem('sbtPendingBookingState',JSON.stringify({from:state.from,to:state.to,date:state.date,service:state.service,seats:state.seats,method:state.method,amount:state.amount}))}catch{}}
function takeBookingReturnState(){try{const raw=sessionStorage.getItem('sbtPendingBookingState');if(!raw)return null;sessionStorage.removeItem('sbtPendingBookingState');return JSON.parse(raw)}catch{return null}}

function getAdminBuses(){return readJSON('adminBuses',[])}
function getAdminSchedules(){return readJSON('adminSchedules',[])}
function getPublicServices(){
  const buses=getAdminBuses(); const schedules=getAdminSchedules();
  const busMap=new Map(buses.map(b=>[String(b[0]).trim(),b]));
  return schedules.map((s,i)=>{
    const bus=busMap.get(String(s[0]).trim());
    if(!bus || String(bus[4]).toLowerCase()!=='active' || String(s[6]||'Open').toLowerCase()!=='open') return null;
    if(String(s[7]||'') && String(s[7])!==String(state.date)) return null;
    const fare=Number(String(s[4]||'').replace(/[^0-9.]/g,''))||0;
    return {id:`${s[0]}-${i}`,scheduleId:String(s[8]||`SCH-${i}-${s[0]}`),busId:String(s[0]),name:String(bus[0]),operator:String(bus[1]||''),type:String(bus[2]||'Seater'),ac:/ac|volvo/i.test(String(bus[2])),route:String(s[1]),dep:String(s[2]),arr:String(s[3]),price:fare,rating:null,seats:Number(s[5])||0,availableSeats:Number(s[11]??s[5])||0};
  }).filter(Boolean);
}
function refreshServices(){services=getPublicServices();return services}
async function loadBackendCatalog(){
  try{
    const c=await api('/catalog');
    backendCatalog=c||null;
    services=(c?.schedules||[]).map(s=>({
      id:String(s.id),scheduleId:String(s.scheduleId||s.id),busId:String(s.busId),routeId:String(s.routeId||''),journeyStops:Array.isArray(s.journeyStops)?s.journeyStops:[],from:String(s.from||''),to:String(s.to||''),fare:Number(s.fare??0),name:String(s.bus||s.busNumber||''),
      operator:String(s.operator||''),type:String(s.type||'Seater'),ac:/ac|volvo/i.test(String(s.type||'')),
      route:`${s.from||''} → ${s.to||''}`,dep:String(s.depart||''),arr:String(s.arrive||''),price:Number(s.fare??s.price??0),
      rating:null,seats:Number(s.seats||0),availableSeats:Number(s.availableSeats??0),travelDate:String(s.travelDate||''),status:String(s.status||'Open')
    }));
    renderTodaysRunningBuses();
    return services;
  }catch(e){
    backendCatalog=null;
    return refreshServices();
  }
}

function pruneHolds(){return readJSON('sbtSeatHolds',{});}
function seatLabel(seat){
  const number=Number(seat);
  return `${Math.ceil(number/4)}${['A','B','C','D'][(number-1)%4]||''}`;
}
async function fetchSeatStatuses(){
  if(!state.service?.scheduleId)throw new Error('Choose a bus service before selecting seats.');
  const rows=await api(`/schedules/${encodeURIComponent(state.service.scheduleId)}/seats?from=${encodeURIComponent(state.from)}&to=${encodeURIComponent(state.to)}&holdToken=${encodeURIComponent(state.holdId||'')}`);
  return new Map((rows||[]).map(row=>[String(row.seat),String(row.status||'UNAVAILABLE')]));
}
function renderSeatMap(){
  const map=$('#seatMap');if(!map||!state.service)return;
  const seatCount=Math.max(0,Number(state.service.seats)||0),groupCount=Math.ceil(seatCount/4),frontCount=Math.ceil(groupCount/2);
  const renderBank=(firstGroup,lastGroup,bankLabel)=>Array.from({length:Math.max(0,lastGroup-firstGroup+1)},(_,offset)=>{
    const group=firstGroup+offset;
    const seats=Array.from({length:4},(_,position)=>{
      const number=(group-1)*4+position+1;
      if(number>seatCount)return '';
      const seat=String(number),status=seatStatuses.get(seat)||'UNAVAILABLE';
      const selected=state.seats.includes(seat),booked=status==='BOOKED',held=status==='HELD';
      const kind=selected?'selected':booked?'booked':held?'held':status==='AVAILABLE'?'available':'booked';
      const disabled=seatOperationPending||kind==='booked'||kind==='held';
      const stateLabel=selected?'selected':booked?'booked':held?'temporarily held':'available';
      return `<button class="seat ${kind}" type="button" data-seat="${seat}" aria-label="Seat ${seatLabel(seat)}, ${stateLabel}" aria-pressed="${selected}" title="${stateLabel[0].toUpperCase()+stateLabel.slice(1)}${disabled&&seatOperationPending?' · Updating availability':''}" ${disabled?'disabled':''} onclick="toggleSeat('${seat}')">${seatLabel(seat)}</button>`;
    }).join('');
    return `<div class="seat-group" aria-label="${bankLabel} row ${group}">${seats}</div>`;
  }).join('');
  map.innerHTML=`<div class="seat-bank front-bank" style="--seat-groups:${Math.max(1,frontCount)}" aria-label="Front seats">${renderBank(1,frontCount,'Front')}</div><div class="bus-aisle" aria-hidden="true"><span></span></div><div class="seat-bank rear-bank" style="--seat-groups:${Math.max(1,groupCount-frontCount)}" aria-label="Rear seats">${renderBank(frontCount+1,groupCount,'Rear')}</div>`;
}
function renderSeatSummary(){
  const selected=state.seats.slice().sort((a,b)=>Number(a)-Number(b));
  $('#seatSummary').innerHTML=selected.length?selected.map(seat=>`<button class="selected-seat-tag" type="button" onclick="toggleSeat('${seat}')" ${seatOperationPending?'disabled':''} aria-label="Remove seat ${seatLabel(seat)}">${seatLabel(seat)} <span aria-hidden="true">×</span></button>`).join(''):'<span class="empty-seat-selection">No seats selected</span>';
  $('#selectedSeatCount').textContent=String(selected.length);
  const confirm=$('#continuePassenger');if(confirm)confirm.disabled=!selected.length||seatOperationPending;
}
function renderSeatSelection(){
  const base=state.seats.length*(Number(state.service?.price)||0),tax=Math.round(base*TAX_RATE);
  state.amount=state.seats.length?base+tax+CONVENIENCE_FEE:0;
  renderSeatMap();
  renderSeatSummary();
}
async function refreshSeatStatuses({announce=true}={}){
  if(!state.service||seatRefreshPending||seatOperationPending)return;
  seatRefreshPending=true;
  const wasPending=seatOperationPending;
  seatOperationPending=true;
  renderSeatSelection();
  try{
    seatStatuses=await fetchSeatStatuses();
    const unavailable=state.seats.filter(seat=>seatStatuses.get(seat)!=='AVAILABLE');
    if(unavailable.length){
      state.seats=state.seats.filter(seat=>!unavailable.includes(seat));
      if(state.seats.length){
        try{await holdSelectedSeats();}
        catch(e){
          try{await releaseOwnHold();}catch(releaseError){toast(releaseError.message||'Unable to release the expired seat hold.');}
          state.seats=[];
          state.holdId=null;
          toast(e.message||'Your remaining seats could not be held. Please choose again.');
        }
      }else{
        try{await releaseOwnHold();}catch(e){toast(e.message||'Unable to release the expired seat hold.');}
      }
      if(announce)toast(`${unavailable.map(seatLabel).join(', ')} ${unavailable.length===1?'is':'are'} no longer available. Please choose another seat.`);
    }else if(state.seats.length&&!wasPending&&Date.now()-lastSeatHoldRefresh>60_000){
      await holdSelectedSeats();
    }
    renderSeatSelection();
  }catch(e){
    if(announce)toast(e.message||'Unable to refresh seat availability. Please try again.');
  }finally{
    seatOperationPending=wasPending;
    seatRefreshPending=false;
    renderSeatSelection();
  }
}
async function holdSelectedSeats(){
  if(!state.service)return;
  if(!state.seats.length){await releaseOwnHold();return;}
  const r=await api('/seats/hold',{method:'POST',body:JSON.stringify({scheduleId:Number(state.service.scheduleId),from:state.from,to:state.to,seats:state.seats,holdToken:state.holdId||''})});
  state.holdId=r.holdToken;
  lastSeatHoldRefresh=Date.now();
}
async function releaseOwnHold(){if(!state.holdId)return;await api('/seats/release',{method:'POST',body:JSON.stringify({holdToken:state.holdId})});state.holdId=null;lastSeatHoldRefresh=0;}

async function fetchJourneyServices(from,to,date){
  const rows=await api(`/schedules/search?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&date=${encodeURIComponent(date)}`);
  return (rows||[]).map(s=>({
    id:String(s.id||s.scheduleId),scheduleId:String(s.scheduleId||s.id),busId:String(s.busId),name:String(s.bus||s.busNumber||''),
    operator:String(s.operator||''),type:String(s.type||'Seater'),ac:/ac|volvo/i.test(String(s.type||'')),
    route:`${s.from||from} → ${s.to||to}`,dep:String(s.depart||''),arr:String(s.arrive||''),
    price:Number(s.fare??s.price??0),fullRouteFare:Number(s.fullRouteFare??s.fare??s.price??0),
    fromIndex:Number(s.fromIndex??0),toIndex:Number(s.toIndex??0),journeyStops:Array.isArray(s.journeyStops)?s.journeyStops:[],
    rating:null,seats:Number(s.seats||0),availableSeats:Number(s.availableSeats??0),travelDate:String(s.travelDate||date),status:String(s.status||'Open')
  }));
}
async function fetchUpcomingRouteServices(from,to){
  const rows=await api(`/schedules/route?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
  return (rows||[]).map(s=>({
    id:String(s.id||s.scheduleId),scheduleId:String(s.scheduleId||s.id),busId:String(s.busId),name:String(s.bus||s.busNumber||''),
    operator:String(s.operator||''),type:String(s.type||'Seater'),ac:/ac|volvo/i.test(String(s.type||'')),
    route:`${s.from||from} → ${s.to||to}`,dep:String(s.depart||''),arr:String(s.arrive||''),
    price:Number(s.fare??s.price??0),fullRouteFare:Number(s.fullRouteFare??s.fare??s.price??0),
    fromIndex:Number(s.fromIndex??0),toIndex:Number(s.toIndex??0),journeyStops:Array.isArray(s.journeyStops)?s.journeyStops:[],
    rating:null,seats:Number(s.seats||0),availableSeats:Number(s.availableSeats??0),travelDate:String(s.travelDate||''),status:String(s.status||'Open')
  }));
}

function scheduleHasDeparted(schedule){
  const date=String(schedule?.travelDate||todayISO());
  if(date<todayISO())return true;
  if(date>todayISO())return false;
  const departure=String(schedule?.depart||'').slice(0,5);
  if(!/^\\d{2}:\\d{2}$/.test(departure))return false;
  const now=new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Kolkata',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date());
  return departure<=now;
}
function orderedStopsForSchedule(schedule){
  const points=Array.isArray(schedule?.journeyStops)?schedule.journeyStops:[];
  const clean=points.map(x=>String(x||'').trim()).filter(Boolean);
  const unique=[];
  clean.forEach(x=>{if(!unique.some(y=>normalizeCity(y)===normalizeCity(x)))unique.push(x)});
  return unique.length>=2?unique:[String(schedule?.from||'').trim(),String(schedule?.to||'').trim()].filter(Boolean);
}
function makeStopOptions(points,placeholder){
  return `<option value="">${placeholder}</option>`+points.map(p=>`<option value="${escapeAttr(p)}">${escapeHTML(p)}</option>`).join('');
}
function journeyPickerMarkup(schedule,id,scope){
  const points=orderedStopsForSchedule(schedule);
  return `<div class="journey-stop-picker" ${scope==='today'?'hidden':''} style="grid-column:1/-1;display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;align-items:end;margin-top:12px;padding:14px;border:1px solid #dbe7fb;border-radius:12px;background:#f7faff">
    <label>Starting / Boarding point<select data-${scope}-from="${escapeAttr(id)}">${makeStopOptions(points,'Select starting point')}</select></label>
    <label>Dropping point<select data-${scope}-to="${escapeAttr(id)}">${makeStopOptions(points,'Select dropping point')}</select></label>
    <button type="button" class="primary" data-${scope}-view="${escapeAttr(id)}" disabled>VIEW SEATS</button>
  </div>`;
}
function validForwardPair(points,from,to){
  const a=points.findIndex(x=>normalizeCity(x)===normalizeCity(from));
  const b=points.findIndex(x=>normalizeCity(x)===normalizeCity(to));
  return a>=0&&b>a;
}
function renderPopularRouteResults(from,to,rows){
  const host=$('#busList');
  const groups=[...new Set(rows.map(x=>x.travelDate).filter(Boolean))].sort();
  host.innerHTML=`<section class="panel" style="padding:18px;margin-bottom:16px">
    <div class="panel-head"><div><span class="eyebrow">POPULAR ROUTE</span><h2>${escapeHTML(from)} → ${escapeHTML(to)}</h2><p class="section-subtitle">Choose an operating date, then select boarding and dropping points for a bus</p></div><button class="secondary" type="button" id="popularBackHome">Back</button></div>
    <div id="popularDateChoices" style="display:flex;gap:8px;flex-wrap:wrap;margin:14px 0">${groups.map((d,i)=>`<button type="button" class="chip ${i===0?'active':''}" data-pop-date="${escapeAttr(d)}">${new Date(d+'T00:00:00').toLocaleDateString('en-IN',{weekday:'short',day:'2-digit',month:'short',year:'numeric'})}</button>`).join('')}</div>
    <div id="popularDateBusList"></div>
  </section>`;
  $('#popularBackHome')?.addEventListener('click',()=>show('#home'));
  const dateHost=$('#popularDateChoices');
  dateHost?.addEventListener('click',e=>{
    const btn=e.target.closest('[data-pop-date]');if(!btn)return;
    dateHost.querySelectorAll('[data-pop-date]').forEach(x=>x.classList.toggle('active',x===btn));
    renderPopularBusesForDate(btn.dataset.popDate,rows);
  });
  if(groups.length)renderPopularBusesForDate(groups[0],rows);
  else $('#popularDateBusList').innerHTML='<div class="panel"><p>No upcoming operating dates are available for this route.</p></div>';
}
function renderPopularBusesForDate(date,allRows){
  const host=$('#popularDateBusList');if(!host)return;
  const rows=allRows.filter(s=>String(s.travelDate)===String(date));
  host.innerHTML=rows.length?rows.map((s,i)=>{
    const id=`pop-${s.scheduleId}-${i}`;
    return `<article class="bus-card popular-bus-option" style="margin:12px 0;padding:16px;display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:14px;align-items:center">
      <div class="bus-name"><strong>${escapeHTML(s.name)}</strong><small>${escapeHTML(s.type||'')}</small></div>
      <div><strong>${escapeHTML(s.dep||'—')} → ${escapeHTML(s.arr||'—')}</strong><small>Departure / Arrival</small></div>
      <div><strong>${escapeHTML(s.availableSeats??0)} seats</strong><small>Available seats</small></div>
      <div><strong>${money(s.price)}</strong><small>Full route fare</small></div>
      ${journeyPickerMarkup(s,id,'popular')}
    </article>`;
  }).join(''):'<div class="panel"><p>No buses are available on this date.</p></div>';
  host.querySelectorAll('[data-popular-from],[data-popular-to]').forEach(sel=>sel.addEventListener('change',()=>{
    const id=sel.dataset.popularFrom||sel.dataset.popularTo;
    const row=rows.find((s,i)=>`pop-${s.scheduleId}-${i}`===id);if(!row)return;
    const from=host.querySelector(`[data-popular-from="${CSS.escape(id)}"]`)?.value;
    const to=host.querySelector(`[data-popular-to="${CSS.escape(id)}"]`)?.value;
    const btn=host.querySelector(`[data-popular-view="${CSS.escape(id)}"]`);
    if(btn)btn.disabled=!validForwardPair(orderedStopsForSchedule(row),from,to);
  }));
  host.querySelectorAll('[data-popular-view]').forEach(btn=>btn.addEventListener('click',async()=>{
    const id=btn.dataset.popularView;
    const row=rows.find((s,i)=>`pop-${s.scheduleId}-${i}`===id);if(!row)return;
    const from=host.querySelector(`[data-popular-from="${CSS.escape(id)}"]`)?.value;
    const to=host.querySelector(`[data-popular-to="${CSS.escape(id)}"]`)?.value;
    await openScheduleJourney(row,from,to,'popular');
  }));
}
async function openScheduleJourney(schedule,from,to,source){
  if(!from||!to)return toast('Select both starting and dropping points.');
  if(normalizeCity(from)===normalizeCity(to))return toast('Starting and dropping points must be different.');
  const points=orderedStopsForSchedule(schedule);
  if(!validForwardPair(points,from,to))return toast('Choose a valid forward journey in route order.');
  const date=String(schedule.travelDate||todayISO());
  if(date<todayISO())return toast('This travel date has passed.');
  if(date===todayISO()&&scheduleHasDeparted(schedule))return toast('This bus has already departed. Booking is closed.');
  try{
    const rows=await fetchJourneyServices(from,to,date);
    const matched=rows.find(x=>String(x.scheduleId)===String(schedule.scheduleId||schedule.id));
    if(!matched)throw new Error('This bus does not serve the selected journey or the fare is not configured.');
    await releaseOwnHold();
    state={...state,from,to,date,service:null,seats:[],amount:0,holdId:null,bookingId:null,processing:false};
    services=rows;$('#fromCity').value=from;$('#toCity').value=to;$('#travelDate').value=date;
    await selectBus(encodeURIComponent(matched.id));
  }catch(e){toast(e.message||'Unable to open seat selection.');}
}
async function openPopularRoute(from,to){
  if(!from||!to)return;
  try{
    await releaseOwnHold();
    const rows=await fetchUpcomingRouteServices(from,to);
    state={...state,from,to,date:'',service:null,seats:[],amount:0,holdId:null,bookingId:null,processing:false};
    $('#resultTitle').textContent=`${from} → ${to}`;
    renderPopularRouteResults(from,to,rows);
    show('#results');
    $('#results')?.scrollIntoView({behavior:'smooth',block:'start'});
  }catch(e){toast(e.message||'Unable to load buses for this route.');}
}
function renderTodaysRunningBuses(){
  const host=$('#todaysRunningBuses');if(!host)return;
  const today=todayISO();
  const rows=(backendCatalog?.schedules||[])
    .filter(s=>String(s.travelDate||'')===today&&String(s.status||'Open')==='Open')
    .sort((a,b)=>String(a.depart||'').localeCompare(String(b.depart||'')));
  host.innerHTML=rows.length?rows.map((s,i)=>{
    const id=`today-${s.scheduleId||s.id||i}`,departed=scheduleHasDeparted(s);
    return `<article class="bus-card running-bus-card" style="margin:12px 0;padding:16px;display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:14px;align-items:center">
      <div class="bus-name"><strong>${escapeHTML(s.bus||s.busNumber||'Bus')}</strong><small>${escapeHTML(s.type||'')}</small></div>
      <div><strong>${escapeHTML(s.from||'')} → ${escapeHTML(s.to||'')}</strong><small>Route</small></div>
      <div class="time-block"><b>${escapeHTML(s.depart||'—')}</b><small>Departure</small></div>
      <div class="time-block"><b>${escapeHTML(s.arrive||'—')}</b><small>Arrival</small></div>
      <div><div class="fare">${money(s.fare)}</div><small>Full route fare</small></div>
      <div>${departed?'<span class="schedule-status departed">DEPARTED · BOOKING CLOSED</span>':`<button type="button" class="primary" data-today-expand="${escapeAttr(id)}">CHOOSE STOPS</button>`}</div>
      ${departed?'':journeyPickerMarkup(s,id,'today')}
    </article>`;
  }).join(''):'<div class="panel"><p>No buses are scheduled for today.</p></div>';
  host.querySelectorAll('[data-today-expand]').forEach(btn=>btn.addEventListener('click',()=>{
    const card=btn.closest('.running-bus-card');const picker=card?.querySelector('.journey-stop-picker');
    if(picker){picker.hidden=false;picker.scrollIntoView({behavior:'smooth',block:'nearest'});btn.disabled=true;btn.textContent='STOPS SELECTED';}
  }));
  host.querySelectorAll('[data-today-from],[data-today-to]').forEach(sel=>sel.addEventListener('change',()=>{
    const id=sel.dataset.todayFrom||sel.dataset.todayTo;
    const row=rows.find((s,i)=>`today-${s.scheduleId||s.id||i}`===id);if(!row)return;
    const from=host.querySelector(`[data-today-from="${CSS.escape(id)}"]`)?.value;
    const to=host.querySelector(`[data-today-to="${CSS.escape(id)}"]`)?.value;
    const btn=host.querySelector(`[data-today-view="${CSS.escape(id)}"]`);
    if(btn)btn.disabled=!validForwardPair(orderedStopsForSchedule(row),from,to);
  }));
  host.querySelectorAll('[data-today-view]').forEach(btn=>btn.addEventListener('click',async()=>{
    const id=btn.dataset.todayView;
    const row=rows.find((s,i)=>`today-${s.scheduleId||s.id||i}`===id);if(!row)return;
    const from=host.querySelector(`[data-today-from="${CSS.escape(id)}"]`)?.value;
    const to=host.querySelector(`[data-today-to="${CSS.escape(id)}"]`)?.value;
    await openScheduleJourney(row,from,to,'today');
  }));
}

async function refreshSearchResults({announce=false}={}){
  if(!state.from||!state.to||!state.date)return;
  try{services=await fetchJourneyServices(state.from,state.to,state.date);renderBuses();}
  catch(e){if(announce)toast(e.message||'Unable to refresh bus search results.');}
}

async function searchBuses(){
  const from=$('#fromCity').value.trim(),to=$('#toCity').value.trim(),date=$('#travelDate').value;
  if(!from||!to||!date)return toast('Enter From, To and travel date');
  if(normalizeCity(from)===normalizeCity(to))return toast('From and To cities must be different');
  if(date<todayISO())return toast('Travel date cannot be in the past');
  try{await releaseOwnHold();}catch(e){toast(e.message||'Unable to release your current seat hold.');return;}
  state={...state,from,to,date,service:null,seats:[],amount:0,holdId:null,bookingId:null,processing:false};seatStatuses=new Map();
  $('#resultTitle').textContent=`${from} → ${to}`;
  try{
    services=await fetchJourneyServices(from,to,date);
    renderBuses();
  }catch(e){
    services=[];
    renderBuses();
    toast(e.message||'Unable to search buses');
  }
  show('#results');
}
function hour(t){return Number(String(t||'00:00').split(':')[0])||0}
function renderBuses(){
  const need=routeKey(`${state.from} → ${state.to}`),typ=$('#busFilter')?.value||'all',tm=$('#timeFilter')?.value||'all',sort=$('#sortFilter')?.value||'departure';
  let list=services.filter(s=>routeKey(s.route)===need&&(!s.travelDate||!state.date||s.travelDate===state.date));
  list=list.filter(s=>typ==='all'||String(s.type).toLowerCase().includes(String(typ).toLowerCase())||(typ==='AC'&&s.ac));
  list=list.filter(s=>tm==='all'||(tm==='morning'&&hour(s.dep)<12)||(tm==='evening'&&hour(s.dep)>=12&&hour(s.dep)<20)||(tm==='night'&&hour(s.dep)>=20));
  list=list.map(s=>({...s,availableSeats:Number(s.availableSeats??s.seats)||0}));
  if(sort==='price')list.sort((a,b)=>a.price-b.price); else if(sort==='rating')list.sort((a,b)=>(Number(b.rating)||0)-(Number(a.rating)||0)); else list.sort((a,b)=>a.dep.localeCompare(b.dep));
  $('#resultMeta').textContent=`${state.date?new Date(state.date+'T00:00:00').toLocaleDateString('en-IN',{day:'2-digit',month:'short',year:'numeric'}):'Upcoming schedules'} • ${list.length} service${list.length===1?'':'s'} available`;
  $('#busList').innerHTML=list.length?list.map(s=>`<article class="bus-card"><div class="bus-name"><strong>${escapeHTML(s.name)}</strong><small>${escapeHTML(s.type)}${s.ac?' • AC':''}</small><div class="tags"><span class="tag">LIVE SEATS</span><span class="tag blue">${s.availableSeats} LEFT</span></div></div><div class="time-block"><b>${escapeHTML(s.dep)}</b><small>Departure</small></div><div class="time-block"><b>${escapeHTML(s.arr)}</b><small>Arrival</small></div><div><div class="rating">${s.rating?`★ ${s.rating}`:'NEW'}</div><small>${s.rating?'Customer rating':'No reviews yet'}</small></div><div><div class="fare">${money(s.price)}</div><button class="primary" ${s.availableSeats?'':'disabled'} onclick="selectBus('${encodeURIComponent(s.id)}')">${s.availableSeats?'VIEW SEATS':'SOLD OUT'}</button></div></article>`).join(''):`<div class="panel"><h2>No buses found</h2><p style="font-size:13px;color:#657895">No active schedule is available for ${escapeHTML(state.from)} → ${escapeHTML(state.to)} on this date.</p></div>`;
}
async function selectBus(encoded){
  const id=decodeURIComponent(encoded), service=services.find(s=>String(s.id)===id); if(!service)return toast('Bus service not found');
  try{await releaseOwnHold();}catch(e){toast(e.message||'Unable to release the previous seat hold.');return;}
  state.service=service;state.date=String(service.travelDate||state.date||'');state.seats=[];state.amount=0;state.holdId=null;seatStatuses=new Map();
  $('#seatRouteFrom').textContent=state.from||service.route.split(' → ')[0]||'';
  $('#seatRouteTo').textContent=state.to||service.route.split(' → ').slice(1).join(' → ')||'';
  $('#seatTripMeta').textContent=`Date: ${new Date((service.travelDate||state.date)+'T00:00:00').toLocaleDateString('en-IN',{day:'2-digit',month:'short',year:'numeric'})}  |  Bus: ${service.name} ${service.type}`;
  $('#seatDepartureTime').textContent=service.dep||'—';
  $('#seatDepartureDate').textContent=new Date((service.travelDate||state.date)+'T00:00:00').toLocaleDateString('en-IN',{day:'2-digit',month:'short',year:'numeric'});
  $('#seatBusInfo').textContent=`${service.name} • ${service.type} • ${money(service.price)} per seat`;
  try{await renderSeats();show('#seats');}catch(e){toast(e.message||'Unable to load seat availability.');}
}
async function renderSeats(){
  if(!state.service)return;
  seatStatuses=await fetchSeatStatuses();
  renderSeatSelection();
}
async function toggleSeat(n){
  if(!state.service||seatOperationPending||seatRefreshPending)return;const seat=String(n);
  const i=state.seats.indexOf(seat);
  if(i<0&&seatStatuses.get(seat)!=='AVAILABLE')return toast(`Seat ${seatLabel(seat)} is no longer available. Please choose another seat.`);
  if(i<0&&state.seats.length>=MAX_SEATS)return toast(`You can select up to ${MAX_SEATS} seats.`);
  const before=state.seats.slice();
  if(i>=0)state.seats.splice(i,1);else state.seats.push(seat);
  seatOperationPending=true;
  renderSeatSelection();
  try{
    await holdSelectedSeats();
    try{seatStatuses=await fetchSeatStatuses();}
    catch(e){toast(e.message||'Seat selection is held, but availability could not be refreshed.');}
  }catch(e){
    state.seats=before;
    toast(e.message||'That seat could not be held. Please choose another seat.');
    try{seatStatuses=await fetchSeatStatuses();}
    catch(refreshError){toast(refreshError.message||'Could not refresh the seat status.');}
  }finally{
    seatOperationPending=false;
    renderSeatSelection();
  }
}
function passengerSummary(){const base=state.seats.length*state.service.price,tax=Math.round(base*TAX_RATE);$('#passengerSummary').innerHTML=`<div class="summary-row"><span>${escapeHTML(state.service.name)}</span><span>${money(state.service.price)} × ${state.seats.length}</span></div><div class="summary-row"><span>Seats</span><span>${state.seats.map(seatLabel).join(', ')}</span></div><div class="summary-row"><span>Taxes & fees</span><span>${money(tax+CONVENIENCE_FEE)}</span></div><div class="summary-total"><span>Total</span><b>${money(state.amount)}</b></div>`}
function buildPassengerFields(){
  const wrap=$('#passengerFields');if(!wrap)return;const user=currentUser();wrap.innerHTML=state.seats.map((seat,i)=>`<div class="panel" style="padding:14px;margin-bottom:10px"><h3 style="margin:0 0 10px">Passenger ${i+1} • Seat ${seatLabel(seat)}</h3><div class="form-grid"><label>FULL NAME<input class="p-name" data-seat="${seat}" value="${i===0?escapeAttr(user?.name||''):''}" required></label><label>AGE<input class="p-age" data-seat="${seat}" type="number" min="1" max="120" required></label><label>GENDER<select class="p-gender" data-seat="${seat}"><option>Male</option><option>Female</option><option>Other</option></select></label><label>MOBILE<input class="p-mobile" data-seat="${seat}" value="${i===0?escapeAttr(user?.phone||''):''}" inputmode="numeric" required></label><label class="wide">EMAIL<input class="p-email" data-seat="${seat}" type="email"></label></div></div>`).join('')+`<label class="check"><input id="terms" type="checkbox" required> I accept the terms & cancellation policy.</label>`;
}
async function continueToPassenger(){
  if(!state.seats.length)return toast('Please select at least one seat.');
  if(seatOperationPending||seatRefreshPending)return;
  seatOperationPending=true;
  renderSeatSelection();
  try{
    seatStatuses=await fetchSeatStatuses();
    const unavailable=state.seats.filter(seat=>seatStatuses.get(seat)!=='AVAILABLE');
    if(unavailable.length){
      state.seats=state.seats.filter(seat=>!unavailable.includes(seat));
      try{await holdSelectedSeats();}
      catch(e){
        state.seats=[];
        try{await releaseOwnHold();}catch(releaseError){toast(releaseError.message||'Unable to release your seat hold.');}
        throw e;
      }
      toast(`${unavailable.map(seatLabel).join(', ')} ${unavailable.length===1?'is':'are'} no longer available. Please choose another seat.`);
      return;
    }
    await holdSelectedSeats();
    if(!currentUser()){
      saveBookingReturnState();
      sessionStorage.setItem('sbtReturnAfterLogin','booking');
      location.href='login.html?return='+encodeURIComponent('index.html#passenger');
      return;
    }
    buildPassengerFields();
    passengerSummary();
    show('#passenger');
  }catch(e){
    toast(e.message||'Your seat selection could not be confirmed. Please refresh and try again.');
  }finally{
    seatOperationPending=false;
    renderSeatSelection();
  }
}
function collectPassengers(){return state.seats.map(seat=>{const q=s=>document.querySelector(`${s}[data-seat="${CSS.escape(seat)}"]`);return {seat,name:q('.p-name')?.value.trim()||'',age:q('.p-age')?.value||'',gender:q('.p-gender')?.value||'',mobile:q('.p-mobile')?.value.replace(/\D/g,'')||'',email:q('.p-email')?.value.trim()||'',}})}
function validatePassengers(){for(const p of collectPassengers()){if(p.name.length<2)return 'Enter every passenger name';if(!/^\d{1,3}$/.test(p.age)||Number(p.age)<1||Number(p.age)>120)return 'Enter a valid age for every passenger';if(!/^[6-9]\d{9}$/.test(p.mobile))return 'Enter a valid 10-digit mobile number for every passenger';if(!p.email||!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(p.email))return 'Enter a valid email address for every passenger'}return ''}
function paymentSummary(){$('#paymentSummary').innerHTML=`<div class="summary-row"><span>Route</span><span>${escapeHTML(state.from)} → ${escapeHTML(state.to)}</span></div><div class="summary-row"><span>Bus</span><span>${escapeHTML(state.service.name)}</span></div><div class="summary-row"><span>Seats</span><span>${state.seats.map(seatLabel).join(', ')}</span></div><div class="summary-total"><span>Amount</span><b>${money(state.amount)}</b></div>`}
function renderPaymentInputs(){const c=$('#paymentInputs');if(!c)return;c.innerHTML='<div class="upi-pay-box"><h3>Pay directly using UPI</h3><p>Google Pay, PhonePe, Paytm, or another UPI app will open with the client UPI ID and exact amount pre-filled. Payment is manually verified by the admin.</p></div>';const btn=$('#payBtn');if(btn)btn.textContent='PAY VIA UPI'}
function validatePayment(){return state.method==='UPI'?'':'Only UPI payments are currently supported.'}

async function ensureBookingIdentity(phone,email){
  const user=currentUser();
  if(!user) throw new Error('Please log in before booking.');
  return user;
}
async function completeBooking(){
  if(state.processing)return;
  // Validate before locking the control so validation errors never leave Pay disabled.
  if(!state.service||!state.seats.length)return toast('Select at least one seat');
  const pe=validatePassengers();if(pe)return toast(pe);
  const pm=validatePayment();if(pm)return toast(pm);
  state.processing=true;
  const payButton=$('#payBtn');
  if(payButton){payButton.disabled=true;payButton.dataset.originalText ||= payButton.textContent;payButton.textContent='PROCESSING…';payButton.setAttribute('aria-busy','true');}
  try{
    const passengers=collectPassengers();const user=await ensureBookingIdentity(passengers[0]?.mobile||'',passengers[0]?.email||'');
    const result=await api('/bookings',{method:'POST',body:JSON.stringify({scheduleId:Number(state.service.scheduleId),from:state.from,to:state.to,customerPhone:user.phone,customerEmail:user.email,seats:state.seats,passengers,paymentMethod:state.method,offerCode:($('#offerCode')?.value||'').trim(),holdToken:state.holdId})});
    state.bookingId=result.id;await releaseOwnHold();
    const accessHeaders={};
    if(state.method==='UPI'){
      const info=await api('/payments/upi?bookingId='+encodeURIComponent(result.dbId),{headers:accessHeaders});
      $('#confirmationId').textContent=result.id;
      // Do not display a payment/booking confirmation before the customer returns from the UPI app.
      // A UPI deep link cannot reliably tell this website whether money was actually transferred.
      const pendingMessage='You returned from the UPI app. Your payment is awaiting admin verification. If you did not complete the payment, you can retry from My Trips. Your ticket will appear only after payment verification and admin confirmation.';
      let returned=false;
      const onReturn=()=>{
        if(returned || document.visibilityState!=='visible')return;
        returned=true;document.removeEventListener('visibilitychange',onReturn);
        $('#confirmationTitle').textContent='Payment submitted — verification pending';
        $('#confirmationMessage').textContent=pendingMessage;
        $('#ticketCard').innerHTML=`<div class="ticket-main"><div><b>Booking reference</b><small>${escapeHTML(result.id)}</small></div><div class="status">AWAITING VERIFICATION</div></div><p style="margin-top:14px">${escapeHTML(pendingMessage)}</p><p>UPI recipient: <strong>${escapeHTML(info.upiId)}</strong></p><p>Amount: <strong>${money(info.amount)}</strong></p><button class="secondary" id="payAgainBtn" type="button">REOPEN UPI PAYMENT</button>`;
        show('#confirmation');
        $('#payAgainBtn').onclick=()=>{window.location.href=info.upiUri};
        refreshBackendBookings();
      };
      document.addEventListener('visibilitychange',onReturn);
      toast('Opening your UPI app. Return here after completing or cancelling payment.');
      window.location.href=info.upiUri;
      // Intentionally no timer-based success fallback: never show a payment status screen just
      // because time passed. Show the pending-verification screen only after the customer returns.
    }else{throw new Error('Only UPI payments are currently supported.');}
  }catch(e){
    // Once the backend has created a booking, never re-enable Pay and create another booking
    // just because payment-info retrieval or app handoff failed. Recover through My Trips.
    if(state.bookingId){
      toast('Your booking request already exists. Check My Trips before trying again.');
      const title=$('#confirmationTitle'),message=$('#confirmationMessage'),card=$('#ticketCard');
      if(title)title.textContent='Booking created — payment status pending';
      if(message)message.textContent='We could not finish opening UPI. This does not mean payment succeeded. Check My Trips for the existing booking and do not submit a second booking.';
      if(card)card.innerHTML=`<div class="ticket-main"><div><b>Booking reference</b><small>${escapeHTML(state.bookingId)}</small></div><div class="status">PAYMENT PENDING</div></div><p>Payment has not been verified. Do not make another booking for the same journey.</p><button class="secondary" id="checkTripsBtn" type="button">CHECK MY TRIPS</button>`;
      if($('#checkTripsBtn'))$('#checkTripsBtn').onclick=()=>renderBookings();
      show('#confirmation');
    } else {
      toast(e.message||'Payment could not be completed. Check My Trips before retrying.');
    }
  }finally{
    state.processing=false;
    // Keep Pay locked if a booking was already created. Re-enable only when no booking exists.
    if(payButton){
      if(state.bookingId){payButton.disabled=true;payButton.textContent='BOOKING CREATED — CHECK MY TRIPS';payButton.setAttribute('aria-disabled','true');}
      else {payButton.disabled=false;payButton.textContent=payButton.dataset.originalText||'PAY VIA UPI';payButton.removeAttribute('aria-busy');}
    }
  }
}
async function refreshBackendBookings(){const user=currentUser();if(!user)return;try{const bookings=await api('/bookings/my');localStorage.setItem('sbtBookings',JSON.stringify(bookings));}catch(e){localStorage.removeItem('sbtBookings');toast(e.message||'Unable to refresh bookings');}}

async function renderBookings(){
  const user=currentUser();
  if(!user){$('#bookingList').innerHTML='';location.href='login.html?return='+encodeURIComponent('index.html#bookings');return;}
  const session=getSession();
  show('#bookings');
  $('#bookingList').innerHTML='<div class="panel"><p>Loading your bookings…</p></div>';
  localStorage.removeItem('sbtBookings');
  try{
    const data=await api('/bookings/my');
    if(getSession()?.token!==session?.token||!currentUser())return;
    localStorage.setItem('sbtBookings',JSON.stringify(data));
    $('#bookingList').innerHTML=data.length?data.map(b=>`<article class="booking-item"><div><small>BOOKING</small><strong>${escapeHTML(b.id)}</strong></div><div><small>BUS</small><strong>${escapeHTML(b.bus)}</strong></div><div><small>ROUTE</small><strong>${escapeHTML(b.from)} → ${escapeHTML(b.to)}</strong></div><div><small>SEATS</small><strong>${(b.seats||[]).join(', ')}</strong></div><div><small>AMOUNT</small><strong>${money(b.amount)}</strong></div><span class="status ${b.status==='Confirmed'?'success':''}">${String(b.status).toUpperCase()}</span>${b.status==='Confirmed'?`<button class="secondary" onclick="openTicket('${b.id}')">TICKET</button><button class="secondary" onclick="openTicketQr('${b.dbId||''}')">QR</button><button class="secondary danger" onclick="cancelBooking('${b.id}')">CANCEL</button>`:`<small>Ticket available after payment verification and admin confirmation.</small>`}</article>`).join(''):'<div class="panel"><h2>No bookings yet</h2><p style="font-size:13px;color:#657895">Your bookings will appear here after payment.</p><button class="primary" onclick="show(\'#home\')">BOOK A BUS</button></div>';
  }catch(e){
    localStorage.removeItem('sbtBookings');
    $('#bookingList').innerHTML='<div class="panel"><h2>Unable to load bookings</h2><p>Please try again.</p><button class="secondary" onclick="renderBookings()">RETRY</button></div>';
    toast(e.message||'Unable to load bookings');
    if(e.status===401){localStorage.removeItem('sbtSession');localStorage.removeItem('sbtCurrentUser');location.href='login.html?return='+encodeURIComponent('index.html#bookings');}
  }
}
function getBookingForUser(id){const u=currentUser();if(!u)return null;return readJSON('sbtBookings',[]).find(b=>b.id===id&&String(b.customerPhone)===String(u.phone))}
async function cancelBooking(id){const b=getBookingForUser(id);if(!b)return toast('Booking not found');if(b.status!=='Confirmed')return toast('Only confirmed bookings can be cancelled');if(!confirm('Cancel this booking?'))return;try{await api('/bookings/'+encodeURIComponent(b.dbId||id)+'/cancel',{method:'POST',headers:b.guestAccessToken?{'X-Guest-Booking-Token':b.guestAccessToken}:{}});await renderBookings();toast('Booking cancelled and refund recorded.')}catch(e){toast(e.message)}}

async function renderProfile(){const session=getSession();if(!currentUser()){location.href='login.html?return='+encodeURIComponent('index.html#profile');return}$('#profileName').textContent='Loading account…';$('#profileEmail').textContent='';$('#profileFieldName').value='';$('#profileFieldMobile').value='';$('#profileFieldEmail').value='';$('#profileFieldCity').value='';try{const user=await api('/users/profile');if(getSession()?.token!==session?.token||!currentUser())return;localStorage.setItem('sbtCurrentUser',JSON.stringify(user));$('#profileAvatar').textContent=(user.name||'C').charAt(0).toUpperCase();$('#profileName').textContent=user.name||'';$('#profileEmail').textContent=user.email||`+91 ${user.phone}`;$('#profileFieldName').value=user.name||'';$('#profileFieldMobile').value=user.phone||'';$('#profileFieldEmail').value=user.email||'';$('#profileFieldCity').value=user.city||'';$('#profileBtn').textContent=(user.name||'C').charAt(0).toUpperCase()}catch(e){$('#profileName').textContent='Unable to load account details';toast(e.message||'Unable to load account details');if(e.status===401){localStorage.removeItem('sbtSession');localStorage.removeItem('sbtCurrentUser');location.href='login.html?return='+encodeURIComponent('index.html#profile')}}
}
async function saveProfile(){const u=currentUser();if(!u)return location.href='login.html';try{const updated=await api('/users/profile',{method:'PUT',body:JSON.stringify({name:$('#profileFieldName').value.trim(),email:$('#profileFieldEmail').value.trim(),city:$('#profileFieldCity').value.trim()})});const users=readJSON('sbtUsers',[]).filter(x=>String(x.phone)!==String(u.phone));users.push(updated);writeJSON('sbtUsers',users);renderProfile();updateAuthUi();toast('Profile updated')}catch(e){toast(e.message)}}
function copyCode(code){navigator.clipboard?.writeText(code).catch(()=>{});toast(`${code} copied`)}
function escapeHTML(v){return String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function escapeAttr(v){return escapeHTML(v)}

async function openTicketQr(dbId){if(!dbId)return toast('Ticket QR unavailable');try{const sess=getSession();const booking=readJSON('sbtBookings',[]).find(x=>String(x.dbId)===String(dbId));const headers=sess?.token?{Authorization:'Bearer '+sess.token}:{};if(booking?.guestAccessToken)headers['X-Guest-Booking-Token']=booking.guestAccessToken;const r=await fetch('/api/bookings/'+encodeURIComponent(dbId)+'/qr',{headers});if(!r.ok)throw new Error('Unable to load QR');const blob=await r.blob(),url=URL.createObjectURL(blob);const w=window.open('','_blank');if(!w)throw new Error('Allow popups to view QR');w.document.write('<title>Ticket QR</title><body style="font-family:Arial;text-align:center;padding:40px"><h2>Saburi Swami Travel</h2><img src="'+url+'" width="320" height="320"><p>Scan to verify booking</p></body>');w.document.close();}catch(e){toast(e.message)}}
function openTicket(id){
  const b=getBookingForUser(id);if(!b)return toast('Booking not found');if(b.status!=='Confirmed')return toast('Ticket is available only after payment verification and admin confirmation.');
  ticketToPrint=b;
  const esc=escapeHTML;
  $('#ticketModalContent').innerHTML=`<article class="professional-ticket" id="printableTicket"><header class="ticket-brand"><div><div class="ticket-brand-name">SABURI SWAMI TRAVEL</div><small>Safe Journey, Better Tomorrow</small></div><div class="ticket-stamp"><b>E-TICKET</b><span>✓ CONFIRMED</span></div></header><section class="ticket-grid"><div><small>BOOKING ID</small><strong>${esc(b.id)}</strong></div><div><small>TRAVEL DATE</small><strong>${esc(b.date||'As booked')}</strong></div><div><small>BUS</small><strong>${esc(b.bus||'—')}</strong></div><div><small>SEAT(S)</small><strong>${esc((b.seats||[]).join(', '))}</strong></div></section><section class="ticket-route"><div><small>FROM</small><strong>${esc(b.from||'—')}</strong></div><span class="ticket-route-arrow">────────➜</span><div><small>TO</small><strong>${esc(b.to||'—')}</strong></div></section><section class="ticket-details"><div><h3>PASSENGER DETAILS</h3><p><b>Name:</b> ${esc(b.passengers?.[0]?.name||b.passengerName||b.customerName||'—')}</p><p><b>Age:</b> ${esc(b.passengers?.[0]?.age??'—')}</p><p><b>Gender:</b> ${esc(b.passengers?.[0]?.gender||'—')}</p><p><b>Mobile:</b> ${esc(b.customerPhone||'—')}</p></div><div><h3>FARE DETAILS</h3><p><span>Total Amount</span><strong>${money(b.amount)}</strong></p><p><span>Booking status</span><strong>Confirmed</strong></p></div><div><h3>IMPORTANT</h3><ul><li>Arrive 15 minutes before departure.</li><li>Carry valid ID proof.</li><li>Follow the cancellation policy.</li></ul></div></section><footer class="ticket-footer"><div id="ticketQrBox"><div class="ticket-qr-placeholder">QR</div><small>Scan for verification</small></div><div class="ticket-thanks"><b>Thank you for travelling with<br>SABURI SWAMI TRAVEL</b><small>Safe Journey, Better Tomorrow</small></div><div><small>Need help?</small><strong>Contact customer support</strong></div></footer></article>`;
  $('#ticketModal').classList.remove('hidden');
  const box=$('#ticketQrBox');if(b.dbId)fetch('/api/bookings/'+encodeURIComponent(b.dbId)+'/qr',{headers:getSession()?.token?{Authorization:'Bearer '+getSession().token}:{}}).then(r=>{if(!r.ok)throw Error();return r.blob()}).then(blob=>{const url=URL.createObjectURL(blob);box.innerHTML=`<img class="ticket-qr" src="${url}" alt="Ticket QR code"><small>Scan for verification</small>`}).catch(()=>{});
}
function closeTicket(){ $('#ticketModal')?.classList.add('hidden'); }
function downloadTicket(){if(!ticketToPrint)return;const content=$('#printableTicket')?.outerHTML;if(!content)return;const css=[...document.styleSheets].map(()=> '').join('');const html=`<!doctype html><html><head><meta charset="utf-8"><title>${escapeHTML(ticketToPrint.id)} - E-Ticket</title><style>body{font-family:Arial,sans-serif;background:#F1F5F9;padding:24px;color:#334155}.professional-ticket{max-width:900px;margin:auto;border:1px solid #D8E2EE;border-radius:18px;overflow:hidden;background:white}.ticket-brand{display:flex;justify-content:space-between;padding:28px;background:linear-gradient(110deg,#0B1F44 0%,#1769F5 72%,#F32645 100%);color:white}.ticket-brand-name{font-size:24px;font-weight:bold}.ticket-stamp{display:grid;text-align:right;gap:8px}.ticket-stamp span{background:#dff7e9;color:#087443;border-radius:20px;padding:6px 12px}.ticket-grid,.ticket-route,.ticket-details,.ticket-footer{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;padding:22px;border-bottom:1px solid #dce5ef}.ticket-grid small,.ticket-route small,.ticket-footer small{display:block;color:#64748B;margin-bottom:6px}.ticket-grid strong,.ticket-route strong{display:block;font-size:17px}.ticket-route{grid-template-columns:1fr 1fr 1fr;align-items:center}.ticket-details{grid-template-columns:repeat(3,1fr)}.ticket-details h3{font-size:13px}.ticket-details p{font-size:13px}.ticket-footer{grid-template-columns:1fr 2fr 1fr;align-items:center}.ticket-qr{width:90px;height:90px}.ticket-qr-placeholder{border:1px solid #aaa;padding:20px;width:40px}.ticket-thanks{text-align:center;color:#1769F5}</style></head><body>${content}</body></html>`;const blob=new Blob([html],{type:'text/html'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=`${ticketToPrint.id}-ticket.html`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
function printTicket(){if(!ticketToPrint)return;const content=$('#printableTicket')?.outerHTML;if(!content)return;const w=window.open('','_blank');if(!w)return toast('Allow popups to print the ticket');w.document.write(`<!doctype html><html><head><title>${escapeHTML(ticketToPrint.id)} ticket</title><style>body{font-family:Arial;padding:20px;color:#334155;background:#F1F5F9}.professional-ticket{border:1px solid #D8E2EE;border-radius:12px;overflow:hidden;background:#fff}.ticket-brand{display:flex;justify-content:space-between;padding:20px;background:linear-gradient(110deg,#0B1F44 0%,#1769F5 72%,#F32645 100%);color:white}.ticket-grid,.ticket-route,.ticket-details,.ticket-footer{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;padding:16px;border-bottom:1px solid #D8E2EE}.ticket-details{grid-template-columns:repeat(3,1fr)}.ticket-footer{grid-template-columns:1fr 2fr 1fr}.ticket-qr{width:90px;height:90px}.ticket-brand-name{font-size:22px;font-weight:bold}small{display:block;color:#64748B}@media print{body{padding:0}}</style></head><body>${content}<script>window.onload=()=>window.print()<\/script></body></html>`);w.document.close();}

function showBookings(){return renderBookings()}
function showPage(name){if(name==='profile'){if(!currentUser()){location.href='login.html?return='+encodeURIComponent('index.html#profile');return}show('#profile');renderProfile();return}if(name==='bookings')return renderBookings();show('#'+name)}
function updateAuthUi(){const l=$('#accountLink'),u=currentUser();if(l){l.textContent='Account';l.href=u?'#profile':'login.html';l.setAttribute('aria-label',u?'Open your account':'Sign in to your account');l.title=u?'Open your account':'Sign in to your account'}}
async function populatePublicStops(){
  const from=$('#fromCity'),to=$('#toCity');if(!from||!to)return;
  const oldFrom=from.value,oldTo=to.value;let names=new Set();
  try{const c=backendCatalog||await api('/catalog');for(const r of c.routes||[]){if(String(r.status||'Active')!=='Active')continue;names.add(String(r.origin||''));names.add(String(r.destination||''));try{const stops=await api('/routes/'+encodeURIComponent(r.id)+'/stops');(stops||[]).forEach(x=>names.add(String(x.city||x.name||'')));}catch{}}
  }catch{}
  const values=[...names].map(x=>x.trim()).filter(Boolean).sort((a,b)=>a.localeCompare(b));
  if(!values.length){const fallback=['Nashik','Kopargaon'];values.push(...fallback)}
  const options=(placeholder,selected)=>`<option value="">${placeholder}</option>`+values.map(n=>`<option value="${escapeAttr(n)}" ${normalizeCity(n)===normalizeCity(selected)?'selected':''}>${escapeHTML(n)}</option>`).join('');
  from.innerHTML=options('Select origin',oldFrom);to.innerHTML=options('Select destination',oldTo);if(!from.value&&values.length)from.value=values.find(v=>normalizeCity(v)===normalizeCity('Nashik'))||values[0];if(!to.value&&values.length>1)to.value=values.find(v=>normalizeCity(v)===normalizeCity('Kopargaon')&&normalizeCity(v)!==normalizeCity(from.value))||values.find(v=>normalizeCity(v)!==normalizeCity(from.value))||values[1];state.from=from.value||state.from;state.to=to.value||state.to;
}
async function init(){
  $('#travelDate').value=todayISO();
  await loadBackendCatalog();
  // Re-evaluate today's homepage cards as the clock advances and after a tab resumes.
  if(!window.__saburiTodayRefresh){
    window.__saburiTodayRefresh=window.setInterval(renderTodaysRunningBuses,30000);
    document.addEventListener('visibilitychange',()=>{if(!document.hidden)renderTodaysRunningBuses();});
  }
  await populatePublicStops();
  loadBackendCatalog().then(async()=>{await populatePublicStops();
    const routeGroups=new Map();
    services.filter(s=>s.route&&s.route.includes(' → ')&&(String(s.travelDate||'')>todayISO()||(String(s.travelDate||'')===todayISO()&&!scheduleHasDeparted({depart:s.dep,travelDate:s.travelDate})))).forEach(service=>{
      const key=routeKey(service.route),group=routeGroups.get(key);
      if(group){group.services.push(service);if(Number(service.price)<Number(group.service.price))group.service=service;}
      else routeGroups.set(key,{service,services:[service]});
    });
    const routes=[...routeGroups.values()];
    $('#popularRoutes').innerHTML=routes.length?routes.map(({service,services:routeServices})=>{
      const parts=service.route.split(' → '),from=parts[0]||'',to=parts.slice(1).join(' → ');
      const serviceTypes=[...new Set(routeServices.map(item=>String(item.type||'').trim()).filter(Boolean))];
      const hasSleeper=serviceTypes.some(type=>/sleeper/i.test(type)),hasSeater=serviceTypes.some(type=>/seater/i.test(type));
      const busOptions=hasSleeper&&hasSeater?'Sleeper & Seater':serviceTypes.length?serviceTypes.join(' & '):'Bus options';
      return `<button type="button" class="route-item route-booking" data-route-from="${escapeHTML(from)}" data-route-to="${escapeHTML(to)}" aria-label="Search buses from ${escapeHTML(from)} to ${escapeHTML(to)}, starting from ${escapeHTML(money(service.price))}">
        <span class="route-card-content">
          <span class="route-popular-badge"><span aria-hidden="true">▰</span> Popular Route</span>
          <strong class="route-title">${escapeHTML(from)} <span aria-hidden="true">→</span> ${escapeHTML(to)}</strong>
          <span class="route-tagline">Comfortable <span aria-hidden="true">|</span> Safe <span aria-hidden="true">|</span> On Time</span>
          <span class="route-journey" aria-label="Journey from ${escapeHTML(from)} to ${escapeHTML(to)}">
            <span class="route-stop"><span class="route-pin" aria-hidden="true">●</span><span><strong>${escapeHTML(from)}</strong><small>Origin</small></span></span>
            <span class="route-connector" aria-hidden="true"><span>▰</span></span>
            <span class="route-stop"><span class="route-pin" aria-hidden="true">●</span><span><strong>${escapeHTML(to)}</strong><small>Destination</small></span></span>
          </span>
          <span class="route-booking-row">
            <span class="route-fare"><small>Starting from</small><strong>${money(service.price)}</strong></span>
            <span class="route-action">View Buses <span aria-hidden="true">→</span></span>
          </span>
        </span>
        <span class="route-trust" aria-label="Bus and booking information">
          <span class="route-trust-item"><span class="route-trust-icon" aria-hidden="true">▤</span><span><strong>${escapeHTML(busOptions)}</strong><small>Bus options</small></span></span>
          <span class="route-trust-item"><span class="route-trust-icon" aria-hidden="true">✓</span><span><strong>Safe Journey</strong><small>Trusted operators</small></span></span>
          <span class="route-trust-item"><span class="route-trust-icon" aria-hidden="true">↗</span><span><strong>Live Seats</strong><small>Check availability</small></span></span>
        </span>
      </button>`;
    }).join(''):'<div class="route-item">No routes available yet.</div>';
    $('#popularRoutes').querySelectorAll('[data-route-from][data-route-to]').forEach(card=>card.addEventListener('click',async()=>{
      const from=card.dataset.routeFrom||'',to=card.dataset.routeTo||'';
      if(!from.trim()||!to.trim()||normalizeCity(from)===normalizeCity(to)){toast('This route is not available for booking.');return;}
      await openPopularRoute(from,to);
    }));
  }).catch(()=>{});
  $('#swapBtn').onclick=()=>{const a=$('#fromCity').value;$('#fromCity').value=$('#toCity').value;$('#toCity').value=a};
  $('#closeTicketModal')?.addEventListener('click',closeTicket);$('#ticketModal')?.addEventListener('click',e=>{if(e.target.id==='ticketModal')closeTicket()});$('#downloadTicketBtn')?.addEventListener('click',downloadTicket);$('#printTicketBtn')?.addEventListener('click',printTicket);
  $('#searchBtn').onclick=searchBuses;
  $$('.chip[data-filter]').forEach(c=>c.onclick=()=>{$$('.chip[data-filter]').forEach(x=>x.classList.remove('active'));c.classList.add('active');$('#busFilter').value=c.dataset.filter==='all'?'all':c.dataset.filter;searchBuses()});
  ['busFilter','timeFilter','sortFilter'].forEach(id=>$('#'+id)?.addEventListener('change',renderBuses));
  $('#continuePassenger').onclick=continueToPassenger;
  $('#passengerForm').onsubmit=e=>{e.preventDefault();const err=validatePassengers();if(err)return toast(err);if(!$('#terms').checked)return toast('Accept the terms and cancellation policy');paymentSummary();renderPaymentInputs();show('#payment')};
  $$('.pay-method').forEach(b=>b.onclick=()=>{state.method=b.dataset.method||'UPI';$$('.pay-method').forEach(x=>x.classList.remove('active'));b.classList.add('active');renderPaymentInputs()});
  const payButton=$('#payBtn');
  if(payButton){payButton.addEventListener('click',e=>{e.preventDefault();completeBooking()});}
  const accountLink=$('#accountLink');
  if(accountLink)accountLink.addEventListener('click',e=>{e.preventDefault();showPage('profile')});
  const menuToggle=$('#mobileMenuToggle'),nav=$('#customerNav');
  if(menuToggle&&nav){menuToggle.addEventListener('click',()=>{const open=nav.classList.toggle('mobile-open');menuToggle.setAttribute('aria-expanded',String(open));menuToggle.setAttribute('aria-label',open?'Close navigation menu':'Open navigation menu');menuToggle.textContent=open?'✕':'☰'});nav.addEventListener('click',e=>{if(e.target.closest('a')){nav.classList.remove('mobile-open');menuToggle.setAttribute('aria-expanded','false');menuToggle.setAttribute('aria-label','Open navigation menu');menuToggle.textContent='☰'}})}
  const year=$('#copyrightYear');if(year)year.textContent=String(new Date().getFullYear());
  $$('[data-home-link]').forEach(a=>a.addEventListener('click',e=>{e.preventDefault();show('#home')}));
  $$('[data-routes-link]').forEach(a=>a.addEventListener('click',e=>{e.preventDefault();show('#home');setActiveCustomerNav('results');setTimeout(()=>$('#popularRoutes')?.scrollIntoView({behavior:'smooth',block:'center'}),80)}));
  $$('[data-page-link]').forEach(a=>a.addEventListener('click',e=>{e.preventDefault();show('#'+a.dataset.pageLink)}));
  $$('[data-copy]').forEach(b=>b.onclick=()=>copyCode(b.dataset.copy));$$('[data-back-home]').forEach(b=>b.onclick=()=>show('#home'));$$('[data-back-results]').forEach(b=>b.onclick=()=>show('#results'));$$('[data-back-seats]').forEach(b=>b.onclick=()=>show('#seats'));$$('[data-back-passenger]').forEach(b=>b.onclick=()=>show('#passenger'));$$('[data-nav-bookings]').forEach(b=>b.onclick=renderBookings);$$('[data-nav-home]').forEach(b=>b.onclick=()=>show('#home'));
  if($('#editProfile'))$('#editProfile').onclick=saveProfile;if($('#logoutBtn'))$('#logoutBtn').onclick=async()=>{if(confirm('Log out of your account?'))try{await logout()}catch(e){toast(e.message||'Unable to log out. Please try again.')}};
  $('#supportBtn').onclick=async()=>{const message=prompt('Describe your issue:');if(!message)return;try{await api('/support',{method:'POST',body:JSON.stringify({subject:'Customer support',message})});toast('Support request submitted')}catch(e){toast(e.message)}};
  $$('.faq button').forEach(btn=>btn.onclick=()=>{const n=btn.nextElementSibling;n.style.display=n.style.display==='block'?'none':'block'});
  const pending=takeBookingReturnState();
  if(pending&&currentUser()){
    state={...state,from:pending.from||state.from,to:pending.to||state.to,date:pending.date||todayISO(),service:pending.service||null,seats:Array.isArray(pending.seats)?pending.seats:[],method:pending.method||'UPI',amount:Number(pending.amount)||0,holdId:null};
    if(state.service){
      // Re-resolve the exact boarding/drop-off pair after login. The general catalog contains full-route fares.
      try{
        $('#travelDate').value=state.date;
        const journeyServices=await fetchJourneyServices(state.from,state.to,state.date);
        const matched=journeyServices.find(s=>String(s.scheduleId)===String(state.service.scheduleId));
        if(!matched)throw new Error('This bus is no longer available for your selected journey. Please search again.');
        services=journeyServices;state.service=matched;
        if(state.seats.length)await holdSelectedSeats();buildPassengerFields();passengerSummary();show('#passenger');
      }catch(e){state.seats=[];state.service=null;state.holdId=null;toast(e.message||'Your selected seats are no longer available. Please select seats again.');show('#home');}
    }else show('#home');
  }else{
    const h=location.hash.slice(1);if(h==='bookings')renderBookings();else if(['offers','support','profile'].includes(h)){if(h==='profile'){if(!currentUser()){location.href='login.html?return='+encodeURIComponent('index.html#profile');return;}renderProfile()}show('#'+h)}else show('#home');
  }
  updateAuthUi();renderPaymentInputs();
  setInterval(()=>{pruneHolds();if(!$('#results')?.classList.contains('hidden'))refreshSearchResults();else if(!$('#home')?.classList.contains('hidden'))loadBackendCatalog();},5000);
  setInterval(()=>{if(!document.hidden&&!$('#seats')?.classList.contains('hidden'))refreshSeatStatuses();},10000);
}

window.addEventListener('hashchange',()=>{const h=location.hash.replace('#','');if(h==='bookings')renderBookings();else if(['home','offers','support','profile'].includes(h)){if(h==='profile'){if(!currentUser()){location.href='login.html?return='+encodeURIComponent('index.html#profile');return}renderProfile()}show('#'+h)}});
window.addEventListener('storage',e=>{if(['sbtBuses','adminBuses','adminSchedules','adminRoutes','sbtBookings','sbtSeatHolds','sbtUsers','sbtTracking','sbtSession'].includes(e.key)){updateAuthUi();if(!$('#results')?.classList.contains('hidden'))refreshSearchResults();else if(!$('#home')?.classList.contains('hidden'))loadBackendCatalog();if(!$('#bookings')?.classList.contains('hidden'))renderBookings();}});
window.addEventListener('focus',()=>{if(!$('#results')?.classList.contains('hidden'))refreshSearchResults();else if(!$('#home')?.classList.contains('hidden'))loadBackendCatalog();if(!$('#bookings')?.classList.contains('hidden'))renderBookings();if(!$('#seats')?.classList.contains('hidden'))refreshSeatStatuses();});
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&!$('#seats')?.classList.contains('hidden'))refreshSeatStatuses();});
document.addEventListener('DOMContentLoaded',init);
