/**
 * MOVO Platform — Complete Backend Server
 * Rwanda's Trusted Digital Logistics Platform
 * Parcel & Document Delivery | Kigali
 */

const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const Database = require('better-sqlite3');
const path = require('path');
const multer = require('multer');
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');
const { loadRuntimeConfig } = require('./src/config/runtime');

// ─── Configuration ───────────────────────────────────────────
const runtime = loadRuntimeConfig({ ...process.env, DB_PATH: process.env.DB_PATH || path.join(__dirname, 'movo.db') });
const PORT = runtime.port;
const IS_PRODUCTION = runtime.production;
const JWT_SECRET = runtime.jwtSecret;
const JWT_EXPIRY = '7d';
const ALLOWED_ORIGINS = runtime.allowedOrigins;
const UPLOAD_DIR = path.join(__dirname, 'uploads');
if (!fs.existsSync(UPLOAD_DIR)) fs.mkdirSync(UPLOAD_DIR, { recursive: true });

const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, UPLOAD_DIR),
  filename: (req, file, cb) => cb(null, `${uuidv4()}-${file.originalname}`)
});
const upload = multer({ storage, limits: { fileSize: 5 * 1024 * 1024 }, fileFilter: (req, file, cb) => {
  const allowed = ['image/jpeg','image/png','image/webp','application/pdf'];
  cb(null, allowed.includes(file.mimetype));
}});

// ─── App Setup ───────────────────────────────────────────────
const app = express();
app.disable('x-powered-by');
const corsOptions = { origin(origin, callback) { callback(null, !origin || ALLOWED_ORIGINS.includes(origin)); } };
const server = http.createServer(app);
const io = new Server(server, { cors: corsOptions });

app.use(cors(corsOptions));
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));
app.use(express.static(path.join(__dirname, 'public')));
app.use('/customer', express.static(path.join(__dirname, 'public', 'customer')));
app.use('/rider', express.static(path.join(__dirname, 'public', 'rider')));
app.use('/business', express.static(path.join(__dirname, 'public', 'business')));
app.use('/admin', express.static(path.join(__dirname, 'public', 'admin')));

