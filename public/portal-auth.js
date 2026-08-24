(() => {
  const role = location.pathname.split('/').filter(Boolean)[0];
  const style = document.createElement('style'); style.textContent = '.hidden{display:none!important}'; document.head.append(style);
  const key = `movo_${role}_token`;
  const root = document.querySelector('main');
  const login = document.createElement('section');
  login.id = 'login-panel';
  // Password is optional — leaving it blank matches the Yango-style flow: phone number in,
  // an SMS code back, no email/password required.
  login.innerHTML = `<hr><h2>Sign In</h2><form id="login-form"><input id="login-phone" required placeholder="Phone, e.g. +2588... or +25078..."><input id="login-password" type="password" placeholder="Password (leave blank for an SMS code)"><button>Sign In</button></form><form id="login-otp-form" class="hidden"><p>Enter the 6-digit code sent by SMS.</p><input id="login-otp" inputmode="numeric" maxlength="6" required placeholder="OTP"><button>Verify code</button></form><p id="login-error"></p>`;
  root.append(login);
  const app = document.createElement('section');
  app.id = 'app'; app.className = 'hidden';
  app.innerHTML = `<hr><h2 id="app-title">MOVO</h2><button id="refresh">Refresh</button><button id="logout">Logout</button><div id="app-content"></div>`;
  root.append(app);
  const call = async (path, options={}) => { const r = await fetch(path,{...options,headers:{'Content-Type':'application/json',Authorization:`Bearer ${localStorage.getItem(key)}`,...(options.headers||{})}}); const b=await r.json(); if(!b.success) throw Error(b.error||'Request failed'); return b.data; };

  // Demo pickup/destination near Maputo, Mozambique — this lightweight portal has no map,
  // so it books against a fixed pair of coordinates the way the delivery demo already does.
  const RIDE_PICKUP = { lat: -25.9692, lng: 32.5732 };
  const RIDE_DEST = { lat: -25.9422, lng: 32.6009 };

  async function renderRides(user) {
    const [rideTypes, estimate, rides] = await Promise.all([
      call('/api/ride-types'),
      call('/api/rides/estimate', { method: 'POST', body: JSON.stringify({ pickup_lat: RIDE_PICKUP.lat, pickup_lng: RIDE_PICKUP.lng, dest_lat: RIDE_DEST.lat, dest_lng: RIDE_DEST.lng }) }),
      call('/api/rides')
    ]);
    const options = rideTypes.map(rt => {
      const est = estimate.estimates.find(e => e.ride_type_id === rt.id);
      return `<option value="${rt.id}">${rt.name} — ${est ? `${est.fare} ${est.currency}, ~${est.estimated_minutes} min` : ''}</option>`;
    }).join('');
    const active = rides.find(r => !['completed', 'cancelled'].includes(r.status));
    return `<h3>Request a ride (Maputo demo route)</h3>
      ${active ? `<p>Active ride ${active.ride_no}: <strong>${active.status.replace(/_/g,' ')}</strong> — ${active.total_fare} ${active.currency}</p>` :
      `<form id="ride-form"><select id="ride-type-id">${options}</select><select id="ride-payment-method"><option value="cash">Cash</option><option value="mpesa">M-Pesa</option><option value="card">Card</option></select><button>Confirm ride</button></form>`}
      <h3>My rides</h3>${rides.map(r => `<p>${r.ride_no} — ${r.status.replace(/_/g,' ')} — ${r.total_fare} ${r.currency}</p>`).join('') || '<p>No rides yet.</p>'}`;
  }

  const enter = async () => { try { const user=await call('/api/auth/me'); document.getElementById('registration-form')?.classList.add('hidden'); document.getElementById('otp')?.classList.add('hidden'); document.getElementById('success')?.classList.add('hidden'); login.classList.add('hidden'); app.classList.remove('hidden'); document.getElementById('app-title').textContent=`${role} — ${user.full_name}`; let content=''; if(role==='rider') { const p=await call('/api/rider/performance'); content=`<p>Approval: ${user.approval_status}. Status: ${user.online_status}. Deliveries: ${p.total_deliveries}. Earnings: ${p.total_earnings} FRW</p><button id="rider-status">${user.online_status==='online'?'Go offline':'Go online'}</button>`; } else if(role==='business') { const d=await call('/api/business/dashboard'); content=`<h3>Business Dashboard</h3><p>Active: ${d.active}. Completed: ${d.completed}. Month spend: ${d.month_spend} FRW</p><h3>Create delivery</h3><form id="delivery-form"><input id="pickup-address" required placeholder="Pickup address"><input id="pickup-phone" required placeholder="Pickup phone"><input id="destination-address" required placeholder="Destination address"><input id="destination-phone" required placeholder="Recipient phone"><button>Create business delivery</button></form>`; } else { const d=await call('/api/deliveries'); const rideSection = await renderRides(user); content=rideSection+`<h3>Request a parcel delivery</h3><form id="delivery-form"><input id="pickup-address" required placeholder="Pickup address"><input id="pickup-phone" required placeholder="Pickup phone"><input id="destination-address" required placeholder="Destination address"><input id="destination-phone" required placeholder="Recipient phone"><button>Create parcel delivery</button></form><h3>My deliveries</h3>`+(d.map(x=>`<p>${x.order_no} — ${x.status}</p>`).join('')||'<p>No deliveries yet.</p>'); } document.getElementById('app-content').innerHTML=content; if(role==='customer'||role==='business'){document.getElementById('delivery-form').onsubmit=async e=>{e.preventDefault();try{const delivery=await call('/api/deliveries',{method:'POST',body:JSON.stringify({service_type:'parcel',pickup_address:document.getElementById('pickup-address').value,pickup_lat:-1.9441,pickup_lng:30.0619,pickup_name:user.full_name,pickup_phone:document.getElementById('pickup-phone').value,dest_address:document.getElementById('destination-address').value,dest_lat:-1.9534,dest_lng:30.0585,dest_name:'Recipient',dest_phone:document.getElementById('destination-phone').value,payment_method:'mobile_money'})}); document.getElementById('app-content').insertAdjacentHTML('afterbegin',`<p>Created ${delivery.delivery.order_no}</p>`); }catch(error){document.getElementById('login-error').textContent=error.message;}};} if(role==='customer'){document.getElementById('ride-form')?.addEventListener('submit', async e=>{e.preventDefault();try{await call('/api/rides',{method:'POST',body:JSON.stringify({pickup_address:'Praça dos Trabalhadores, Maputo',pickup_lat:RIDE_PICKUP.lat,pickup_lng:RIDE_PICKUP.lng,dest_address:'Sommerschield, Maputo',dest_lat:RIDE_DEST.lat,dest_lng:RIDE_DEST.lng,ride_type_id:document.getElementById('ride-type-id').value,payment_method:document.getElementById('ride-payment-method').value})});enter();}catch(error){document.getElementById('login-error').textContent=error.message;}});} if(role==='rider'){document.getElementById('rider-status').onclick=async()=>{try{await call('/api/rider/status',{method:'PUT',body:JSON.stringify({online:user.online_status!=='online'})});enter();}catch(error){document.getElementById('app-content').insertAdjacentHTML('afterbegin',`<p role="alert">${error.message}</p>`);}};} } catch(e) { localStorage.removeItem(key); } };

  let pendingPhone = '';
  document.getElementById('login-form').onsubmit = async e => { e.preventDefault(); try { const phone=document.getElementById('login-phone').value; const password=document.getElementById('login-password').value; const d=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(password?{phone,password}:{phone})}).then(r=>r.json()); if(!d.success) throw Error(d.error); if(d.data.requires_otp) { pendingPhone=phone; document.getElementById('login-otp-form').classList.remove('hidden'); document.getElementById('login-error').textContent=''; return; } if(d.data.user.role!==role) throw Error(`Use the ${d.data.user.role} portal`); localStorage.setItem(key,d.data.token); enter(); } catch(err) { document.getElementById('login-error').textContent=err.message; } };
  document.getElementById('login-otp-form').onsubmit = async e => { e.preventDefault(); try { const d=await fetch('/api/auth/verify-otp',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({phone:pendingPhone,otp:document.getElementById('login-otp').value})}).then(r=>r.json()); if(!d.success) throw Error(d.error); if(d.data.user.role!==role) throw Error(`Use the ${d.data.user.role} portal`); localStorage.setItem(key,d.data.token); enter(); } catch(err) { document.getElementById('login-error').textContent=err.message; } };
  document.getElementById('refresh').onclick=enter; document.getElementById('logout').onclick=()=>{localStorage.removeItem(key); location.reload();};
  if(localStorage.getItem(key)) enter();
})();
