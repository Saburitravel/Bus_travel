/* Saburi Swami Travel - admin frontend. Backend is authoritative. */
if(typeof protectPage==='function' && !protectPage('admin')) throw new Error('Unauthorized admin access');
const defaultBuses=[];
const defaultRoutes=[];
const defaultSchedules=[];
const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
function readJSON(k,f){try{const v=JSON.parse(localStorage.getItem(k)||'null');return v??f}catch{return f}}
function writeJSON(k,v){localStorage.setItem(k,JSON.stringify(v))}
function toast(msg){const t=$('#toast');if(!t)return;t.textContent=msg;t.classList.add('show');clearTimeout(window.__adminToast);window.__adminToast=setTimeout(()=>t.classList.remove('show'),2400)}
function getStoredBuses(){return readJSON('adminBuses',defaultBuses)}
function getStoredRoutes(){return readJSON('adminRoutes',defaultRoutes)}
function getStoredSchedules(){return readJSON('adminSchedules',defaultSchedules)}
function getUsers(){return readJSON('sbtUsers',[])}
function getBookings(){return readJSON('sbtBookings',[])}
function esc(v){return String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function setText(selector,value){const el=typeof selector==='string'?$(selector):selector;if(el)el.textContent=String(value??'');return Boolean(el)}
function statusClass(v){return /active|open|confirmed|success/i.test(String(v))?'success':''}
function renderTable(id,data,statusCol){const el=$(id);if(!el)return;const display=id==='#busTable'?7:id==='#routeTable'?8:8;el.innerHTML=data.length?data.map((r,i)=>`<tr>${r.slice(0,display).map((v,j)=>j===statusCol?`<td><span class="status ${statusClass(v)}">${esc(v)}</span></td>`:`<td>${esc(v)}</td>`).join('')}<td class="row-actions"><button class="secondary" onclick="editRow('${id}',${i})">Edit</button><button class="secondary danger" onclick="deleteRow('${id}',${i})">Delete</button></td></tr>`).join(''):`<tr><td colspan="10">No records found.</td></tr>`}
function syncPublicMarker(){localStorage.setItem('sbtCatalogVersion',String(Date.now()));window.dispatchEvent(new StorageEvent('storage',{key:'adminSchedules'}))}
const tableConfig={'#busTable':{key:'adminBuses',get:getStoredBuses,status:4},'#routeTable':{key:'adminRoutes',get:getStoredRoutes,status:5},'#scheduleTable':{key:'adminSchedules',get:getStoredSchedules,status:6}};
function persist(id,data){localStorage.setItem(tableConfig[id].key,JSON.stringify(data));syncPublicMarker()}
async function editRow(id,idx){const cfg=tableConfig[id],data=cfg?.get();if(!data?.[idx])return;const row=[...data[idx]];const labels=id==='#busTable'?['Bus number','Operator','Type','Route (display only)','Status','Total seats','Registration number']:id==='#routeTable'?['Route ID','Origin','Destination','Distance','Stops','Status','Duration','ID display']:['Bus number','Route','Departure','Arrival','Fare','Seats','Status','Travel Date'];for(let i=0;i<row.length;i++){if(id==='#scheduleTable'&&(i===4||i>=8))continue;if((i===3)&&id==='#busTable')continue;if(i===7&&id==='#routeTable')continue;const n=prompt(`Edit ${labels[i]||'Value'}`,row[i]);if(n===null)return;row[i]=n.trim()}try{if(id==='#busTable'){const list=await sbtFetch('/buses');const old=list.find(x=>String(x.busNumber)===String(data[idx][0]));if(!old)throw new Error('Bus not found');await sbtFetch('/buses/'+old.id,{method:'PUT',body:JSON.stringify({busNumber:row[0],operatorName:row[1],type:row[2],totalSeats:Number(row[5]),registrationNumber:row[6],status:row[4]})});}else if(id==='#routeTable'){const list=await sbtFetch('/routes');const old=list.find(x=>String(x.routeCode)===String(data[idx][0]));if(!old)throw new Error('Route not found');await sbtFetch('/routes/'+old.id,{method:'PUT',body:JSON.stringify({routeCode:row[0],origin:row[1],destination:row[2],distance:row[3],stops:Number(row[4]),duration:row[6],status:row[5]})});}else{const list=await sbtFetch('/schedules');const old=list.find(x=>String(x.scheduleId)===String(data[idx][8]));if(!old)throw new Error('Schedule not found');const buses=await sbtFetch('/buses'),routes=await sbtFetch('/routes');const bus=buses.find(x=>x.busNumber===row[0]),route=routes.find(x=>`${x.origin} → ${x.destination}`===row[1]) || routes.find(x=>String(x.id)===String(old.routeId));if(!bus||!route)throw new Error('Schedule references invalid bus/route');await sbtFetch('/schedules/'+old.scheduleId,{method:'PUT',body:JSON.stringify({busId:bus.id,routeId:route.id,travelDate:row[7]||old.travelDate,departureTime:row[2],arrivalTime:row[3],status:row[6]})});}await sbtPull();render();toast('Updated successfully')}catch(e){toast(e.message||'Update failed')}}
async function deleteRow(id,idx){const cfg=tableConfig[id],data=cfg?.get();if(!data?.[idx])return;if(!confirm('Delete this record?'))return;try{if(id==='#busTable'){const list=await sbtFetch('/buses');const old=list.find(x=>x.busNumber===data[idx][0]);if(old)await sbtFetch('/buses/'+old.id,{method:'DELETE'});}else if(id==='#routeTable'){const list=await sbtFetch('/routes');const old=list.find(x=>x.routeCode===data[idx][0]);if(old)await sbtFetch('/routes/'+old.id,{method:'DELETE'});}else{const sid=data[idx][8];if(sid)await sbtFetch('/schedules/'+sid,{method:'DELETE'});}await sbtPull();render();toast('Deleted successfully')}catch(e){toast(e.message||'Delete failed')}}

function bookingRows(){return getBookings().map(b=>[b.id,b.customerName||b.customerPhone||'Customer',`${b.from} → ${b.to}`,(b.seats||[]).join(', '),`₹${Number(b.amount||0).toLocaleString('en-IN')}`,b.status])}
function renderBookings(){const body=$('#adminBookingTable'),data=bookingRows();if(!body)return;body.innerHTML=data.length?data.map((r)=>`<tr>${r.map((v,j)=>`<td>${j===5?`<span class="status ${statusClass(v)}">${esc(v)}</span>`:esc(v)}</td>`).join('')}<td class="row-actions"><button class="secondary" onclick="viewBooking('${r[0]}')">VIEW</button>${r[5]==='Pending'?`<button class="primary" onclick="confirmBooking('${r[0]}')">CONFIRM BOOKING</button><button class="secondary danger" onclick="rejectBooking('${r[0]}')">REJECT</button>`:r[5]==='Confirmed'?`<button class="secondary danger" onclick="adminCancelBooking('${r[0]}')">CANCEL</button>`:''}</td></tr>`).join(''):`<tr><td colspan="7">No bookings yet.</td></tr>`} 
async function confirmBooking(displayId){const b=getBookings().find(x=>x.id===displayId);if(!b?.dbId)return toast('Booking record not found');if(!confirm(`Confirm payment and booking ${displayId}?`))return;try{await sbtFetch('/admin/bookings/'+encodeURIComponent(b.dbId)+'/confirm',{method:'POST'});await sbtPull();render();toast('Payment verified and booking confirmed')}catch(e){toast(e.message)}}
async function rejectBooking(displayId){const b=getBookings().find(x=>x.id===displayId);if(!b?.dbId)return toast('Booking record not found');if(!confirm(`Reject booking ${displayId} and release its seats?`))return;try{await sbtFetch('/admin/bookings/'+encodeURIComponent(b.dbId)+'/reject',{method:'POST'});await sbtPull();render();toast('Booking rejected and seats released')}catch(e){toast(e.message)}}
async function adminCancelBooking(displayId){const b=getBookings().find(x=>x.id===displayId);if(!b?.dbId)return toast('Booking record not found');if(!confirm('Cancel this booking and record refund?'))return;try{await sbtFetch('/admin/bookings/'+encodeURIComponent(b.dbId)+'/cancel',{method:'POST'});await sbtPull();render();toast('Booking cancelled and refund recorded')}catch(e){toast(e.message)}}
function viewBooking(id){const b=getBookings().find(x=>x.id===id);if(!b)return;alert(`Booking: ${b.id}\nCustomer: ${b.customerName||b.customerPhone}\nRoute: ${b.from} → ${b.to}\nSeats: ${(b.seats||[]).join(', ')}\nAmount: ₹${b.amount}\nStatus: ${b.status}`)}
function renderUsers(){const bookings=getBookings();const body=$('#userTable');if(!body)return;const users=getUsers();body.innerHTML=users.length?users.map(u=>{const count=bookings.filter(b=>String(b.customerPhone)===String(u.phone)).length;return `<tr><td>${esc(u.name)}</td><td>${esc(u.email||'—')}</td><td>+91 ${esc(u.phone)}</td><td>${count}</td><td>${esc(u.joined||'')}</td><td><span class="status ${u.status==='ACTIVE'?'success':''}">${esc(u.status||'ACTIVE')}</span> <button class="secondary" onclick="toggleCustomer('${u.id}','${u.status||'ACTIVE'}')">${u.status==='ACTIVE'?'DEACTIVATE':'ACTIVATE'}</button></td></tr>`}).join(''):`<tr><td colspan="6">No customers registered.</td></tr>`}
async function toggleCustomer(id,status){try{await sbtFetch('/users/'+id+'/status',{method:'PUT',body:JSON.stringify({status:status==='ACTIVE'?'INACTIVE':'ACTIVE'})});await sbtPull();render();toast('Customer status updated')}catch(e){toast(e.message)}}
async function renderPaymentsMetrics(){const section=document.querySelector('[data-admin-view="payments"]');if(!section)return;try{const [payments,refunds]=await Promise.all([sbtFetch('/admin/payments'),sbtFetch('/admin/refunds')]);const now=new Date(),today=now.toISOString().slice(0,10),start=new Date(now);start.setHours(0,0,0,0);start.setDate(start.getDate()-start.getDay());const startIso=start.toISOString().slice(0,10),todayAmt=payments.filter(p=>String(p.createdAt||'').slice(0,10)===today&&p.status==='SUCCESS').reduce((a,p)=>a+Number(p.amount||0),0),weekAmt=payments.filter(p=>String(p.createdAt||'').slice(0,10)>=startIso&&String(p.createdAt||'').slice(0,10)<=today&&p.status==='SUCCESS').reduce((a,p)=>a+Number(p.amount||0),0),refundAmt=refunds.filter(r=>r.status==='REFUNDED').reduce((a,r)=>a+Number(r.amount||0),0),success=payments.filter(p=>p.status==='SUCCESS').length;const m=section.querySelectorAll('.metric');const vals=[money(todayAmt),money(weekAmt),money(refundAmt),(payments.length?Math.round(success/payments.length*100):0)+'%'];m.forEach((card,i)=>{const value=card?.querySelector('b');if(value&&i<vals.length)value.textContent=vals[i]});}catch(e){console.warn('Payment metrics failed',e.message)}}
function renderTransactions(){const target=$('#transactions');if(!target)return;const tx=getBookings().map(b=>`<div class="transaction"><span>${esc(b.id)}</span><b>${money(b.amount)}</b><span class="${['Paid','SUCCESS'].includes(b.payment?.status)?'success':''}">${esc(b.payment?.status||b.status)}</span></div>`);target.innerHTML=tx.length?tx.slice(0,10).join(''):'<p style="font-size:10px;color:#777">No transactions yet.</p>'}
function money(n){return `₹${Number(n||0).toLocaleString('en-IN')}`}
function renderMetrics(){const bookings=getBookings(),today=new Date().toISOString().slice(0,10),todayB=bookings.filter(b=>b.date===today&&b.status==='Confirmed'),revenue=todayB.reduce((s,b)=>s+Number(b.amount||0),0),active=getStoredBuses().filter(b=>String(b[4]).toLowerCase()==='active').length,totalSeats=getStoredSchedules().reduce((s,b)=>s+Number(b[5]||0),0),used=todayB.reduce((s,b)=>s+(b.seats||[]).length,0),occ=totalSeats?Math.round(used/totalSeats*100):0;const m=$$('.metric');const vals=[todayB.length.toLocaleString('en-IN'),money(revenue),String(active),occ+'%'];m.slice(0,4).forEach((card,i)=>{const value=card?.querySelector('b');if(value)value.textContent=vals[i]})}
function charts(){
 const bookings=getBookings(),now=new Date();
 const days=[...Array(7)].map((_,i)=>{const d=new Date(now);d.setHours(0,0,0,0);d.setDate(d.getDate()-(6-i));return d});
 const key=d=>`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
 const label=d=>d.toLocaleDateString('en-IN',{day:'numeric',month:'short'});
 const bookingCounts=days.map(d=>bookings.filter(b=>String(b.date||b.bookingDate||'').slice(0,10)===key(d)&&!['Cancelled','Rejected','Refunded'].includes(String(b.status||''))).length);
 const revenueValues=days.map(d=>bookings.filter(b=>String(b.date||b.bookingDate||'').slice(0,10)===key(d)&&String(b.status||'').toLowerCase()==='confirmed').reduce((sum,b)=>sum+Math.max(0,Number(b.amount||b.totalAmount||0)),0));
 const renderChart=(id,values,format)=>{const el=$('#'+id);if(!el)return;const max=Math.max(...values,1);el.innerHTML=`<div class="chart-bars" role="img" aria-label="${id==='bookingChart'?'Bookings by day for the last seven days':'Confirmed booking revenue by day for the last seven days'}">${values.map((v,i)=>`<div class="chart-column" title="${esc(label(days[i]))}: ${esc(format(v))}"><div class="chart-track"><i class="chart-bar" style="height:${Math.max(v>0?5:0,Math.round(v/max*100))}%" aria-hidden="true"></i></div><span class="chart-value">${esc(format(v))}</span><small>${esc(label(days[i]))}</small></div>`).join('')}</div>${values.every(v=>v===0)?'<p class="chart-empty">No data for the last 7 days yet.</p>':''}`};
 renderChart('bookingChart',bookingCounts,v=>String(v));
 renderChart('revenueChart',revenueValues,v=>money(v));
}
let selectedStopRouteId = null;
let activeStopRoute = null;
let activeRouteStops = [];
let activeRouteFares = [];

function pointOptions(route, stops){
 const points=[{key:'ORIGIN',label:route.origin,index:0}];
 (stops||[]).slice().sort((a,b)=>Number(a.stopOrder)-Number(b.stopOrder)).forEach((stop,i)=>points.push({key:'STOP:'+stop.id,label:stop.city||stop.name,index:i+1}));
 points.push({key:'DESTINATION',label:route.destination,index:points.length});
 return points;
}
function selectedPointLabel(key){
 const points=activeStopRoute?pointOptions(activeStopRoute,activeRouteStops):[];
 return points.find(p=>p.key===key)?.label||'Unknown stop';
}
function emptyState(title,description){return `<div class="stop-empty"><strong>${esc(title)}</strong>${esc(description)}</div>`;}

async function renderStops(){
 const routeSelect=$('#stopRouteSelect'),stopList=$('#routeStopsList'),fareList=$('#routeFaresList');
 if(!routeSelect||!stopList||!fareList)return;
 try{
  const routes=await sbtFetch('/routes');
  if(!routes.length){
   activeStopRoute=null;activeRouteStops=[];activeRouteFares=[];routeSelect.innerHTML='<option value="">No routes available</option>';
   stopList.innerHTML=emptyState('Create a route first','Add a route in Route Management before adding stops.');
   fareList.innerHTML=emptyState('No route selected','Create a route before configuring fares.');
   setText('#stopCountBadge','0 stops');setText('#fareCountBadge','0 fares');
   if($('#addStopBtn'))$('#addStopBtn').disabled=true;if($('#addFareBtn'))$('#addFareBtn').disabled=true;if($('#addFareFooterBtn'))$('#addFareFooterBtn').disabled=true;
   return;
  }
  const requested=String(selectedStopRouteId||routeSelect.value||routes[0].id);
  activeStopRoute=routes.find(r=>String(r.id)===requested)||routes[0];selectedStopRouteId=String(activeStopRoute.id);
  routeSelect.innerHTML=routes.map(r=>`<option value="${esc(String(r.id))}" ${String(r.id)===selectedStopRouteId?'selected':''}>${esc(r.routeCode)} · ${esc(r.origin)} → ${esc(r.destination)}</option>`).join('');
  const [stops,fares]=await Promise.all([
   sbtFetch('/routes/'+encodeURIComponent(activeStopRoute.id)+'/stops'),
   sbtFetch('/admin/routes/'+encodeURIComponent(activeStopRoute.id)+'/fares')
  ]);
  activeRouteStops=(stops||[]).slice().sort((a,b)=>Number(a.stopOrder)-Number(b.stopOrder));
  activeRouteFares=fares||[];
  setText('#stopCountBadge',`${activeRouteStops.length+2} route points`);
  setText('#fareCountBadge',`${activeRouteFares.length} fare${activeRouteFares.length===1?'':'s'}`);
  if($('#addStopBtn'))$('#addStopBtn').disabled=false;
  const canAddFare=pointOptions(activeStopRoute,activeRouteStops).length>=2;
  if($('#addFareBtn'))$('#addFareBtn').disabled=!canAddFare;if($('#addFareFooterBtn'))$('#addFareFooterBtn').disabled=!canAddFare;
  const originCard=`<article class="route-stop-item route-stop-endpoint"><span class="route-stop-number">1</span><div class="route-stop-main"><strong>${esc(activeStopRoute.origin)}</strong><small>Route origin</small></div><div class="stop-row-actions"><span class="route-stop-kind">Starting stop · Fixed</span></div></article>`;
  const intermediateCards=activeRouteStops.map((stop,i)=>`<article class="route-stop-item"><span class="route-stop-number">${i+2}</span><div class="route-stop-main"><strong>${esc(stop.name)}</strong><small>${esc(stop.city||'City not specified')} · Stop order ${Number(stop.stopOrder)}${stop.latitude!=null&&stop.longitude!=null?` · ${esc(stop.latitude)}, ${esc(stop.longitude)}`:''}</small></div><div class="stop-row-actions"><span class="route-stop-kind">Intermediate stop</span><button class="stop-icon-btn" type="button" title="Edit stop" aria-label="Edit ${esc(stop.name)}" data-stop-action="edit-stop" data-stop-id="${esc(String(stop.id))}">Edit</button><button class="stop-icon-btn danger" type="button" title="Delete stop" aria-label="Delete ${esc(stop.name)}" data-stop-action="delete-stop" data-stop-id="${esc(String(stop.id))}">Delete</button></div></article>`).join('');
  const noIntermediate=activeRouteStops.length?'':`<div class="stop-inline-empty">No intermediate stops yet. This bus travels directly from ${esc(activeStopRoute.origin)} to ${esc(activeStopRoute.destination)}.</div>`;
  const destinationCard=`<article class="route-stop-item route-stop-endpoint"><span class="route-stop-number">${activeRouteStops.length+2}</span><div class="route-stop-main"><strong>${esc(activeStopRoute.destination)}</strong><small>Route destination</small></div><div class="stop-row-actions"><span class="route-stop-kind">Destination stop · Fixed</span></div></article>`;
  stopList.innerHTML=originCard+intermediateCards+noIntermediate+destinationCard;
  fareList.innerHTML=activeRouteFares.length?activeRouteFares.map(fare=>`<article class="route-fare-item"><div class="route-fare-main"><strong>${esc(fare.fromLabel)} → ${esc(fare.toLabel)}</strong><small>Independently configured journey fare</small><div class="route-fare-amount">${money(fare.fare)}</div></div><div class="stop-row-actions"><button class="stop-icon-btn" type="button" title="Edit fare" aria-label="Edit fare ${esc(fare.fromLabel)} to ${esc(fare.toLabel)}" data-stop-action="edit-fare" data-fare-id="${esc(String(fare.id))}">Edit</button><button class="stop-icon-btn danger" type="button" title="Delete fare" aria-label="Delete fare ${esc(fare.fromLabel)} to ${esc(fare.toLabel)}" data-stop-action="delete-fare" data-fare-id="${esc(String(fare.id))}">Delete</button></div></article>`).join(''):emptyState('No fares configured','Add a fare for any valid boarding and destination pair. Customers cannot book a pair until its fare is configured.');
 }catch(e){
  stopList.innerHTML=emptyState('Unable to load stops',e.message||'Please refresh and try again.');
  fareList.innerHTML=emptyState('Unable to load fares',e.message||'Please refresh and try again.');
  toast(e.message||'Unable to load Stop Management');
 }
}

function setModal(title,html,onSubmit){
 const titleEl=$('#modalTitle'),formEl=$('#adminForm'),backdrop=$('#modalBackdrop');
 if(!titleEl||!formEl||!backdrop){toast('Admin form is unavailable. Refresh the page and try again.');return;}
 titleEl.textContent=title;formEl.innerHTML=html;backdrop.classList.remove('hidden');
 formEl.onsubmit=async event=>{event.preventDefault();const submit=formEl.querySelector('button[type="submit"]');if(submit)submit.disabled=true;try{await onSubmit(new FormData(formEl));backdrop.classList.add('hidden');await sbtPull();render();}catch(error){toast(error?.message||'Unable to save changes');}finally{if(submit)submit.disabled=false;}};
}
function stopFormHtml(stop){
 const isEdit=!!stop;const order=stop?.stopOrder??(activeRouteStops.reduce((m,s)=>Math.max(m,Number(s.stopOrder)||0),0)+1);
 return `<p class="stop-form-note">Route: <strong>${esc(activeStopRoute.routeCode)} · ${esc(activeStopRoute.origin)} → ${esc(activeStopRoute.destination)}</strong><br>Adding a stop does not add or calculate any fare. Configure fares separately below.</p><div class="stop-form-grid"><label>Stop name<input name="name" maxlength="80" value="${esc(stop?.name||'')}" placeholder="e.g. Vinchur" required></label><label>City<input name="city" maxlength="80" value="${esc(stop?.city||'')}" placeholder="City / town"></label><label class="wide">Route order<input name="stopOrder" type="number" min="1" max="200" step="1" value="${esc(String(order))}" required><small>Stops are displayed by this order, between route origin and destination.</small></label><label>Latitude (optional)<input name="latitude" type="number" min="-90" max="90" step="any" value="${stop?.latitude==null?'':esc(String(stop.latitude))}" placeholder="19.XXXXXX"></label><label>Longitude (optional)<input name="longitude" type="number" min="-180" max="180" step="any" value="${stop?.longitude==null?'':esc(String(stop.longitude))}" placeholder="74.XXXXXX"></label></div><div class="stop-form-actions"><button type="button" class="secondary" id="cancelStopForm">Cancel</button><button type="submit" class="primary">${isEdit?'Save Stop':'Add Stop'}</button></div>`;
}
function openStopDialog(stop=null){
 if(!activeStopRoute)return toast('Select a route first.');
 setModal(stop?'Edit route stop':'Add stop to route',stopFormHtml(stop),async fd=>{
  const order=Number(fd.get('stopOrder'));if(!Number.isInteger(order)||order<1||order>200)throw new Error('Stop order must be between 1 and 200.');
  const parseOptional=(v,label,min,max)=>{const raw=String(v||'').trim();if(!raw)return null;const n=Number(raw);if(!Number.isFinite(n)||n<min||n>max)throw new Error(label+' is out of range.');return n;};
  const payload={name:String(fd.get('name')||'').trim(),city:String(fd.get('city')||'').trim(),stopOrder:order,latitude:parseOptional(fd.get('latitude'),'Latitude',-90,90),longitude:parseOptional(fd.get('longitude'),'Longitude',-180,180)};
  if(!payload.name)throw new Error('Stop name is required.');
  const path=stop?'/admin/stops/'+encodeURIComponent(stop.id):'/admin/routes/'+encodeURIComponent(activeStopRoute.id)+'/stops';
  await sbtFetch(path,{method:stop?'PUT':'POST',body:JSON.stringify(payload)});
  toast(stop?'Stop updated successfully.':'Stop added. Add any fares separately in Configured fares.');
 });
 const cancel=$('#cancelStopForm');if(cancel)cancel.onclick=()=>$('#modalBackdrop')?.classList.add('hidden');
}
function fareFormHtml(fare=null){
 const points=pointOptions(activeStopRoute,activeRouteStops);
 const fromOptions=points.slice(0,-1).map(p=>`<option value="${esc(p.key)}" ${p.key===(fare?.fromKey||'ORIGIN')?'selected':''}>${esc(p.label)}</option>`).join('');
 const currentFrom=points.findIndex(p=>p.key===(fare?.fromKey||'ORIGIN'));
 const destinationOptions=points.slice(Math.max(1,currentFrom+1)).map(p=>`<option value="${esc(p.key)}" ${p.key===(fare?.toKey||'')?'selected':''}>${esc(p.label)}</option>`).join('');
 return `<p class="stop-form-note">Every price is independent. The system will not add, subtract, or automatically redistribute fares when you add, edit, or delete another fare.</p><div class="stop-form-grid"><label class="wide">Boarding stop<select name="fromKey" required>${fromOptions}</select></label><label class="wide">Destination stop<select name="toKey" required>${destinationOptions}</select></label><label class="wide">Fare (₹)<input name="fare" type="number" min="0.01" max="100000" step="0.01" value="${fare?esc(String(fare.fare)) : ''}" placeholder="Enter fare" required></label></div><div class="stop-form-actions"><button type="button" class="secondary" id="cancelFareForm">Cancel</button><button type="submit" class="primary">${fare?'Save Fare':'Add Fare'}</button></div>`;
}
function openFareDialog(fare=null){
 if(!activeStopRoute)return toast('Select a route first.');
 const points=pointOptions(activeStopRoute,activeRouteStops);if(points.length<2)return toast('This route needs an origin and destination before fares can be added.');
 setModal(fare?'Edit fare':'Add fare',fareFormHtml(fare),async fd=>{
  const payload={fromKey:String(fd.get('fromKey')||''),toKey:String(fd.get('toKey')||''),fare:Number(fd.get('fare'))};
  if(!Number.isFinite(payload.fare)||payload.fare<=0||payload.fare>100000)throw new Error('Fare must be greater than ₹0 and at most ₹100,000.');
  const from=points.find(p=>p.key===payload.fromKey),to=points.find(p=>p.key===payload.toKey);if(!from||!to||to.index<=from.index)throw new Error('Choose a destination that appears after the boarding stop.');
  if(activeRouteFares.some(x=>String(x.id)!==String(fare?.id||'')&&x.fromKey===payload.fromKey&&x.toKey===payload.toKey))throw new Error('A fare already exists for this stop pair. Edit the existing fare instead.');
  const path=fare?'/admin/fares/'+encodeURIComponent(fare.id):'/admin/routes/'+encodeURIComponent(activeStopRoute.id)+'/fares';
  await sbtFetch(path,{method:fare?'PUT':'POST',body:JSON.stringify(payload)});
  toast(fare?'Fare updated. Other fares were not changed.':'Fare added. No other fares were changed.');
 });
 const fromSel=$('#adminForm [name="fromKey"]'),toSel=$('#adminForm [name="toKey"]'),cancel=$('#cancelFareForm');
 const refreshDestinations=()=>{if(!fromSel||!toSel)return;const selectedFrom=points.find(p=>p.key===fromSel.value);const currentTo=toSel.value;const options=points.filter(p=>p.index>(selectedFrom?.index??-1));toSel.innerHTML=options.map(p=>`<option value="${esc(p.key)}">${esc(p.label)}</option>`).join('');if(options.some(p=>p.key===currentTo))toSel.value=currentTo;};
 if(fromSel)fromSel.addEventListener('change',refreshDestinations);
 if(cancel)cancel.onclick=()=>$('#modalBackdrop')?.classList.add('hidden');
}
async function deleteStop(id){
 const stop=activeRouteStops.find(s=>String(s.id)===String(id));if(!stop)return toast('Stop not found. Refresh and try again.');
 if(!confirm(`Delete stop “${stop.name}”? Any fares that directly reference this stop will also be removed. Other fares will not be changed.`))return;
 try{await sbtFetch('/admin/stops/'+encodeURIComponent(id),{method:'DELETE'});await renderStops();toast('Stop deleted. Review fares for the updated route.');}catch(e){toast(e.message||'Unable to delete stop');}
}
async function deleteFare(id){
 const fare=activeRouteFares.find(f=>String(f.id)===String(id));if(!fare)return toast('Fare not found. Refresh and try again.');
 if(!confirm(`Delete fare ${fare.fromLabel} → ${fare.toLabel} (${money(fare.fare)})? This journey cannot be booked until a fare is added again. Other fares will remain unchanged.`))return;
 try{await sbtFetch('/admin/fares/'+encodeURIComponent(id),{method:'DELETE'});await renderStops();toast('Fare deleted. Other fares were not changed.');}catch(e){toast(e.message||'Unable to delete fare');}
}
document.addEventListener('click',async event=>{
 const target=event.target instanceof Element?event.target.closest('[data-stop-action]'):null;if(!target)return;
 try{
  if(target.dataset.stopAction==='edit-stop'){const stop=activeRouteStops.find(s=>String(s.id)===String(target.dataset.stopId));if(stop)openStopDialog(stop);}
  else if(target.dataset.stopAction==='delete-stop')await deleteStop(target.dataset.stopId);
  else if(target.dataset.stopAction==='edit-fare'){const fare=activeRouteFares.find(f=>String(f.id)===String(target.dataset.fareId));if(fare)openFareDialog(fare);}
  else if(target.dataset.stopAction==='delete-fare')await deleteFare(target.dataset.fareId);
 }catch(error){toast(error?.message||'Unable to complete Stop Management action');}
});
function render(){renderTable('#busTable',getStoredBuses(),4);renderTable('#routeTable',getStoredRoutes(),5);renderTable('#scheduleTable',getStoredSchedules(),6);renderBookings();renderUsers();renderTransactions();renderMetrics();renderPaymentsMetrics();charts();initAdminTracking();if(document.querySelector('[data-admin-view="stops"]'))renderStops()}
function switchAdmin(page){if(!document.querySelector(`[data-admin-view="${page}"]`))page='dashboard';$$('.admin-view').forEach(v=>v.classList.toggle('hidden',v.dataset.adminView!==page));$$('#adminNav button').forEach(b=>b.classList.toggle('active',b.dataset.adminPage===page));const titles={dashboard:'Dashboard',buses:'Bus management',routes:'Route management',schedules:'Schedule management',bookings:'Booking management',tracking:'Live tracking',stops:'Stop management',users:'User management',payments:'Payments & revenue',reports:'Reports & settings'};setText('#adminPageTitle',titles[page]||'Dashboard');if(location.hash.slice(1)!==page)history.replaceState(null,'','#'+page)}
$$('[data-admin-page]').forEach(b=>b.onclick=()=>switchAdmin(b.dataset.adminPage));
const formTemplates={
 bus:{title:'Add bus',html:'<label>BUS NUMBER<input name="bus" placeholder="SB-105" required></label><label>OPERATOR<input name="operator" placeholder="Saburi" required></label><label>TYPE<select name="type"><option>Sleeper</option><option>Seater</option><option>AC Sleeper</option><option>Volvo</option></select></label><label>TOTAL SEATS<input name="seats" type="number" min="1" max="100" value="40" required></label><label>REGISTRATION<input name="registration" placeholder="MH-15-AB-1234"></label><label>STATUS<select name="status"><option>Active</option><option>Maintenance</option><option>Inactive</option></select></label><button class="primary">ADD BUS</button>'},
 route:{title:'Add route',html:'<label>ROUTE ID<input name="id" placeholder="R005" required></label><label>ORIGIN<input name="origin" required></label><label>DESTINATION<input name="destination" required></label><label>DISTANCE<input name="distance" placeholder="85 km"></label><label>STOPS<input name="stops" type="number" min="0" value="3" required></label><label>DURATION<input name="duration" placeholder="3h 00m"></label><button class="primary">ADD ROUTE</button>'},
 schedule:{title:'New schedule',html:'<label>BUS<select name="bus" id="scheduleBus" required></select></label><label>ROUTE<select name="route" id="scheduleRoute" required></select></label><label>TRAVEL DATE<input name="travelDate" type="date" required></label><div class="form-grid"><label>DEPART<input name="depart" type="time" value="18:30" required></label><label>ARRIVE<input name="arrive" type="time" value="21:10" required></label></div><p style="font-size:10px;color:#777">Seat inventory is automatically created from the selected bus total seat count.</p><button class="primary">CREATE SCHEDULE</button>'}
};
async function openModal(type){
 const tpl=formTemplates[type];if(!tpl)return;const titleEl=$('#modalTitle'),formEl=$('#adminForm'),backdrop=$('#modalBackdrop');if(!titleEl||!formEl||!backdrop){toast('Admin form is unavailable. Refresh the admin page and try again.');return;}titleEl.textContent=tpl.title;formEl.innerHTML=tpl.html;backdrop.classList.remove('hidden');
 if(type==='schedule'){const d=$('#adminForm [name=travelDate]');if(d){const x=new Date();x.setDate(x.getDate()+1);d.value=x.toISOString().slice(0,10);}const bs=$('#scheduleBus'),rs=$('#scheduleRoute');bs.innerHTML=getStoredBuses().map(b=>`<option value="${esc(b[0])}">${esc(b[0])}</option>`).join('');rs.innerHTML=getStoredRoutes().filter(r=>r[5]!=='Inactive').map(r=>`<option value="${esc(r[0])}">${esc(r[1])} → ${esc(r[2])}</option>`).join('');}
 $('#adminForm').onsubmit=async e=>{e.preventDefault();const fd=new FormData(e.target);try{
   if(type==='bus'){await sbtFetch('/buses',{method:'POST',body:JSON.stringify({busNumber:String(fd.get('bus')).trim(),operatorName:String(fd.get('operator')).trim(),type:String(fd.get('type')),totalSeats:Number(fd.get('seats')),registrationNumber:String(fd.get('registration')).trim(),status:String(fd.get('status'))})});}
   else if(type==='route'){await sbtFetch('/routes',{method:'POST',body:JSON.stringify({routeCode:String(fd.get('id')).trim(),origin:String(fd.get('origin')).trim(),destination:String(fd.get('destination')).trim(),distance:String(fd.get('distance')).trim(),stops:Number(fd.get('stops')),duration:String(fd.get('duration')).trim(),status:'Active'})});}
   else {const bus=await sbtFetch('/buses');const routes=await sbtFetch('/routes');const bv=bus.find(x=>String(x.busNumber)===String(fd.get('bus')));const rv=routes.find(x=>String(x.routeCode)===String(fd.get('route')));if(!bv||!rv)throw new Error('Select a valid bus and route');await sbtFetch('/schedules',{method:'POST',body:JSON.stringify({busId:bv.id,routeId:rv.id,travelDate:String(fd.get('travelDate')),departureTime:String(fd.get('depart')),arrivalTime:String(fd.get('arrive')),status:'Open'})});}
   $('#modalBackdrop').classList.add('hidden');await sbtPull();render();toast('Saved successfully');
 }catch(err){toast(err.message||'Save failed')}};
}
$$('[data-open-modal]').forEach(btn=>btn.addEventListener('click',()=>openModal(btn.dataset.openModal)));
const closeModalBtn=$('#closeModal'),modalBackdrop=$('#modalBackdrop'),exportBookingsBtn=$('#exportBookings');if(closeModalBtn)closeModalBtn.onclick=()=>modalBackdrop?.classList.add('hidden');if(modalBackdrop)modalBackdrop.onclick=e=>{if(e.target.id==='modalBackdrop')modalBackdrop.classList.add('hidden')};$('#addStopBtn')?.addEventListener('click',()=>openStopDialog());$('#addFareBtn')?.addEventListener('click',()=>openFareDialog());$('#addFareFooterBtn')?.addEventListener('click',()=>openFareDialog());$('#stopRouteSelect')?.addEventListener('change',event=>{selectedStopRouteId=event.target.value;renderStops()});if(exportBookingsBtn)exportBookingsBtn.onclick=()=>{const blob=new Blob([JSON.stringify(getBookings(),null,2)],{type:'application/json'}),a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='saburi-bookings.json';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),500)};
$$('.report-list button').forEach(btn=>btn.onclick=async()=>{const title=btn.textContent.trim().replace(/EXPORT/i,'').trim(),type=title.split(' ')[0].toLowerCase();try{const data=await sbtFetch('/admin/reports/'+type);const blob=new Blob([JSON.stringify(data,null,2)],{type:'application/json'}),a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=title.toLowerCase().replace(/\s+/g,'-')+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),500)}catch(e){toast(e.message)}});
function getTrackingState(){return readJSON('adminBusTracking',{})}
async function initAdminTracking(){const sel=$('#adminTrackingBus');if(!sel)return;const buses=getStoredBuses();const schedules=getStoredSchedules();sel.innerHTML=schedules.map(s=>`<option value="${esc(s[8]||'')}">${esc(s[0])} • ${esc(s[1])} • ${esc(s[7]||'')}</option>`).join('');$('#adminStartTracking').onclick=()=>showAdminTracking(sel.value);$('#adminRefreshTracking').onclick=refreshAdminTracking;if(sel.value)await showAdminTracking(sel.value)}
async function showAdminTracking(scheduleId){if(!scheduleId)return;const nodes={bus:$('#adminTrackBusName'),route:$('#adminTrackRoute'),status:$('#adminTrackStatus'),progress:$('#adminTrackProgress'),percent:$('#adminTrackPercent'),location:$('#adminTrackLocation'),eta:$('#adminTrackEta')};if(Object.values(nodes).some(n=>!n)){console.warn('Tracking panel is not present on this admin page; skipping tracking update.');return;}try{const cur=await sbtFetch('/tracking/'+encodeURIComponent(scheduleId));setText(nodes.bus,cur.busId);setText(nodes.route,cur.route);setText(nodes.status,cur.status);nodes.progress.style.width=Math.min(100,Math.max(0,Number(cur.progress)||0))+'%';setText(nodes.percent,Math.round(Number(cur.progress)||0)+'%');setText(nodes.location,cur.currentLocation||'On route');setText(nodes.eta,cur.progress>=100?'Arrived':'~'+Math.max(1,Math.round((100-(Number(cur.progress)||0))*.6))+' min')}catch(e){toast(e.message||'Unable to load tracking details')}}
async function refreshAdminTracking(){const id=$('#adminTrackingBus').value;if(!id)return;try{await sbtFetch('/tracking/'+id+'/advance',{method:'POST'});await showAdminTracking(id);toast('Location updated')}catch(e){toast(e.message)}}

$$('[data-booking-filter]').forEach(btn=>btn.onclick=()=>{ $$('[data-booking-filter]').forEach(x=>x.classList.remove('active'));btn.classList.add('active');const filter=btn.dataset.bookingFilter;const body=$('#adminBookingTable');const data=bookingRows().filter(r=>filter==='all'||r[5]===filter);body.innerHTML=data.length?data.map(r=>`<tr>${r.map((v,j)=>`<td>${j===5?`<span class="status ${statusClass(v)}">${esc(v)}</span>`:esc(v)}</td>`).join('')}<td class="row-actions"><button class="secondary" onclick="viewBooking('${r[0]}')">VIEW</button>${r[5]==='Pending'?`<button class="primary" onclick="confirmBooking('${r[0]}')">CONFIRM BOOKING</button><button class="secondary danger" onclick="rejectBooking('${r[0]}')">REJECT</button>`:r[5]==='Confirmed'?`<button class="secondary danger" onclick="adminCancelBooking('${r[0]}')">CANCEL</button>`:''}</td></tr>`).join(''):`<tr><td colspan="7">No ${filter.toLowerCase()} bookings.</td></tr>`});
const saveSettings=$('[data-admin-view="reports"] .primary');if(saveSettings)saveSettings.onclick=async()=>{const inputs=[...document.querySelectorAll('[data-admin-view="reports"] input[type=checkbox]')];const keys=['smsNotifications','emailConfirmations','autoCancelUnpaidHolds','maintenanceMode'];const payload=Object.fromEntries(inputs.map((x,i)=>[keys[i]??('setting'+i),x.checked]));try{await sbtFetch('/admin/settings',{method:'PUT',body:JSON.stringify(payload)});writeJSON('sbtAdminSettings',inputs.map(x=>x.checked));toast('Settings saved to backend')}catch(e){toast(e.message)}};
window.addEventListener('hashchange',()=>switchAdmin(location.hash.slice(1)||'dashboard'));
window.addEventListener('storage',e=>{if(['sbtBookings','sbtUsers','adminBuses','adminRoutes','adminSchedules','sbtCatalogVersion','adminBusTracking'].includes(e.key))render()});
render();switchAdmin(location.hash.slice(1)||'dashboard');