// ─── Database Initialization ─────────────────────────────────
const db = new Database(runtime.dbPath);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    phone TEXT UNIQUE NOT NULL,
    email TEXT,
    password TEXT,
    full_name TEXT NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('customer','rider','business','admin')),
    status TEXT DEFAULT 'active',
    language TEXT DEFAULT 'en',
    avatar TEXT,
    otp_code TEXT,
    otp_expires TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS riders (
    user_id TEXT PRIMARY KEY REFERENCES users(id),
    national_id TEXT,
    license_number TEXT,
    motorcycle_plate TEXT,
    motorcycle_make TEXT,
    motorcycle_color TEXT,
    insurance_number TEXT,
    insurance_expiry TEXT,
    emergency_name TEXT,
    emergency_phone TEXT,
    id_front_photo TEXT,
    id_back_photo TEXT,
    license_photo TEXT,
    motorcycle_photo TEXT,
    approval_status TEXT DEFAULT 'pending',
    online_status TEXT DEFAULT 'offline',
    current_lat REAL,
    current_lng REAL,
    last_location_update TEXT,
    total_deliveries INTEGER DEFAULT 0,
    avg_rating REAL DEFAULT 0,
    rating_count INTEGER DEFAULT 0,
    total_earnings REAL DEFAULT 0,
    rejection_count INTEGER DEFAULT 0,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS businesses (
    user_id TEXT PRIMARY KEY REFERENCES users(id),
    company_name TEXT NOT NULL,
    registration_number TEXT,
    tax_id TEXT,
    physical_address TEXT,
    billing_name TEXT,
    billing_phone TEXT,
    billing_email TEXT,
    monthly_limit REAL DEFAULT 500000,
    month_spend REAL DEFAULT 0,
    approval_status TEXT DEFAULT 'pending',
    service_level TEXT DEFAULT 'standard',
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS business_members (
    id TEXT PRIMARY KEY,
    business_id TEXT REFERENCES businesses(user_id),
    user_id TEXT REFERENCES users(id),
    role TEXT DEFAULT 'member',
    spending_limit REAL DEFAULT 0,
    created_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS delivery_zones (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    center_lat REAL NOT NULL,
    center_lng REAL NOT NULL,
    radius_km REAL DEFAULT 3,
    base_price_parcel REAL NOT NULL,
    base_price_document REAL NOT NULL,
    per_km_rate REAL DEFAULT 200,
    sort_order INTEGER DEFAULT 0,
    is_active INTEGER DEFAULT 1,
    created_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS zone_pricing (
    id TEXT PRIMARY KEY,
    origin_zone_id TEXT REFERENCES delivery_zones(id),
    dest_zone_id TEXT REFERENCES delivery_zones(id),
    parcel_price REAL NOT NULL,
    document_price REAL NOT NULL,
    estimated_min INTEGER DEFAULT 30,
    is_active INTEGER DEFAULT 1,
    created_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS deliveries (
    id TEXT PRIMARY KEY,
    order_no TEXT UNIQUE NOT NULL,
    customer_id TEXT REFERENCES users(id),
    business_id TEXT,
    rider_id TEXT REFERENCES users(id),
    service_type TEXT NOT NULL CHECK(service_type IN ('parcel','document')),
    status TEXT NOT NULL DEFAULT 'created',
    pickup_address TEXT NOT NULL,
    pickup_lat REAL,
    pickup_lng REAL,
    pickup_name TEXT NOT NULL,
    pickup_phone TEXT NOT NULL,
    pickup_instructions TEXT,
    pickup_otp TEXT,
    pickup_verified_at TEXT,
    dest_address TEXT NOT NULL,
    dest_lat REAL,
    dest_lng REAL,
    dest_name TEXT NOT NULL,
    dest_phone TEXT NOT NULL,
    dest_instructions TEXT,
    delivery_otp TEXT,
    delivery_verified_at TEXT,
    item_description TEXT,
    item_weight REAL,
    item_category TEXT,
    special_instructions TEXT,
    origin_zone TEXT,
    dest_zone TEXT,
    distance_km REAL,
    customer_price REAL NOT NULL,
    rider_earnings REAL NOT NULL,
    platform_fee REAL NOT NULL,
    total_charge REAL NOT NULL,
    payment_method TEXT DEFAULT 'mobile_money',
    payment_status TEXT DEFAULT 'pending',
    payment_ref TEXT,
    paid_at TEXT,
    preferred_time TEXT,
    est_pickup TEXT,
    est_delivery TEXT,
    assigned_at TEXT,
    picked_up_at TEXT,
    delivered_at TEXT,
    cancelled_at TEXT,
    cancel_reason TEXT,
    cancelled_by TEXT,
    pickup_photo TEXT,
    delivery_photo TEXT,
    recipient_name TEXT,
    delivery_notes TEXT,
    customer_rating INTEGER,
    customer_review TEXT,
    rated_at TEXT,
    business_ref TEXT,
    department TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS delivery_events (
    id TEXT PRIMARY KEY,
    delivery_id TEXT REFERENCES deliveries(id),
    status TEXT NOT NULL,
    lat REAL,
    lng REAL,
    note TEXT,
    created_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS rider_locations (
    id TEXT PRIMARY KEY,
    rider_id TEXT NOT NULL,
    delivery_id TEXT,
    lat REAL NOT NULL,
    lng REAL NOT NULL,
    created_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS delivery_offers (
    id TEXT PRIMARY KEY,
    delivery_id TEXT NOT NULL REFERENCES deliveries(id),
    rider_id TEXT NOT NULL REFERENCES users(id),
    status TEXT NOT NULL DEFAULT 'offered' CHECK(status IN ('offered','accepted','declined','expired')),
    expires_at TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now')),
    responded_at TEXT
  );

  CREATE TABLE IF NOT EXISTS payments (
    id TEXT PRIMARY KEY,
    delivery_id TEXT REFERENCES deliveries(id),
    user_id TEXT NOT NULL,
    amount REAL NOT NULL,
    type TEXT NOT NULL CHECK(type IN ('customer_pay','rider_payout','refund','platform_fee')),
    method TEXT,
    status TEXT DEFAULT 'pending',
    ref TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    processed_at TEXT
  );

  CREATE TABLE IF NOT EXISTS ratings (
    id TEXT PRIMARY KEY,
    delivery_id TEXT REFERENCES deliveries(id),
    rater_id TEXT NOT NULL,
    rated_id TEXT NOT NULL,
    score INTEGER NOT NULL CHECK(score BETWEEN 1 AND 5),
    review TEXT,
    created_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS tickets (
    id TEXT PRIMARY KEY,
    delivery_id TEXT,
    reporter_id TEXT NOT NULL,
    category TEXT NOT NULL,
    subject TEXT NOT NULL,
    description TEXT NOT NULL,
    priority TEXT DEFAULT 'medium',
    status TEXT DEFAULT 'open',
    assigned_to TEXT,
    resolution TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    resolved_at TEXT
  );

  CREATE TABLE IF NOT EXISTS saved_addresses (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    label TEXT NOT NULL,
    address TEXT NOT NULL,
    lat REAL,
    lng REAL,
    contact_name TEXT,
    contact_phone TEXT,
    is_default INTEGER DEFAULT 0,
    created_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS notifications (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    type TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    data TEXT,
    is_read INTEGER DEFAULT 0,
    created_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS audit_log (
    id TEXT PRIMARY KEY,
    user_id TEXT,
    action TEXT NOT NULL,
    entity TEXT,
    entity_id TEXT,
    details TEXT,
    ip TEXT,
    created_at TEXT DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS pricing_config (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT DEFAULT (datetime('now'))
  );

  CREATE INDEX IF NOT EXISTS idx_deliveries_customer ON deliveries(customer_id);
  CREATE INDEX IF NOT EXISTS idx_deliveries_rider ON deliveries(rider_id);
  CREATE INDEX IF NOT EXISTS idx_deliveries_status ON deliveries(status);
  CREATE INDEX IF NOT EXISTS idx_deliveries_business ON deliveries(business_id);
  CREATE INDEX IF NOT EXISTS idx_rider_locations_rider ON rider_locations(rider_id);
  CREATE INDEX IF NOT EXISTS idx_delivery_offers_rider ON delivery_offers(rider_id,status,expires_at);
  CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id);
  CREATE INDEX IF NOT EXISTS idx_tickets_reporter ON tickets(reporter_id);
  CREATE INDEX IF NOT EXISTS idx_tickets_status ON tickets(status);
`);

// ─── Seed Data ───────────────────────────────────────────────
function seedData() {
  const adminCount = db.prepare('SELECT COUNT(*) as c FROM users WHERE role=?').get('admin');
  if (adminCount.c === 0) {
    const hash = bcrypt.hashSync('Admin@2026', 10);
    db.prepare('INSERT INTO users (id,phone,email,password,full_name,role,status) VALUES (?,?,?,?,?,?,?)')
      .run(uuidv4(), '+250780000000', 'admin@movo.rw', hash, 'MOVO Administrator', 'admin', 'active');
  }
  const zoneCount = db.prepare('SELECT COUNT(*) as c FROM delivery_zones').get();
  if (zoneCount.c === 0) {
    const zones = [
      ['z1','City Center / Kacyiru',-1.9441,30.0619,3,1500,1000,200,0],
      ['z2','Nyarugenge / Downtown',-1.9534,30.0585,2.5,1500,1000,200,1],
      ['z3','Remera / Gisozi',-1.9367,30.0867,3,1800,1200,220,2],
      ['z4','Kicukiro',-1.9783,30.1125,3.5,2000,1400,220,3],
      ['z5','Gikondo / Nyarugenge Industrial',-1.9667,30.0500,2.5,1600,1100,200,4],
      ['z6','Nyabugogo',-1.9592,30.0417,2,1400,900,180,5],
      ['z7','Kabeza',-1.9633,30.0750,2.5,1700,1200,210,6],
      ['z8','Kanombe',-1.9583,30.1400,4,2200,1600,250,7],
      ['z9','Masaka / Kicukiro Outer',-1.9950,30.1250,4,2500,1800,260,8],
      ['z10','Batsinda / Rusororo',-1.9100,30.1100,5,3000,2200,280,9]
    ];
    const insZone = db.prepare('INSERT INTO delivery_zones (id,name,center_lat,center_lng,radius_km,base_price_parcel,base_price_document,per_km_rate,sort_order) VALUES (?,?,?,?,?,?,?,?,?)');
    zones.forEach(z => insZone.run(...z));

    // Seed zone-to-zone pricing
    const allZones = db.prepare('SELECT id FROM delivery_zones ORDER BY sort_order').all();
    const insPricing = db.prepare('INSERT INTO zone_pricing (id,origin_zone_id,dest_zone_id,parcel_price,document_price,estimated_min) VALUES (?,?,?,?,?,?)');
    for (const oz of allZones) {
      for (const dz of allZones) {
        const o = db.prepare('SELECT base_price_parcel,base_price_document,per_km_rate,center_lat,center_lng FROM delivery_zones WHERE id=?').get(oz.id);
        const d = db.prepare('SELECT center_lat,center_lng FROM delivery_zones WHERE id=?').get(dz.id);
        const dist = haversine(o.center_lat, o.center_lng, d.center_lat, d.center_lng);
        const pPrice = Math.round(o.base_price_parcel + dist * o.per_km_rate);
        const dPrice = Math.round(o.base_price_document + dist * o.per_km_rate);
        const estMin = Math.round(10 + dist * 3);
        insPricing.run(uuidv4(), oz.id, dz.id, pPrice, dPrice, estMin);
      }
    }
  }
  const cfgCount = db.prepare('SELECT COUNT(*) as c FROM pricing_config').get();
  if (cfgCount.c === 0) {
    const configs = [
      ['platform_fee_percent','20'],['min_ride_price','800'],['cancel_fee_customer',500],
      ['cancel_fee_rider',0],['waiting_fee_per_min',100],['max_waiting_free_min',5],
      ['rider_accept_timeout_sec',30],['rider_search_radius_km',5],['rider_search_expand_km',2],
      ['currency','RWF'],['currency_symbol','FRW']
    ];
    const insCfg = db.prepare('INSERT OR REPLACE INTO pricing_config (key,value) VALUES (?,?)');
    configs.forEach(c => insCfg.run(c[0], String(c[1])));
  }
  if (process.env.LIVE_MAP_DEMO_MODE === 'true') seedLiveMapDemoData();
}

function seedLiveMapDemoData() {
  const customerId = 'demo-map-customer';
  db.prepare('INSERT OR IGNORE INTO users (id,phone,email,full_name,role,status) VALUES (?,?,?,?,?,?)')
    .run(customerId, '+250730000000', 'demo-map@movo.rw', 'Demo Map Customer', 'customer', 'active');

  const riders = [
    ['demo-map-rider-1', '+250730000001', 'Demo Rider — Kacyiru', 'RAB 001D', -1.9448, 30.0612],
    ['demo-map-rider-2', '+250730000002', 'Demo Rider — Remera', 'RAB 002D', -1.9488, 30.0825],
    ['demo-map-rider-3', '+250730000003', 'Demo Rider — Downtown', 'RAB 003D', -1.9572, 30.0571]
  ];
  const insertUser = db.prepare('INSERT OR IGNORE INTO users (id,phone,email,full_name,role,status) VALUES (?,?,?,?,?,?)');
  const insertRider = db.prepare("INSERT OR IGNORE INTO riders (user_id,motorcycle_plate,approval_status,online_status,current_lat,current_lng,last_location_update) VALUES (?,?, 'approved','online',?,?,datetime('now'))");
  const updateRider = db.prepare("UPDATE riders SET approval_status='approved',online_status='online',current_lat=?,current_lng=?,last_location_update=datetime('now') WHERE user_id=?");
  riders.forEach(([id, phone, name, plate, lat, lng]) => {
    insertUser.run(id, phone, `${id}@movo.rw`, name, 'rider', 'active');
    insertRider.run(id, plate, lat, lng);
    updateRider.run(lat, lng, id);
  });

  const deliveries = [
    ['demo-map-delivery-1', 'DEMO-MAP-001', 'demo-map-rider-1', 'going_pickup', 'Kacyiru Convention Centre', -1.9441, 30.0619, 'Kigali Heights', -1.9367, 30.0867],
    ['demo-map-delivery-2', 'DEMO-MAP-002', 'demo-map-rider-2', 'in_transit', 'Remera Bus Park', -1.9494, 30.0910, 'Kigali International Airport', -1.9680, 30.1395],
    ['demo-map-delivery-3', 'DEMO-MAP-003', 'demo-map-rider-3', 'arrived_dest', 'Nyamirambo Stadium', -1.9755, 30.0446, 'Kigali City Tower', -1.9494, 30.0588]
  ];
  const insertDelivery = db.prepare(`INSERT OR IGNORE INTO deliveries (id,order_no,customer_id,rider_id,service_type,status,pickup_address,pickup_lat,pickup_lng,pickup_name,pickup_phone,dest_address,dest_lat,dest_lng,dest_name,dest_phone,customer_price,rider_earnings,platform_fee,total_charge,payment_method,assigned_at)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,datetime('now'))`);
  const updateDelivery = db.prepare("UPDATE deliveries SET rider_id=?,status=?,updated_at=datetime('now') WHERE id=?");
  deliveries.forEach(([id, orderNo, riderId, status, pickupAddress, pickupLat, pickupLng, destAddress, destLat, destLng]) => {
    insertDelivery.run(id, orderNo, customerId, riderId, 'parcel', status, pickupAddress, pickupLat, pickupLng, 'Demo Sender', '+250730000010', destAddress, destLat, destLng, 'Demo Recipient', '+250730000011', 1500, 1200, 300, 1500, 'mobile_money');
    updateDelivery.run(riderId, status, id);
  });
}

// ─── Utility Functions ───────────────────────────────────────
function haversine(lat1, lon1, lat2, lon2) {
  const R = 6371;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat/2)**2 + Math.cos(lat1*Math.PI/180)*Math.cos(lat2*Math.PI/180)*Math.sin(dLon/2)**2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
}

function genOTP() { return String(Math.floor(100000 + Math.random() * 900000)); }
function genOrderNo() { return 'MV' + Date.now().toString(36).toUpperCase() + Math.random().toString(36).substr(2,4).toUpperCase(); }
function resOK(res, data, status=200) { res.status(status).json({ success: true, data }); }
function resErr(res, msg, status=400) { res.status(status).json({ success: false, error: msg }); }
function getConfig(key, def=null) { const r = db.prepare('SELECT value FROM pricing_config WHERE key=?').get(key); return r ? r.value : def; }

function findZone(lat, lng) {
  const zones = db.prepare('SELECT * FROM delivery_zones WHERE is_active=1 ORDER BY sort_order').all();
  for (const z of zones) {
    const d = haversine(lat, lng, z.center_lat, z.center_lng);
    if (d <= z.radius_km) return z;
  }
  // Fallback: nearest zone
  let nearest = null, minD = Infinity;
  for (const z of zones) {
    const d = haversine(lat, lng, z.center_lat, z.center_lng);
    if (d < minD) { minD = d; nearest = z; }
  }
  return nearest;
}

function calcPrice(pickupLat, pickupLng, destLat, destLng, serviceType) {
  const oz = findZone(pickupLat, pickupLng);
  const dz = findZone(destLat, destLng);
  if (!oz || !dz) return null;
  const pricing = db.prepare('SELECT * FROM zone_pricing WHERE origin_zone_id=? AND dest_zone_id=? AND is_active=1')
    .get(oz.id, dz.id);
  if (!pricing) return null;
  const feePct = parseFloat(getConfig('platform_fee_percent', '20'));
  const base = serviceType === 'parcel' ? pricing.parcel_price : pricing.document_price;
  const minPrice = parseInt(getConfig('min_ride_price', '800'));
  const customerPrice = Math.max(base, minPrice);
  const platformFee = Math.round(customerPrice * feePct / 100);
  const riderEarnings = customerPrice - platformFee;
  return {
    originZone: oz, destZone: dz,
    distance_km: Math.round(haversine(pickupLat, pickupLng, destLat, destLng) * 10) / 10,
    customerPrice, riderEarnings, platformFee,
    totalCharge: customerPrice,
    estimatedMinutes: pricing.estimated_min,
    serviceType
  };
}

function addEvent(deliveryId, status, lat, lng, note) {
  db.prepare('INSERT INTO delivery_events (id,delivery_id,status,lat,lng,note) VALUES (?,?,?,?,?,?)')
    .run(uuidv4(), deliveryId, status, lat, lng, note);
}

function updateDeliveryStatus(id, status, extra={}) {
  const sets = ['status=?','updated_at=datetime(\'now\')'];
  const vals = [status];
  const deliveryFields = new Set([
    'rider_id', 'assigned_at', 'pickup_verified_at', 'pickup_photo', 'picked_up_at',
    'delivery_verified_at', 'delivery_photo', 'recipient_name', 'delivery_notes',
    'delivered_at', 'payment_status', 'paid_at', 'cancelled_at', 'cancel_reason', 'cancelled_by'
  ]);
  for (const [k,v] of Object.entries(extra)) {
    if (!deliveryFields.has(k)) continue;
    sets.push(`${k}=?`);
    vals.push(v);
  }
  vals.push(id);
  db.prepare(`UPDATE deliveries SET ${sets.join(',')} WHERE id=?`).run(...vals);
  addEvent(id, status, extra.lat, extra.lng, extra.note);
}

function notifyUser(userId, type, title, body, data=null) {
  db.prepare('INSERT INTO notifications (id,user_id,type,title,body,data) VALUES (?,?,?,?,?,?)')
    .run(uuidv4(), userId, type, title, body, data ? JSON.stringify(data) : null);
  const sock = userSockets[userId];
  if (sock) sock.emit('notification', { type, title, body, data });
}

function audit(userId, action, entity, entityId, details) {
  db.prepare('INSERT INTO audit_log (id,user_id,action,entity,entity_id,details) VALUES (?,?,?,?,?,?)')
    .run(uuidv4(), userId, action, entity, entityId, details ? JSON.stringify(details) : null);
}

// ─── Auth Middleware ─────────────────────────────────────────
function auth(req, res, next) {
  const hdr = req.headers.authorization;
  if (!hdr || !hdr.startsWith('Bearer ')) return resErr(res, 'Authentication required', 401);
  try {
    const token = hdr.split(' ')[1];
    const payload = jwt.verify(token, JWT_SECRET);
    const user = db.prepare('SELECT id,phone,full_name,role,status FROM users WHERE id=?').get(payload.id);
    if (!user || user.status !== 'active') return resErr(res, 'Account inactive or not found', 401);
    req.user = user;
    next();
  } catch(e) { resErr(res, 'Invalid or expired token', 401); }
}

function roleAuth(...roles) {
  return (req, res, next) => {
    if (!roles.includes(req.user.role)) return resErr(res, 'Insufficient permissions', 403);
    next();
  };
}

// ─── AUTH ROUTES ─────────────────────────────────────────────
app.post('/api/auth/register', (req, res) => {
  try {
    const { phone, full_name, email, password, role, company_name, tax_id, national_id, license_number, motorcycle_plate } = req.body;
    if (!phone || !full_name || !role) return resErr(res, 'Phone, name, and role are required');
    if (!/^\+?[0-9]{8,15}$/.test(phone)) return resErr(res, 'Enter a valid phone number');
    if (!['customer','rider','business'].includes(role)) return resErr(res, 'Invalid role');
    if (password && password.length < 8) return resErr(res, 'Password must be at least 8 characters');
    if (role === 'business' && !company_name?.trim()) return resErr(res, 'Company name is required');
    if (role === 'rider' && (!national_id?.trim() || !license_number?.trim() || !motorcycle_plate?.trim())) return resErr(res, 'National ID, license number, and motorcycle plate are required');
    if (db.prepare('SELECT id FROM users WHERE phone=?').get(phone)) return resErr(res, 'Phone number already registered');
    const hash = password ? bcrypt.hashSync(password, 10) : null;
    const otp = genOTP();
    const otpHash = bcrypt.hashSync(otp, 10);
    const id = uuidv4();
    const create = db.transaction(() => {
      db.prepare("INSERT INTO users (id,phone,email,password,full_name,role,otp_code,otp_expires) VALUES (?,?,?,?,?,?,?,datetime('now','+10 minutes'))")
        .run(id, phone, email || null, hash, full_name.trim(), role, otpHash);
      if (role === 'rider') db.prepare('INSERT INTO riders (user_id,national_id,license_number,motorcycle_plate) VALUES (?,?,?,?)').run(id, national_id.trim(), license_number.trim(), motorcycle_plate.trim().toUpperCase());
      if (role === 'business') db.prepare('INSERT INTO businesses (user_id,company_name,tax_id) VALUES (?,?,?)').run(id, company_name.trim(), tax_id?.trim() || null);
    });
    create();
    const response = { id, phone, message: runtime.otpTestMode ? 'OTP generated for test mode' : 'OTP sent to your phone' };
    if (runtime.otpTestMode) response.otp = otp;
    resOK(res, response);
  } catch(e) { resErr(res, e.message); }
});

app.post('/api/auth/verify-otp', (req, res) => {
  try {
    const { phone, otp } = req.body;
    if (!phone || !otp) return resErr(res, 'Phone and OTP required');
    const user = db.prepare('SELECT * FROM users WHERE phone=?').get(phone);
    if (!user) return resErr(res, 'User not found');
    if (!/^[0-9]{6}$/.test(otp)) return resErr(res, 'OTP must be six digits');
    if (!runtime.otpTestMode && !bcrypt.compareSync(otp, user.otp_code || '')) return resErr(res, 'Invalid OTP');
    if (!runtime.otpTestMode && new Date(user.otp_expires) < new Date()) return resErr(res, 'OTP expired');
    db.prepare('UPDATE users SET otp_code=NULL,otp_expires=NULL,status=\'active\' WHERE id=?').run(user.id);
    const token = jwt.sign({ id: user.id, role: user.role }, JWT_SECRET, { expiresIn: JWT_EXPIRY });
    const profile = { id: user.id, phone: user.phone, full_name: user.full_name, role: user.role, email: user.email };
    if (user.role === 'rider') Object.assign(profile, db.prepare('SELECT * FROM riders WHERE user_id=?').get(user.id));
    if (user.role === 'business') Object.assign(profile, db.prepare('SELECT * FROM businesses WHERE user_id=?').get(user.id));
    resOK(res, { token, user: profile });
  } catch(e) { resErr(res, e.message); }
});

app.post('/api/auth/login', (req, res) => {
  try {
    const { phone, password } = req.body;
    if (!phone) return resErr(res, 'Phone number required');
    const user = db.prepare('SELECT * FROM users WHERE phone=?').get(phone);
    if (!user) return resErr(res, 'Account not found');
    if (password && user.password) {
      if (!bcrypt.compareSync(password, user.password)) return resErr(res, 'Invalid password');
    } else {
      const otp = genOTP();
      const otpHash = bcrypt.hashSync(otp, 10);
      db.prepare('UPDATE users SET otp_code=?,otp_expires=datetime(\'now\',\'+10 minutes\') WHERE id=?').run(otpHash, user.id);
      const response = { requires_otp: true, phone, message: runtime.otpTestMode ? 'OTP generated for test mode' : 'OTP sent to your phone' };
      if (runtime.otpTestMode) response.otp = otp;
      return resOK(res, response);
    }
    if (user.status !== 'active') return resErr(res, 'Account is ' + user.status);
    const token = jwt.sign({ id: user.id, role: user.role }, JWT_SECRET, { expiresIn: JWT_EXPIRY });
    const profile = { id: user.id, phone: user.phone, full_name: user.full_name, role: user.role, email: user.email };
    if (user.role === 'rider') Object.assign(profile, db.prepare('SELECT * FROM riders WHERE user_id=?').get(user.id));
    if (user.role === 'business') Object.assign(profile, db.prepare('SELECT * FROM businesses WHERE user_id=?').get(user.id));
    resOK(res, { token, user: profile });
  } catch(e) { resErr(res, e.message); }
});

app.get('/api/auth/me', auth, (req, res) => {
  const user = db.prepare('SELECT id,phone,email,full_name,role,status,avatar,created_at FROM users WHERE id=?').get(req.user.id);
  if (req.user.role === 'rider') Object.assign(user, db.prepare('SELECT * FROM riders WHERE user_id=?').get(req.user.id));
  if (req.user.role === 'business') Object.assign(user, db.prepare('SELECT * FROM businesses WHERE user_id=?').get(req.user.id));
  resOK(res, user);
});

// ─── USER / PROFILE ROUTES ───────────────────────────────────
app.put('/api/profile', auth, (req, res) => {
  try {
    const { full_name, email, avatar, language } = req.body;
    const updates = []; const vals = [];
    if (full_name) { updates.push('full_name=?'); vals.push(full_name); }
    if (email !== undefined) { updates.push('email=?'); vals.push(email); }
    if (avatar) { updates.push('avatar=?'); vals.push(avatar); }
    if (language) { updates.push('language=?'); vals.push(language); }
    if (updates.length === 0) return resErr(res, 'Nothing to update');
    updates.push("updated_at=datetime('now')");
    vals.push(req.user.id);
    db.prepare(`UPDATE users SET ${updates.join(',')} WHERE id=?`).run(...vals);
    resOK(res, { message: 'Profile updated' });
  } catch(e) { resErr(res, e.message); }
});

app.get('/api/notifications', auth, (req, res) => {
  const notifs = db.prepare('SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT 50').all(req.user.id);
  resOK(res, notifs);
});

app.put('/api/notifications/:id/read', auth, (req, res) => {
  db.prepare('UPDATE notifications SET is_read=1 WHERE id=? AND user_id=?').run(req.params.id, req.user.id);
  resOK(res, { message: 'Marked read' });
});

// ─── SAVED ADDRESSES ─────────────────────────────────────────
app.get('/api/addresses', auth, (req, res) => {
  resOK(res, db.prepare('SELECT * FROM saved_addresses WHERE user_id=? ORDER BY is_default DESC, created_at DESC').all(req.user.id));
});

app.post('/api/addresses', auth, (req, res) => {
  try {
    const { label, address, lat, lng, contact_name, contact_phone, is_default } = req.body;
    if (!label || !address) return resErr(res, 'Label and address required');
    const id = uuidv4();
    db.prepare('INSERT INTO saved_addresses (id,user_id,label,address,lat,lng,contact_name,contact_phone,is_default) VALUES (?,?,?,?,?,?,?,?,?)')
      .run(id, req.user.id, label, address, lat||null, lng||null, contact_name||null, contact_phone||null, is_default?1:0);
    if (is_default) db.prepare('UPDATE saved_addresses SET is_default=0 WHERE user_id=? AND id!=?').run(req.user.id, id);
    resOK(res, { id, message: 'Address saved' });
  } catch(e) { resErr(res, e.message); }
});

app.delete('/api/addresses/:id', auth, (req, res) => {
  db.prepare('DELETE FROM saved_addresses WHERE id=? AND user_id=?').run(req.params.id, req.user.id);
  resOK(res, { message: 'Address deleted' });
});

// ─── RIDER ROUTES ────────────────────────────────────────────
app.put('/api/rider/profile', auth, roleAuth('rider'), (req, res) => {
  try {
    const fields = ['national_id','license_number','motorcycle_plate','motorcycle_make','motorcycle_type','motorcycle_color',
      'insurance_number','insurance_expiry','emergency_name','emergency_phone'];
    const updates = []; const vals = [];
    for (const f of fields) {
      if (req.body[f] !== undefined) { updates.push(`${f}=?`); vals.push(req.body[f]); }
    }
    if (updates.length === 0) return resErr(res, 'Nothing to update');
    updates.push("updated_at=datetime('now')");
    vals.push(req.user.id);
    db.prepare(`UPDATE riders SET ${updates.join(',')} WHERE user_id=?`).run(...vals);
    resOK(res, { message: 'Rider profile updated' });
  } catch(e) { resErr(res, e.message); }
});

try { db.exec("ALTER TABLE riders ADD COLUMN profile_photo TEXT"); } catch (_) {}
try { db.exec("ALTER TABLE riders ADD COLUMN motorcycle_type TEXT"); } catch (_) {}
app.post('/api/rider/documents', auth, roleAuth('rider'), upload.fields([
  { name: 'profile', maxCount: 1 },
  { name: 'id_front', maxCount: 1 }, { name: 'id_back', maxCount: 1 },
  { name: 'license', maxCount: 1 }, { name: 'motorcycle', maxCount: 1 }
]), (req, res) => {
  try {
    const updates = []; const vals = [];
    if (req.files['profile']) { updates.push('profile_photo=?'); vals.push(req.files['profile'][0].filename); }
    if (req.files['id_front']) { updates.push('id_front_photo=?'); vals.push(req.files['id_front'][0].filename); }
    if (req.files['id_back']) { updates.push('id_back_photo=?'); vals.push(req.files['id_back'][0].filename); }
    if (req.files['license']) { updates.push('license_photo=?'); vals.push(req.files['license'][0].filename); }
    if (req.files['motorcycle']) { updates.push('motorcycle_photo=?'); vals.push(req.files['motorcycle'][0].filename); }
    if (updates.length === 0) return resErr(res, 'No documents uploaded');
    updates.push("updated_at=datetime('now')");
    vals.push(req.user.id);
    db.prepare(`UPDATE riders SET ${updates.join(',')} WHERE user_id=?`).run(...vals);
    resOK(res, { message: 'Documents uploaded' });
  } catch(e) { resErr(res, e.message); }
});

app.get('/api/rider/documents/:riderId/:kind', auth, (req, res) => {
  const fields = { profile: 'profile_photo', id_front: 'id_front_photo', id_back: 'id_back_photo', license: 'license_photo', motorcycle: 'motorcycle_photo' };
  const field = fields[req.params.kind];
  if (!field) return resErr(res, 'Unknown document type', 404);
  if (req.user.role !== 'admin' && req.user.id !== req.params.riderId) return resErr(res, 'Document access denied', 403);
  const rider = db.prepare(`SELECT ${field} AS filename FROM riders WHERE user_id=?`).get(req.params.riderId);
  if (!rider?.filename) return resErr(res, 'Document not found', 404);
  const filePath = path.join(UPLOAD_DIR, path.basename(rider.filename));
  if (!fs.existsSync(filePath)) return resErr(res, 'Document not found', 404);
  res.sendFile(filePath);
});

app.put('/api/rider/status', auth, roleAuth('rider'), (req, res) => {
  const { online } = req.body;
  const rider = db.prepare('SELECT approval_status FROM riders WHERE user_id=?').get(req.user.id);
  if (rider.approval_status !== 'approved') return resErr(res, 'Rider not yet approved');
  const status = online ? 'online' : 'offline';
  db.prepare('UPDATE riders SET online_status=?,updated_at=datetime(\'now\') WHERE user_id=?').run(status, req.user.id);
  resOK(res, { online_status: status });
});

app.put('/api/rider/location', auth, roleAuth('rider'), (req, res) => {
  const { lat, lng } = req.body;
  if (!Number.isFinite(Number(lat)) || !Number.isFinite(Number(lng)) || Math.abs(Number(lat)) > 90 || Math.abs(Number(lng)) > 180) return resErr(res, 'Valid latitude and longitude required');
  const rider = db.prepare('SELECT approval_status,online_status FROM riders WHERE user_id=?').get(req.user.id);
  if (!rider || rider.approval_status !== 'approved' || !['online','busy'].includes(rider.online_status)) return resErr(res, 'Approved online rider required', 403);
  db.prepare('UPDATE riders SET current_lat=?,current_lng=?,last_location_update=datetime(\'now\') WHERE user_id=?')
    .run(lat, lng, req.user.id);
  db.prepare('INSERT INTO rider_locations (id,rider_id,lat,lng) VALUES (?,?,?,?)').run(uuidv4(), req.user.id, lat, lng);
  resOK(res, { message: 'Location updated' });
});

app.get('/api/mobile/v1/rider/home', auth, roleAuth('rider'), (req, res) => {
  db.prepare("UPDATE delivery_offers SET status='expired',responded_at=datetime('now') WHERE rider_id=? AND status='offered' AND expires_at<=datetime('now')").run(req.user.id);
  const rider = db.prepare("SELECT r.approval_status,r.online_status,r.last_location_update,r.total_deliveries,r.total_earnings,r.avg_rating,r.rating_count,r.profile_photo,r.motorcycle_plate,r.motorcycle_make,r.motorcycle_type,r.motorcycle_color,u.full_name FROM riders r JOIN users u ON u.id=r.user_id WHERE r.user_id=?").get(req.user.id);
  const activeDelivery = db.prepare("SELECT * FROM deliveries WHERE rider_id=? AND status IN ('assigned','going_pickup','arrived_pickup','picked_up','in_transit','arrived_dest') ORDER BY updated_at DESC LIMIT 1").get(req.user.id) || null;
  const offers = db.prepare(`SELECT o.id as offer_id,o.expires_at,d.id,d.order_no,d.service_type,d.pickup_address,d.pickup_lat,d.pickup_lng,d.pickup_name,d.pickup_phone,d.dest_address,d.dest_lat,d.dest_lng,d.dest_name,d.dest_phone,d.rider_earnings,d.distance_km
    FROM delivery_offers o JOIN deliveries d ON d.id=o.delivery_id WHERE o.rider_id=? AND o.status='offered' AND o.expires_at>datetime('now') ORDER BY o.expires_at`).all(req.user.id);
  resOK(res, { ...rider, profile_photo_url: rider.profile_photo ? `/api/rider/documents/${req.user.id}/profile` : null, profile_photo: undefined, activeDelivery, offers, serverTime: new Date().toISOString() });
});

app.put('/api/mobile/v1/rider/offers/:offerId/decline', auth, roleAuth('rider'), (req, res) => {
  const changed = db.prepare("UPDATE delivery_offers SET status='declined',responded_at=datetime('now') WHERE id=? AND rider_id=? AND status='offered' AND expires_at>datetime('now')").run(req.params.offerId, req.user.id);
  if (!changed.changes) return resErr(res, 'Offer is unavailable', 409);
  resOK(res, { message: 'Offer declined' });
});

app.get('/api/rider/earnings', auth, roleAuth('rider'), (req, res) => {
  const { period } = req.query;
  let dateFilter = '';
  if (period === 'today') dateFilter = "AND date(d.delivered_at) = date('now')";
  else if (period === 'week') dateFilter = "AND d.delivered_at >= datetime('now','-7 days')";
  else if (period === 'month') dateFilter = "AND d.delivered_at >= datetime('now','-30 days')";

  const stats = db.prepare(`SELECT COUNT(*) as count, COALESCE(SUM(rider_earnings),0) as total_earnings,
    COALESCE(SUM(platform_fee),0) as total_fees FROM deliveries d WHERE d.rider_id=? AND d.status='delivered' ${dateFilter}`)
    .get(req.user.id);
  const recent = db.prepare(`SELECT d.id,d.order_no,d.service_type,d.rider_earnings,d.delivered_at,d.pickup_address,d.dest_address
    FROM deliveries d WHERE d.rider_id=? AND d.status='delivered' ${dateFilter} ORDER BY d.delivered_at DESC LIMIT 20`).all(req.user.id);
  resOK(res, { ...stats, recent });
});

app.get('/api/rider/performance', auth, roleAuth('rider'), (req, res) => {
  const rider = db.prepare('SELECT * FROM riders WHERE user_id=?').get(req.user.id);
  const total = db.prepare("SELECT COUNT(*) as c FROM deliveries WHERE rider_id=? AND status='delivered'").get(req.user.id).c;
  const accepted = db.prepare("SELECT COUNT(*) as c FROM deliveries WHERE rider_id=? AND status IN ('delivered','picked_up','in_transit','arrived_dest')").get(req.user.id).c;
  const cancelled = db.prepare("SELECT COUNT(*) as c FROM deliveries WHERE rider_id=? AND status='cancelled' AND cancelled_by='rider'").get(req.user.id).c;
  const assigned = db.prepare("SELECT COUNT(*) as c FROM deliveries WHERE rider_id=?").get(req.user.id).c;
  resOK(res, {
    total_deliveries: total,
    acceptance_rate: assigned > 0 ? Math.round(accepted/assigned*100) : 0,
    cancellation_rate: assigned > 0 ? Math.round(cancelled/assigned*100) : 0,
    avg_rating: rider.avg_rating || 0,
    rating_count: rider.rating_count || 0,
    total_earnings: rider.total_earnings || 0
  });
});

app.get('/api/rider/active-delivery', auth, roleAuth('rider'), (req, res) => {
  const d = db.prepare("SELECT * FROM deliveries WHERE rider_id=? AND status IN ('assigned','going_pickup','arrived_pickup','picked_up','in_transit','arrived_dest') ORDER BY created_at DESC LIMIT 1")
    .get(req.user.id);
  resOK(res, d || null);
});

app.post('/api/rider/deliveries/:id/proof', auth, roleAuth('rider'), upload.single('proof'), (req, res) => {
  const d = db.prepare("SELECT status FROM deliveries WHERE id=? AND rider_id=?").get(req.params.id, req.user.id);
  if (!d || !['arrived_pickup','arrived_dest'].includes(d.status)) return resErr(res, 'Proof is not accepted at this delivery stage', 409);
  if (!req.file) return resErr(res, 'A JPEG, PNG, or WebP proof image is required');
  const kind = req.body.kind === 'delivery' ? 'delivery' : 'pickup';
  const field = kind === 'delivery' ? 'delivery_photo' : 'pickup_photo';
  db.prepare(`UPDATE deliveries SET ${field}=?,updated_at=datetime('now') WHERE id=?`).run(req.file.filename, req.params.id);
  audit(req.user.id, 'rider_proof_uploaded', 'delivery', req.params.id, JSON.stringify({ kind }));
  resOK(res, { kind, filename: req.file.filename });
});

// ─── BUSINESS ROUTES ─────────────────────────────────────────
app.put('/api/business/profile', auth, roleAuth('business'), (req, res) => {
  try {
    const fields = ['company_name','registration_number','tax_id','physical_address','billing_name','billing_phone','billing_email'];
    const updates = []; const vals = [];
    for (const f of fields) {
      if (req.body[f] !== undefined) { updates.push(`${f}=?`); vals.push(req.body[f]); }
    }
    if (updates.length === 0) return resErr(res, 'Nothing to update');
    updates.push("updated_at=datetime('now')");
    vals.push(req.user.id);
    db.prepare(`UPDATE businesses SET ${updates.join(',')} WHERE user_id=?`).run(...vals);
    resOK(res, { message: 'Business profile updated' });
  } catch(e) { resErr(res, e.message); }
});

app.get('/api/business/dashboard', auth, roleAuth('business'), (req, res) => {
  const bid = req.user.id;
  const active = db.prepare("SELECT COUNT(*) as c FROM deliveries WHERE business_id=? AND status NOT IN ('delivered','cancelled','failed')").get(bid).c;
  const completed = db.prepare("SELECT COUNT(*) as c FROM deliveries WHERE business_id=? AND status='delivered'").get(bid).c;
  const failed = db.prepare("SELECT COUNT(*) as c FROM deliveries WHERE business_id=? AND status IN ('cancelled','failed')").get(bid).c;
  const spend = db.prepare("SELECT COALESCE(SUM(total_charge),0) as t FROM deliveries WHERE business_id=? AND status='delivered' AND date(created_at)>=date('now','start of month')").get(bid).t;
  const recent = db.prepare('SELECT * FROM deliveries WHERE business_id=? ORDER BY created_at DESC LIMIT 10').all(bid);
  resOK(res, { active, completed, failed, month_spend: spend, recent });
});

app.get('/api/business/members', auth, roleAuth('business'), (req, res) => {
  const members = db.prepare(`SELECT bm.*,u.full_name,u.phone,u.status FROM business_members bm
    JOIN users u ON bm.user_id=u.id WHERE bm.business_id=?`).all(req.user.id);
  resOK(res, members);
});

app.post('/api/business/members', auth, roleAuth('business'), (req, res) => {
  try {
    const { phone, full_name, role, spending_limit } = req.body;
    if (!phone || !full_name) return resErr(res, 'Phone and name required');
    let user = db.prepare('SELECT id FROM users WHERE phone=?').get(phone);
    if (!user) {
      user = { id: uuidv4() };
      db.prepare('INSERT INTO users (id,phone,full_name,role,status) VALUES (?,?,?,?,?)').run(user.id, phone, full_name, 'customer', 'active');
    }
    const existing = db.prepare('SELECT id FROM business_members WHERE business_id=? AND user_id=?').get(req.user.id, user.id);
    if (existing) return resErr(res, 'Already a member');
    db.prepare('INSERT INTO business_members (id,business_id,user_id,role,spending_limit) VALUES (?,?,?,?,?)')
      .run(uuidv4(), req.user.id, user.id, role||'member', spending_limit||0);
    resOK(res, { message: 'Member added' });
  } catch(e) { resErr(res, e.message); }
});

app.get('/api/business/invoices', auth, roleAuth('business'), (req, res) => {
  const invoices = db.prepare(`SELECT date(created_at) as month, COUNT(*) as deliveries, SUM(total_charge) as total
    FROM deliveries WHERE business_id=? AND status='delivered' GROUP BY date(created_at, 'start of month') ORDER BY month DESC LIMIT 12`).all(req.user.id);
  resOK(res, invoices);
});

// ─── DELIVERY ROUTES ─────────────────────────────────────────
app.post('/api/deliveries/price', auth, (req, res) => {
  try {
    const { pickup_lat, pickup_lng, dest_lat, dest_lng, service_type } = req.body;
    if (!pickup_lat || !pickup_lng || !dest_lat || !dest_lng || !service_type)
      return resErr(res, 'Pickup and destination coordinates and service type required');
    const price = calcPrice(pickup_lat, pickup_lng, dest_lat, dest_lng, service_type);
    if (!price) return resErr(res, 'Cannot calculate price for this route');
    resOK(res, price);
  } catch(e) { resErr(res, e.message); }
});

app.post('/api/deliveries', auth, roleAuth('customer','business'), (req, res) => {
  try {
    const {
      service_type, pickup_address, pickup_lat, pickup_lng, pickup_name, pickup_phone, pickup_instructions,
      dest_address, dest_lat, dest_lng, dest_name, dest_phone, dest_instructions,
      item_description, item_weight, item_category, special_instructions,
      payment_method, preferred_time, business_ref, department
    } = req.body;

    if (!pickup_address || !dest_address || !pickup_name || !pickup_phone || !dest_name || !dest_phone)
      return resErr(res, 'Pickup and destination details are required');
    if (!service_type || !['parcel','document'].includes(service_type))
      return resErr(res, 'Valid service type required');

    const price = calcPrice(pickup_lat, pickup_lng, dest_lat, dest_lng, service_type);
    if (!price) return resErr(res, 'Cannot calculate price for this route');

    const id = uuidv4();
    const orderNo = genOrderNo();
    const pickupOtp = genOTP().substr(0,4);
    const deliveryOtp = genOTP().substr(0,4);

    db.prepare(`INSERT INTO deliveries (
      id,order_no,customer_id,business_id,service_type,status,
      pickup_address,pickup_lat,pickup_lng,pickup_name,pickup_phone,pickup_instructions,pickup_otp,
      dest_address,dest_lat,dest_lng,dest_name,dest_phone,dest_instructions,delivery_otp,
      item_description,item_weight,item_category,special_instructions,
      origin_zone,dest_zone,distance_km,customer_price,rider_earnings,platform_fee,total_charge,
      payment_method,preferred_time,business_ref,department
    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`).run(
      id, orderNo, req.user.id, req.user.role==='business'?req.user.id:null, service_type, 'created',
      pickup_address, pickup_lat, pickup_lng, pickup_name, pickup_phone, pickup_instructions||null, pickupOtp,
      dest_address, dest_lat, dest_lng, dest_name, dest_phone, dest_instructions||null, deliveryOtp,
      item_description||null, item_weight||null, item_category||null, special_instructions||null,
      price.originZone.name, price.destZone.name, price.distance_km,
      price.customerPrice, price.riderEarnings, price.platformFee, price.totalCharge,
      payment_method||'mobile_money', preferred_time||null, business_ref||null, department||null
    );
    addEvent(id, 'created', pickup_lat, pickup_lng, 'Delivery requested');

    // Start dispatch
    dispatchDelivery(id);

    const delivery = db.prepare('SELECT * FROM deliveries WHERE id=?').get(id);
    resOK(res, { delivery, message: 'Delivery created, searching for rider...' }, 201);
  } catch(e) { resErr(res, e.message); }
});

app.get('/api/deliveries', auth, (req, res) => {
  let query, params;
  if (req.user.role === 'rider') {
    query = 'SELECT * FROM deliveries WHERE rider_id=?';
    params = [req.user.id];
  } else if (req.user.role === 'business') {
    query = 'SELECT * FROM deliveries WHERE business_id=?';
    params = [req.user.id];
  } else {
    query = 'SELECT * FROM deliveries WHERE customer_id=?';
    params = [req.user.id];
  }
  if (req.query.status) { query += ' AND status=?'; params.push(req.query.status); }
  query += ' ORDER BY created_at DESC LIMIT 50';
  resOK(res, db.prepare(query).all(...params));
});

app.get('/api/deliveries/:id', auth, (req, res) => {
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!d) return resErr(res, 'Delivery not found', 404);
  // Access check
  if (req.user.role === 'customer' && d.customer_id !== req.user.id) return resErr(res, 'Not found', 404);
  if (req.user.role === 'rider' && d.rider_id !== req.user.id) return resErr(res, 'Not found', 404);
  if (req.user.role === 'business' && d.business_id !== req.user.id) return resErr(res, 'Not found', 404);
  const events = db.prepare('SELECT * FROM delivery_events WHERE delivery_id=? ORDER BY created_at').all(d.id);
  resOK(res, { delivery: d, events });
});

app.get('/api/deliveries/:id/track', auth, (req, res) => {
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!d) return resErr(res, 'Delivery not found', 404);
  if (req.user.role === 'customer' && d.customer_id !== req.user.id) return resErr(res, 'Not found', 404);
  if (req.user.role === 'rider' && d.rider_id !== req.user.id) return resErr(res, 'Not found', 404);
  if (req.user.role === 'business' && d.business_id !== req.user.id) return resErr(res, 'Not found', 404);
  const events = db.prepare('SELECT * FROM delivery_events WHERE delivery_id=? ORDER BY created_at').all(d.id);
  const riderLoc = d.rider_id ? db.prepare('SELECT lat,lng,created_at FROM rider_locations WHERE rider_id=? ORDER BY created_at DESC LIMIT 1').get(d.rider_id) : null;
  const rider = d.rider_id ? db.prepare('SELECT u.full_name,u.avatar,u.phone,r.motorcycle_plate,r.motorcycle_make,r.avg_rating FROM users u JOIN riders r ON u.id=r.user_id WHERE u.id=?').get(d.rider_id) : null;
  resOK(res, { delivery: d, events, riderLocation: riderLoc, rider });
});

// Rider delivery actions
app.put('/api/deliveries/:id/accept', auth, roleAuth('rider'), (req, res) => {
  const d = db.prepare("SELECT * FROM deliveries WHERE id=? AND status='searching'").get(req.params.id);
  if (!d) return resErr(res, 'Delivery not available');
  const rider = db.prepare('SELECT * FROM riders WHERE user_id=?').get(req.user.id);
  if (rider.approval_status !== 'approved') return resErr(res, 'Rider not approved');
  if (rider.online_status !== 'online') return resErr(res, 'Go online first');
  // Check if rider already has active delivery
  const active = db.prepare("SELECT id FROM deliveries WHERE rider_id=? AND status NOT IN ('delivered','cancelled','failed')").get(req.user.id);
  if (active) return resErr(res, 'You have an active delivery');
  const acceptOffer = db.transaction(() => {
    const offer = db.prepare("SELECT id FROM delivery_offers WHERE delivery_id=? AND rider_id=? AND status='offered' AND expires_at>datetime('now')").get(d.id, req.user.id);
    if (!offer) return false;
    const claimed = db.prepare("UPDATE deliveries SET rider_id=?,status='assigned',assigned_at=datetime('now'),updated_at=datetime('now') WHERE id=? AND status='searching'").run(req.user.id, d.id);
    if (!claimed.changes) return false;
    db.prepare("UPDATE delivery_offers SET status=CASE WHEN id=? THEN 'accepted' ELSE 'expired' END,responded_at=datetime('now') WHERE delivery_id=? AND status='offered'").run(offer.id, d.id);
    addEvent(d.id, 'assigned', null, null, 'Rider accepted delivery offer');
    return true;
  });
  if (!acceptOffer()) return resErr(res, 'Offer is unavailable or already accepted', 409);
  db.prepare("UPDATE riders SET online_status='busy' WHERE user_id=?").run(req.user.id);
  notifyUser(d.customer_id, 'rider_assigned', 'Rider Assigned', `A rider has been assigned to your delivery ${d.order_no}`, { delivery_id: d.id });
  // Emit to customer socket
  const custSock = userSockets[d.customer_id];
  if (custSock) custSock.emit('delivery_update', { delivery_id: d.id, status: 'assigned', rider_id: req.user.id });
  resOK(res, { message: 'Delivery accepted', delivery: db.prepare('SELECT * FROM deliveries WHERE id=?').get(d.id) });
});

app.put('/api/deliveries/:id/going-pickup', auth, roleAuth('rider'), (req, res) => {
  const d = db.prepare("SELECT * FROM deliveries WHERE id=? AND rider_id=? AND status='assigned'").get(req.params.id, req.user.id);
  if (!d) return resErr(res, 'Delivery not found');
  updateDeliveryStatus(d.id, 'going_pickup', { lat: req.body.lat, lng: req.body.lng });
  notifyUser(d.customer_id, 'rider_en_route', 'Rider En Route', `Your rider is heading to pickup for ${d.order_no}`);
  resOK(res, { message: 'Status updated' });
});

app.put('/api/deliveries/:id/arrive-pickup', auth, roleAuth('rider'), (req, res) => {
  const d = db.prepare("SELECT * FROM deliveries WHERE id=? AND rider_id=? AND status='going_pickup'").get(req.params.id, req.user.id);
  if (!d) return resErr(res, 'Delivery not found');
  updateDeliveryStatus(d.id, 'arrived_pickup', { lat: req.body.lat, lng: req.body.lng });
  notifyUser(d.customer_id, 'rider_arrived_pickup', 'Rider at Pickup', `Your rider has arrived at the pickup location for ${d.order_no}`, { pickup_otp: d.pickup_otp });
  resOK(res, { message: 'Arrived at pickup' });
});

app.put('/api/deliveries/:id/verify-pickup', auth, roleAuth('rider'), (req, res) => {
  const d = db.prepare("SELECT * FROM deliveries WHERE id=? AND rider_id=? AND status='arrived_pickup'").get(req.params.id, req.user.id);
  if (!d) return resErr(res, 'Delivery not found');
  const { otp, photo } = req.body;
  if (d.pickup_otp && otp !== d.pickup_otp) return resErr(res, 'Invalid pickup OTP');
  const now = new Date().toISOString();
  updateDeliveryStatus(d.id, 'picked_up', { pickup_verified_at: now, pickup_photo: photo||null, lat: req.body.lat, lng: req.body.lng });
  notifyUser(d.customer_id, 'item_collected', 'Item Collected', `Your item has been picked up for ${d.order_no}`);
  resOK(res, { message: 'Pickup verified' });
});

app.put('/api/deliveries/:id/in-transit', auth, roleAuth('rider'), (req, res) => {
  const d = db.prepare("SELECT * FROM deliveries WHERE id=? AND rider_id=? AND status='picked_up'").get(req.params.id, req.user.id);
  if (!d) return resErr(res, 'Delivery not found');
  updateDeliveryStatus(d.id, 'in_transit', { picked_up_at: d.picked_up_at || new Date().toISOString(), lat: req.body.lat, lng: req.body.lng });
  notifyUser(d.customer_id, 'in_transit', 'Delivery in Transit', `Your ${d.service_type} is on its way for ${d.order_no}`);
  resOK(res, { message: 'In transit' });
});

app.put('/api/deliveries/:id/arrive-dest', auth, roleAuth('rider'), (req, res) => {
  const d = db.prepare("SELECT * FROM deliveries WHERE id=? AND rider_id=? AND status='in_transit'").get(req.params.id, req.user.id);
  if (!d) return resErr(res, 'Delivery not found');
  updateDeliveryStatus(d.id, 'arrived_dest', { lat: req.body.lat, lng: req.body.lng });
  notifyUser(d.customer_id, 'rider_arrived_dest', 'Rider at Destination', `Your rider has arrived at the destination for ${d.order_no}`, { delivery_otp: d.delivery_otp });
  // Also notify recipient
  resOK(res, { message: 'Arrived at destination' });
});

app.put('/api/deliveries/:id/complete', auth, roleAuth('rider'), (req, res) => {
  const d = db.prepare("SELECT * FROM deliveries WHERE id=? AND rider_id=? AND status='arrived_dest'").get(req.params.id, req.user.id);
  if (!d) return resErr(res, 'Delivery not found');
  const { otp, photo, recipient_name, notes } = req.body;
  if (d.delivery_otp && otp !== d.delivery_otp) return resErr(res, 'Invalid delivery OTP');
  const now = new Date().toISOString();
  updateDeliveryStatus(d.id, 'delivered', {
    delivery_verified_at: now, delivery_photo: photo||null,
    recipient_name: recipient_name||d.dest_name, delivery_notes: notes||null,
    delivered_at: now, payment_status: 'paid', paid_at: now
  });
  // Update rider stats
  db.prepare('UPDATE riders SET total_deliveries=total_deliveries+1, total_earnings=total_earnings+?, online_status=\'online\', updated_at=datetime(\'now\') WHERE user_id=?')
    .run(d.rider_earnings, req.user.id);
  // Record payment
  db.prepare('INSERT INTO payments (id,delivery_id,user_id,amount,type,method,status,processed_at) VALUES (?,?,?,?,?,?,?,?)')
    .run(uuidv4(), d.id, req.user.id, d.rider_earnings, 'rider_payout', 'mobile_money', 'completed', now);
  db.prepare('INSERT INTO payments (id,delivery_id,user_id,amount,type,method,status,processed_at) VALUES (?,?,?,?,?,?,?,?)')
    .run(uuidv4(), d.id, d.customer_id, d.total_charge, 'customer_pay', d.payment_method, 'completed', now);
  db.prepare('INSERT INTO payments (id,delivery_id,user_id,amount,type,status,processed_at) VALUES (?,?,?,?,?,?,?)')
    .run(uuidv4(), d.id, d.customer_id, d.platform_fee, 'platform_fee', 'completed', now);
  notifyUser(d.customer_id, 'delivered', 'Delivery Completed', `Your ${d.service_type} has been delivered! Order: ${d.order_no}`, { delivery_id: d.id });
  resOK(res, { message: 'Delivery completed' });
});

app.put('/api/deliveries/:id/cancel', auth, (req, res) => {
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!d) return resErr(res, 'Delivery not found');
  if (['delivered','cancelled','failed'].includes(d.status)) return resErr(res, 'Cannot cancel this delivery');
  const canCancel = (req.user.id === d.customer_id && ['created','searching','assigned'].includes(d.status)) ||
                    (req.user.id === d.rider_id && ['assigned','going_pickup'].includes(d.status)) ||
                    req.user.role === 'admin';
  if (!canCancel) return resErr(res, 'Cannot cancel at this stage');
  updateDeliveryStatus(d.id, 'cancelled', {
    cancelled_at: new Date().toISOString(),
    cancel_reason: req.body.reason || 'Cancelled by ' + req.user.role,
    cancelled_by: req.user.role
  });
  if (d.rider_id) {
    db.prepare("UPDATE riders SET online_status='online' WHERE user_id=?").run(d.rider_id);
    notifyUser(d.rider_id, 'delivery_cancelled', 'Delivery Cancelled', `Delivery ${d.order_no} has been cancelled`);
  }
  notifyUser(d.customer_id, 'delivery_cancelled', 'Delivery Cancelled', `Your delivery ${d.order_no} has been cancelled`);
  resOK(res, { message: 'Delivery cancelled' });
});

// ─── PAYMENT ROUTES ──────────────────────────────────────────
app.post('/api/payments/process', auth, (req, res) => {
  try {
    const { delivery_id, method } = req.body;
    const d = db.prepare('SELECT * FROM deliveries WHERE id=? AND customer_id=?').get(delivery_id, req.user.id);
    if (!d) return resErr(res, 'Delivery not found');
    if (d.payment_status === 'paid') return resErr(res, 'Already paid');
    // Simulate mobile money payment
    const ref = 'MM' + Date.now();
    db.prepare("UPDATE deliveries SET payment_status='paid',payment_ref=?,paid_at=datetime('now') WHERE id=?").run(ref, d.id);
    db.prepare('INSERT INTO payments (id,delivery_id,user_id,amount,type,method,status,ref,processed_at) VALUES (?,?,?,?,?,?,?,?,?)')
      .run(uuidv4(), d.id, req.user.id, d.total_charge, 'customer_pay', method||'mobile_money', 'completed', ref, new Date().toISOString());
    resOK(res, { message: 'Payment successful', ref });
  } catch(e) { resErr(res, e.message); }
});

// ─── RATING ROUTES ───────────────────────────────────────────
app.post('/api/ratings', auth, (req, res) => {
  try {
    const { delivery_id, score, review } = req.body;
    if (!delivery_id || !score || score < 1 || score > 5) return resErr(res, 'Valid delivery ID and score (1-5) required');
    const d = db.prepare('SELECT * FROM deliveries WHERE id=? AND customer_id=? AND status=?').get(delivery_id, req.user.id, 'delivered');
    if (!d) return resErr(res, 'Delivery not found or not deliverable');
    const existing = db.prepare('SELECT id FROM ratings WHERE delivery_id=? AND rater_id=?').get(delivery_id, req.user.id);
    if (existing) return resErr(res, 'Already rated');
    db.prepare('INSERT INTO ratings (id,delivery_id,rater_id,rated_id,score,review) VALUES (?,?,?,?,?,?)')
      .run(uuidv4(), delivery_id, req.user.id, d.rider_id, score, review||null);
    // Update rider average
    const agg = db.prepare('SELECT AVG(score) as avg, COUNT(*) as cnt FROM ratings WHERE rated_id=?').get(d.rider_id);
    db.prepare('UPDATE riders SET avg_rating=ROUND(?,1),rating_count=? WHERE user_id=?').run(agg.avg, agg.cnt, d.rider_id);
    db.prepare('UPDATE deliveries SET customer_rating=?,customer_review=?,rated_at=datetime(\'now\') WHERE id=?').run(score, review||null, delivery_id);
    resOK(res, { message: 'Rating submitted' });
  } catch(e) { resErr(res, e.message); }
});

// ─── SUPPORT TICKETS ─────────────────────────────────────────
app.post('/api/tickets', auth, (req, res) => {
  try {
    const { delivery_id, category, subject, description, priority } = req.body;
    if (!category || !subject || !description) return resErr(res, 'Category, subject, and description required');
    const id = uuidv4();
    db.prepare('INSERT INTO tickets (id,delivery_id,reporter_id,category,subject,description,priority) VALUES (?,?,?,?,?,?,?)')
      .run(id, delivery_id||null, req.user.id, category, subject, description, priority||'medium');
    resOK(res, { id, message: 'Ticket created' }, 201);
  } catch(e) { resErr(res, e.message); }
});

app.get('/api/tickets', auth, (req, res) => {
  if (req.user.role === 'admin') {
    resOK(res, db.prepare('SELECT t.*,u.full_name as reporter_name FROM tickets t JOIN users u ON t.reporter_id=u.id ORDER BY t.created_at DESC').all());
  } else {
    resOK(res, db.prepare('SELECT * FROM tickets WHERE reporter_id=? ORDER BY created_at DESC').all(req.user.id));
  }
});

app.put('/api/tickets/:id', auth, roleAuth('admin'), (req, res) => {
  const { status, assigned_to, resolution } = req.body;
  const sets = []; const vals = [];
  if (status) { sets.push('status=?'); vals.push(status); }
  if (assigned_to) { sets.push('assigned_to=?'); vals.push(assigned_to); }
  if (resolution) { sets.push('resolution=?,resolved_at=datetime(\'now\')'); vals.push(resolution); }
  if (sets.length === 0) return resErr(res, 'Nothing to update');
  sets.push("updated_at=datetime('now')");
  vals.push(req.params.id);
  db.prepare(`UPDATE tickets SET ${sets.join(',')} WHERE id=?`).run(...vals);
  resOK(res, { message: 'Ticket updated' });
});

// ─── ADMIN ROUTES ────────────────────────────────────────────
app.get('/api/admin/dashboard', auth, roleAuth('admin'), (req, res) => {
  const active = db.prepare("SELECT COUNT(*) as c FROM deliveries WHERE status NOT IN ('delivered','cancelled','failed')").get().c;
  const todayDeliveries = db.prepare("SELECT COUNT(*) as c FROM deliveries WHERE date(created_at)=date('now')").get().c;
  const todayCompleted = db.prepare("SELECT COUNT(*) as c FROM deliveries WHERE status='delivered' AND date(delivered_at)=date('now')").get().c;
  const todayRevenue = db.prepare("SELECT COALESCE(SUM(platform_fee),0) as t FROM deliveries WHERE status='delivered' AND date(delivered_at)=date('now')").get().t;
  const totalCustomers = db.prepare("SELECT COUNT(*) as c FROM users WHERE role='customer'").get().c;
  const totalRiders = db.prepare("SELECT COUNT(*) as c FROM users WHERE role='rider'").get().c;
  const onlineRiders = db.prepare("SELECT COUNT(*) as c FROM riders WHERE online_status='online'").get().c;
  const pendingApprovals = db.prepare("SELECT COUNT(*) as c FROM riders WHERE approval_status='pending'").get().c;
  const openTickets = db.prepare("SELECT COUNT(*) as c FROM tickets WHERE status='open'").get().c;
  const totalBusinesses = db.prepare("SELECT COUNT(*) as c FROM users WHERE role='business'").get().c;
  const monthRevenue = db.prepare("SELECT COALESCE(SUM(platform_fee),0) as t FROM deliveries WHERE status='delivered' AND date(delivered_at)>=date('now','start of month')").get().t;
  const monthDeliveries = db.prepare("SELECT COUNT(*) as c FROM deliveries WHERE status='delivered' AND date(delivered_at)>=date('now','start of month')").get().c;
  resOK(res, { active, todayDeliveries, todayCompleted, todayRevenue, totalCustomers, totalRiders, onlineRiders, pendingApprovals, openTickets, totalBusinesses, monthRevenue, monthDeliveries });
});

app.get('/api/admin/users', auth, roleAuth('admin'), (req, res) => {
  const { role, status, search, page=1, limit=20 } = req.query;
  let query = 'SELECT u.id,u.phone,u.email,u.full_name,u.role,u.status,u.created_at';
  if (role === 'rider') query += ',r.approval_status,r.online_status,r.avg_rating,r.total_deliveries,r.total_earnings';
  if (role === 'business') query += ',b.company_name,b.tax_id,b.approval_status as biz_status';
  query += ' FROM users u';
  if (role === 'rider') query += ' LEFT JOIN riders r ON u.id=r.user_id';
  if (role === 'business') query += ' LEFT JOIN businesses b ON u.id=b.user_id';
  const conditions = []; const params = [];
  if (role) { conditions.push('u.role=?'); params.push(role); }
  if (status) { conditions.push('u.status=?'); params.push(status); }
  if (search) { conditions.push('(u.full_name LIKE ? OR u.phone LIKE ?)'); params.push(`%${search}%`,`%${search}%`); }
  if (conditions.length) query += ' WHERE ' + conditions.join(' AND ');
  query += ' ORDER BY u.created_at DESC LIMIT ? OFFSET ?';
  params.push(parseInt(limit), (parseInt(page)-1)*parseInt(limit));
  resOK(res, db.prepare(query).all(...params));
});

app.get('/api/admin/riders/:id', auth, roleAuth('admin'), (req, res) => {
  const rider = db.prepare('SELECT u.*,r.* FROM users u JOIN riders r ON u.id=r.user_id WHERE u.id=?').get(req.params.id);
  if (!rider) return resErr(res, 'Rider not found', 404);
  const recentDeliveries = db.prepare('SELECT * FROM deliveries WHERE rider_id=? ORDER BY created_at DESC LIMIT 10').all(req.params.id);
  const ratings = db.prepare('SELECT * FROM ratings WHERE rated_id=? ORDER BY created_at DESC LIMIT 10').all(req.params.id);
  resOK(res, { ...rider, recentDeliveries, ratings });
});

app.put('/api/admin/riders/:id/approve', auth, roleAuth('admin'), (req, res) => {
  const { action } = req.body;
  if (!['approve','reject','suspend'].includes(action)) return resErr(res, 'Invalid action');
  const statusMap = { approve: 'approved', reject: 'rejected', suspend: 'suspended' };
  db.prepare('UPDATE riders SET approval_status=?,updated_at=datetime(\'now\') WHERE user_id=?').run(statusMap[action], req.params.id);
  if (action === 'suspend') db.prepare("UPDATE users SET status='suspended' WHERE id=?").run(req.params.id);
  audit(req.user.id, 'rider_'+action, 'rider', req.params.id);
  notifyUser(req.params.id, 'rider_'+action, 'Rider Status Update', `Your rider account has been ${statusMap[action]}`);
  resOK(res, { message: `Rider ${statusMap[action]}` });
});

app.get('/api/admin/deliveries', auth, roleAuth('admin'), (req, res) => {
  const { status, search, page=1, limit=20 } = req.query;
  let query = 'SELECT d.*,u1.full_name as customer_name,u2.full_name as rider_name FROM deliveries d LEFT JOIN users u1 ON d.customer_id=u1.id LEFT JOIN users u2 ON d.rider_id=u2.id';
  const conditions = []; const params = [];
  if (status) { conditions.push('d.status=?'); params.push(status); }
  if (search) { conditions.push('(d.order_no LIKE ? OR d.pickup_address LIKE ? OR d.dest_address LIKE ?)'); params.push(`%${search}%`,`%${search}%`,`%${search}%`); }
  if (conditions.length) query += ' WHERE ' + conditions.join(' AND ');
  query += ' ORDER BY d.created_at DESC LIMIT ? OFFSET ?';
  params.push(parseInt(limit), (parseInt(page)-1)*parseInt(limit));
  resOK(res, db.prepare(query).all(...params));
});

app.put('/api/admin/deliveries/:id', auth, roleAuth('admin'), (req, res) => {
  const { status, note } = req.body;
  if (!status) return resErr(res, 'Status required');
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!d) return resErr(res, 'Not found', 404);
  updateDeliveryStatus(d.id, status, { note: note || 'Admin update' });
  audit(req.user.id, 'admin_status_change', 'delivery', d.id, { from: d.status, to: status });
  resOK(res, { message: 'Status updated' });
});

app.get('/api/admin/zones', auth, roleAuth('admin'), (req, res) => {
  resOK(res, db.prepare('SELECT * FROM delivery_zones ORDER BY sort_order').all());
});

app.post('/api/admin/zones', auth, roleAuth('admin'), (req, res) => {
  try {
    const { name, center_lat, center_lng, radius_km, base_price_parcel, base_price_document, per_km_rate, sort_order } = req.body;
    if (!name || !center_lat || !center_lng) return resErr(res, 'Name and coordinates required');
    const id = uuidv4();
    db.prepare('INSERT INTO delivery_zones (id,name,center_lat,center_lng,radius_km,base_price_parcel,base_price_document,per_km_rate,sort_order) VALUES (?,?,?,?,?,?,?,?,?)')
      .run(id, name, center_lat, center_lng, radius_km||3, base_price_parcel||1500, base_price_document||1000, per_km_rate||200, sort_order||0);
    resOK(res, { id, message: 'Zone created' }, 201);
  } catch(e) { resErr(res, e.message); }
});

app.put('/api/admin/zones/:id', auth, roleAuth('admin'), (req, res) => {
  const fields = ['name','center_lat','center_lng','radius_km','base_price_parcel','base_price_document','per_km_rate','is_active','sort_order'];
  const sets = []; const vals = [];
  for (const f of fields) {
    if (req.body[f] !== undefined) { sets.push(`${f}=?`); vals.push(req.body[f]); }
  }
  if (sets.length === 0) return resErr(res, 'Nothing to update');
  vals.push(req.params.id);
  db.prepare(`UPDATE delivery_zones SET ${sets.join(',')} WHERE id=?`).run(...vals);
  resOK(res, { message: 'Zone updated' });
});

app.get('/api/admin/pricing', auth, roleAuth('admin'), (req, res) => {
  const matrix = db.prepare(`SELECT zp.*, oz.name as origin_name, dz.name as dest_name FROM zone_pricing zp
    JOIN delivery_zones oz ON zp.origin_zone_id=oz.id JOIN delivery_zones dz ON zp.dest_zone_id=dz.id WHERE zp.is_active=1`).all();
  const config = db.prepare('SELECT * FROM pricing_config').all();
  resOK(res, { matrix, config });
});

app.put('/api/admin/pricing/config', auth, roleAuth('admin'), (req, res) => {
  const { key, value } = req.body;
  if (!key || value === undefined) return resErr(res, 'Key and value required');
  db.prepare("INSERT OR REPLACE INTO pricing_config (key,value,updated_at) VALUES (?,?,datetime('now'))").run(key, String(value));
  resOK(res, { message: 'Config updated' });
});

app.put('/api/admin/pricing/zone', auth, roleAuth('admin'), (req, res) => {
  const { id, parcel_price, document_price, estimated_min } = req.body;
  if (!id) return resErr(res, 'ID required');
  const sets = []; const vals = [];
  if (parcel_price !== undefined) { sets.push('parcel_price=?'); vals.push(parcel_price); }
  if (document_price !== undefined) { sets.push('document_price=?'); vals.push(document_price); }
  if (estimated_min !== undefined) { sets.push('estimated_min=?'); vals.push(estimated_min); }
  if (sets.length === 0) return resErr(res, 'Nothing to update');
  vals.push(id);
  db.prepare(`UPDATE zone_pricing SET ${sets.join(',')} WHERE id=?`).run(...vals);
  resOK(res, { message: 'Pricing updated' });
});

app.get('/api/admin/finances', auth, roleAuth('admin'), (req, res) => {
  const daily = db.prepare(`SELECT date(delivered_at) as day, COUNT(*) as deliveries,
    SUM(total_charge) as gross, SUM(platform_fee) as revenue, SUM(rider_earnings) as payouts
    FROM deliveries WHERE status='delivered' AND delivered_at >= datetime('now','-30 days')
    GROUP BY date(delivered_at) ORDER BY day DESC`).all();
  const summary = db.prepare(`SELECT COUNT(*) as total, SUM(total_charge) as gross, SUM(platform_fee) as revenue,
    SUM(rider_earnings) as payouts FROM deliveries WHERE status='delivered' AND delivered_at >= datetime('now','-30 days')`).get();
  const pendingPayouts = db.prepare("SELECT COALESCE(SUM(rider_earnings),0) as t FROM deliveries WHERE status='delivered' AND rider_earnings > 0").get().t;
  resOK(res, { daily, summary, pendingPayouts });
});

app.get('/api/admin/live-map', auth, roleAuth('admin'), (req, res) => {
  const riders = db.prepare("SELECT u.id,u.full_name,r.motorcycle_plate,r.avg_rating,r.current_lat as lat,r.current_lng as lng,r.online_status,r.last_location_update FROM users u JOIN riders r ON u.id=r.user_id WHERE r.online_status IN ('online','busy')").all();
  const activeDeliveries = db.prepare(`SELECT d.*,u.full_name as rider_name,r.current_lat as rider_lat,r.current_lng as rider_lng,r.last_location_update
    FROM deliveries d JOIN users u ON d.rider_id=u.id JOIN riders r ON d.rider_id=r.user_id
    WHERE d.status NOT IN ('delivered','cancelled','failed','created','searching')`).all();
  resOK(res, {
    riders: riders.map(rider => ({ ...rider, is_demo: rider.id.startsWith('demo-map-') })),
    activeDeliveries: activeDeliveries.map(delivery => ({ ...delivery, is_demo: delivery.id.startsWith('demo-map-') }))
  });
});

app.get('/api/admin/reports', auth, roleAuth('admin'), (req, res) => {
  const { type } = req.query;
  let data;
  if (type === 'delivery-status') {
    data = db.prepare('SELECT status, COUNT(*) as count FROM deliveries GROUP BY status').all();
  } else if (type === 'service-type') {
    data = db.prepare('SELECT service_type, COUNT(*) as count FROM deliveries GROUP BY service_type').all();
  } else if (type === 'top-zones') {
    data = db.prepare("SELECT dest_zone as zone, COUNT(*) as count FROM deliveries WHERE status='delivered' GROUP BY dest_zone ORDER BY count DESC LIMIT 10").all();
  } else if (type === 'rider-performance') {
    data = db.prepare("SELECT u.full_name, r.total_deliveries, r.avg_rating, r.total_earnings FROM users u JOIN riders r ON u.id=r.user_id WHERE r.approval_status='approved' ORDER BY r.total_deliveries DESC LIMIT 20").all();
  } else {
    data = db.prepare('SELECT date(created_at) as day, COUNT(*) as count FROM deliveries GROUP BY date(created_at) ORDER BY day DESC LIMIT 30').all();
  }
  resOK(res, data);
});

// ─── Dispatch Engine ─────────────────────────────────────────
const userSockets = {};

function dispatchDelivery(deliveryId) {
  updateDeliveryStatus(deliveryId, 'searching');
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(deliveryId);
  if (!d) return;

  let radius = parseFloat(getConfig('rider_search_radius_km', '5'));
  const expandStep = parseFloat(getConfig('rider_search_expand_km', '2'));
  const maxRadius = radius + expandStep * 3;

  function search() {
    const riders = db.prepare(`SELECT u.id,u.full_name,u.phone,r.avg_rating,r.current_lat,r.current_lng,
      (SELECT COUNT(*) FROM deliveries WHERE rider_id=r.user_id AND status NOT IN ('delivered','cancelled','failed')) as active_count
      FROM users u JOIN riders r ON u.id=r.user_id
      WHERE r.approval_status='approved' AND r.online_status='online' AND r.current_lat IS NOT NULL
      ORDER BY r.avg_rating DESC`).all();

    const nearby = riders.filter(r => {
      if (r.active_count > 0) return false;
      const dist = haversine(d.pickup_lat, d.pickup_lng, r.current_lat, r.current_lng);
      return dist <= radius;
    });

    if (nearby.length > 0) {
      for (const rider of nearby.slice(0, 5)) {
        const timeoutSeconds = parseInt(getConfig('rider_accept_timeout_sec', '30'));
        const existingOffer = db.prepare("SELECT id FROM delivery_offers WHERE delivery_id=? AND rider_id=? AND status='offered' AND expires_at>datetime('now')").get(d.id, rider.id);
        const offerId = existingOffer?.id || uuidv4();
        if (!existingOffer) db.prepare("INSERT INTO delivery_offers (id,delivery_id,rider_id,expires_at) VALUES (?,?,?,datetime('now',?))")
          .run(offerId, d.id, rider.id, `+${timeoutSeconds} seconds`);
        const sock = userSockets[rider.id];
        if (sock) {
          sock.emit('new_delivery', {
            offer_id: offerId,
            id: d.id, order_no: d.order_no, service_type: d.service_type,
            pickup_address: d.pickup_address, pickup_lat: d.pickup_lat, pickup_lng: d.pickup_lng,
            dest_address: d.dest_address, dest_lat: d.dest_lat, dest_lng: d.dest_lng,
            earnings: d.rider_earnings, distance_km: d.distance_km,
            estimated_minutes: d.est_delivery_time,
            timeout: timeoutSeconds
          });
        }
      }
      // Set timeout for auto-expand
      const timeout = parseInt(getConfig('rider_accept_timeout_sec', '30')) * 1000;
      setTimeout(() => {
        const current = db.prepare('SELECT status FROM deliveries WHERE id=?').get(deliveryId);
        if (current && current.status === 'searching') {
          if (radius < maxRadius) {
            radius += expandStep;
            search();
          } else {
            updateDeliveryStatus(deliveryId, 'failed', { note: 'No rider found' });
            notifyUser(d.customer_id, 'no_rider', 'No Rider Available', `We could not find a rider for ${d.order_no}. Please try again.`, { delivery_id: d.id });
          }
        }
      }, timeout);
    } else {
      if (radius < maxRadius) {
        radius += expandStep;
        setTimeout(search, 3000);
      } else {
        updateDeliveryStatus(deliveryId, 'failed', { note: 'No rider found in expanded search' });
        notifyUser(d.customer_id, 'no_rider', 'No Rider Available', `We could not find a rider for ${d.order_no}.`, { delivery_id: d.id });
      }
    }
  }
  search();
}

// ─── WebSocket ───────────────────────────────────────────────
io.on('connection', (socket) => {
  let currentUserId = null;

  socket.on('authenticate', (token) => {
    try {
      const payload = jwt.verify(token, JWT_SECRET);
      currentUserId = payload.id;
      userSockets[currentUserId] = socket;
      console.log(`Socket authenticated: ${currentUserId}`);
    } catch(e) { socket.disconnect(); }
  });

  socket.on('rider_location', (data) => {
    if (!currentUserId) return;
    const { lat, lng, delivery_id } = data;
    if (lat && lng) {
      db.prepare('UPDATE riders SET current_lat=?,current_lng=?,last_location_update=datetime(\'now\') WHERE user_id=?')
        .run(lat, lng, currentUserId);
      db.prepare('INSERT INTO rider_locations (id,rider_id,delivery_id,lat,lng) VALUES (?,?,?,?,?)')
        .run(uuidv4(), currentUserId, delivery_id||null, lat, lng);
      // Forward to customer tracking this delivery
      if (delivery_id) {
        const d = db.prepare('SELECT customer_id FROM deliveries WHERE id=?').get(delivery_id);
        if (d) {
          const custSock = userSockets[d.customer_id];
          if (custSock) custSock.emit('rider_location', { delivery_id, lat, lng });
        }
      }
    }
  });

  socket.on('disconnect', () => {
    if (currentUserId) {
      delete userSockets[currentUserId];
      console.log(`Socket disconnected: ${currentUserId}`);
    }
  });
});

// ─── Health Check ─────────────────────────────────────────────
app.get('/health', (req, res) => {
  try {
    db.prepare('SELECT 1').get();
    res.status(200).json({ status: 'ok', service: 'movo', timestamp: new Date().toISOString() });
  } catch (error) {
    res.status(503).json({ status: 'error', service: 'movo' });
  }
});

// ─── Root Redirect ───────────────────────────────────────────
app.get('/', (req, res) => {
  res.send(`
    <!DOCTYPE html>
    <html><head><title>MOVO Platform</title>
    <style>
      *{margin:0;padding:0;box-sizing:border-box}
      body{font-family:'Space Grotesk',sans-serif;background:#0A6847;color:#fff;min-height:100vh;display:flex;align-items:center;justify-content:center;flex-direction:column}
      h1{font-size:4rem;font-weight:700;letter-spacing:-2px;margin-bottom:.5rem}
      p{font-size:1.2rem;opacity:.8;margin-bottom:2rem}
      .links{display:flex;gap:1rem;flex-wrap:wrap;justify-content:center}
      a{display:inline-flex;align-items:center;gap:.5rem;padding:.8rem 1.5rem;background:rgba(255,255,255,.12);border:1px solid rgba(255,255,255,.2);border-radius:12px;color:#fff;text-decoration:none;font-weight:500;transition:all .2s}
      a:hover{background:rgba(255,255,255,.22);transform:translateY(-2px)}
      .tag{font-size:.7rem;background:rgba(255,255,255,.15);padding:.2rem .6rem;border-radius:20px;margin-left:.5rem}
    </style>
    <link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300;400;500;600;700&display=swap" rel="stylesheet">
    </head><body>
    <h1>MOVO</h1>
    <p>Deliver with Confidence — Kigali, Rwanda</p>
    <div class="links">
      <a href="/customer">Customer App</a>
      <a href="/rider">Rider App</a>
      <a href="/business">Business Portal</a>
      <a href="/admin">Admin Portal</a>
    </div>
    </body></html>
  `);
});

// ─── Error Handler ───────────────────────────────────────────
app.use((err, req, res, next) => {
  console.error('Server error:', err);
  resErr(res, 'Internal server error', 500);
});

// ─── Start Server ────────────────────────────────────────────
seedData();
server.listen(PORT, () => {
  console.log(`
  ╔══════════════════════════════════════════════════╗
  ║  MOVO Platform — Deliver with Confidence         ║
  ║  Server running on http://localhost:${PORT}       ║
  ║                                                  ║
  ║  Customer App:  http://localhost:${PORT}/customer ║
  ║  Rider App:     http://localhost:${PORT}/rider    ║
  ║  Business:      http://localhost:${PORT}/business ║
  ║  Admin:         http://localhost:${PORT}/admin    ║
  ║                                                  ║
  ║  Admin login:   +250780000000 / Admin@2026       ║
  ╚══════════════════════════════════════════════════╝
  `);
});
