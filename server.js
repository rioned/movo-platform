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
const crypto = require('crypto');
const { loadRuntimeConfig, evaluateReadiness } = require('./src/config/runtime');
const { securityHeaders, createRateLimiter } = require('./src/middleware/security');
const { createLogger, createMetrics, requestObserver } = require('./src/middleware/observability');
const validate = require('./src/lib/validate');
const { ValidationError } = validate;

// ─── Configuration ───────────────────────────────────────────
const runtime = loadRuntimeConfig({ ...process.env, DB_PATH: process.env.DB_PATH || path.join(__dirname, 'movo.db') });
const PORT = runtime.port;
const IS_PRODUCTION = runtime.production;
const JWT_SECRET = runtime.jwtSecret;
const JWT_EXPIRY = runtime.jwtExpiry;
const ALLOWED_ORIGINS = runtime.allowedOrigins;
const BCRYPT_ROUNDS = runtime.security.bcryptRounds;
const UPLOAD_DIR = path.join(__dirname, 'uploads');
if (!fs.existsSync(UPLOAD_DIR)) fs.mkdirSync(UPLOAD_DIR, { recursive: true });

const logger = createLogger({ level: runtime.logLevel, service: 'movo-api' });
const metrics = createMetrics();
const { createMessaging } = require('./src/services/messaging');
const messaging = createMessaging({ provider: runtime.providers.sms, logger });

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
if (runtime.trustProxy) app.set('trust proxy', 1);
const corsOptions = { origin(origin, callback) { callback(null, !origin || ALLOWED_ORIGINS.includes(origin)); } };
const server = http.createServer(app);
const io = new Server(server, { cors: corsOptions, pingTimeout: 20_000, maxHttpBufferSize: 1e6 });

app.use(securityHeaders({ httpsOnly: runtime.httpsOnly }));
app.use(requestObserver({ logger, metrics, ignorePaths: ['/health', '/ready', '/metrics'] }));
app.use(cors(corsOptions));
app.use(express.json({ limit: '1mb' }));
app.use(express.urlencoded({ extended: true, limit: '1mb' }));

// Rate limiting: a broad ceiling for every caller, tighter buckets for credential
// and state-changing endpoints. Limits are configuration, not code.
const globalLimiter = createRateLimiter({ scope: 'global', windowMs: runtime.rateLimit.windowMs, max: runtime.rateLimit.global, enabled: runtime.rateLimit.enabled });
const authLimiter = createRateLimiter({
  scope: 'auth', windowMs: runtime.rateLimit.windowMs, max: runtime.rateLimit.auth, enabled: runtime.rateLimit.enabled,
  message: 'Too many authentication attempts. Please wait a moment and try again.', code: 'auth_rate_limited'
});
const writeLimiter = createRateLimiter({ scope: 'write', windowMs: runtime.rateLimit.windowMs, max: runtime.rateLimit.write, enabled: runtime.rateLimit.enabled });
app.use('/api', globalLimiter);
app.use('/api/auth', authLimiter);
app.use('/api', (req, res, next) => (req.method === 'GET' || req.method === 'HEAD' ? next() : writeLimiter(req, res, next)));

app.use(express.static(path.join(__dirname, 'public')));
app.use('/customer', express.static(path.join(__dirname, 'public', 'customer')));
app.use('/rider', express.static(path.join(__dirname, 'public', 'rider')));
app.use('/business', express.static(path.join(__dirname, 'public', 'business')));
app.use('/admin', express.static(path.join(__dirname, 'public', 'admin')));

// ─── Database Initialization ─────────────────────────────────
const db = new Database(runtime.dbPath);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');
db.pragma('busy_timeout = 5000');
db.pragma('synchronous = NORMAL');
db.pragma('wal_autocheckpoint = 1000');

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

for (const migration of [
  "ALTER TABLE deliveries ADD COLUMN preferred_rider_id TEXT REFERENCES users(id)",
  "ALTER TABLE deliveries ADD COLUMN dispatch_mode TEXT NOT NULL DEFAULT 'automatic'",
  "ALTER TABLE deliveries ADD COLUMN idempotency_actor_id TEXT",
  "ALTER TABLE deliveries ADD COLUMN idempotency_key TEXT",
  "ALTER TABLE deliveries ADD COLUMN idempotency_hash TEXT",
  "ALTER TABLE deliveries ADD COLUMN scheduled_for TEXT",
  "ALTER TABLE deliveries ADD COLUMN cancellation_fee REAL DEFAULT 0",
  "ALTER TABLE deliveries ADD COLUMN waiting_minutes INTEGER DEFAULT 0",
  "ALTER TABLE deliveries ADD COLUMN pod_reference TEXT",
  "ALTER TABLE users ADD COLUMN failed_login_count INTEGER DEFAULT 0",
  "ALTER TABLE users ADD COLUMN locked_until TEXT",
  "ALTER TABLE users ADD COLUMN otp_attempts INTEGER DEFAULT 0",
  "ALTER TABLE users ADD COLUMN last_login_at TEXT",
  "ALTER TABLE riders ADD COLUMN availability TEXT DEFAULT 'offline'",
  "ALTER TABLE riders ADD COLUMN accepted_offers INTEGER DEFAULT 0",
  "ALTER TABLE riders ADD COLUMN declined_offers INTEGER DEFAULT 0"
]) {
  try { db.exec(migration); } catch (error) {
    if (!String(error.message).includes('duplicate column name')) throw error;
  }
}
db.exec(`
  CREATE TABLE IF NOT EXISTS incidents (
    id TEXT PRIMARY KEY,
    delivery_id TEXT,
    reporter_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    severity TEXT NOT NULL DEFAULT 'medium',
    description TEXT,
    lat REAL,
    lng REAL,
    status TEXT NOT NULL DEFAULT 'open',
    resolution TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    resolved_at TEXT
  );

  CREATE INDEX IF NOT EXISTS idx_deliveries_preferred_rider ON deliveries(preferred_rider_id,status);
  CREATE INDEX IF NOT EXISTS idx_riders_dispatch_eligibility ON riders(approval_status,online_status,last_location_update);
  CREATE UNIQUE INDEX IF NOT EXISTS idx_deliveries_idempotency ON deliveries(idempotency_actor_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
  CREATE INDEX IF NOT EXISTS idx_deliveries_created ON deliveries(created_at);
  CREATE INDEX IF NOT EXISTS idx_deliveries_dest_phone ON deliveries(dest_phone);
  CREATE INDEX IF NOT EXISTS idx_delivery_events_delivery ON delivery_events(delivery_id,created_at);
  CREATE INDEX IF NOT EXISTS idx_rider_locations_delivery ON rider_locations(delivery_id,created_at);
  CREATE INDEX IF NOT EXISTS idx_payments_delivery ON payments(delivery_id);
  CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log(created_at);
  CREATE INDEX IF NOT EXISTS idx_incidents_status ON incidents(status,created_at);
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

function genOTP() { return String(crypto.randomInt(100000, 1000000)); }
function genOrderNo() { return 'MV' + Date.now().toString(36).toUpperCase() + crypto.randomBytes(3).toString('hex').toUpperCase(); }
function resOK(res, data, status=200) { res.status(status).json({ success: true, data }); }
function resErr(res, msg, status=400, code=null) {
  const payload = { success: false, error: msg };
  if (code) payload.code = code;
  res.status(status).json(payload);
}

/**
 * Wraps a route so thrown ValidationErrors become clean 4xx responses and any
 * other failure reaches the central error handler instead of hanging the request.
 */
function route(handler) {
  return (req, res, next) => {
    try {
      const result = handler(req, res, next);
      if (result && typeof result.catch === 'function') result.catch(next);
    } catch (error) {
      next(error);
    }
  };
}
function getConfig(key, def=null) { const r = db.prepare('SELECT value FROM pricing_config WHERE key=?').get(key); return r ? r.value : def; }

function normalizePhone(phone) {
  if (typeof phone !== 'string') return null;
  const compact = phone.trim().replace(/[\s()-]/g, '');
  let national;
  if (/^07\d{8}$/.test(compact)) national = compact;
  else if (/^2507\d{8}$/.test(compact)) national = `0${compact.slice(3)}`;
  else if (/^\+2507\d{8}$/.test(compact)) national = `0${compact.slice(4)}`;
  return national ? `+250${national.slice(1)}` : null;
}

db.function('normalize_phone', { deterministic: true }, normalizePhone);

for (const user of db.prepare('SELECT id,phone FROM users').all()) {
  const canonicalPhone = normalizePhone(user.phone);
  if (!canonicalPhone || canonicalPhone === user.phone) continue;
  const conflict = db.prepare('SELECT id FROM users WHERE phone=? AND id<>?').get(canonicalPhone, user.id);
  if (!conflict) db.prepare('UPDATE users SET phone=? WHERE id=?').run(canonicalPhone, user.id);
}
const canonicalAdminPhone = '+250780000000';
const maskedAdmin = db.prepare("SELECT id FROM users WHERE role='admin' AND phone='+250****0000'").get();
if (maskedAdmin && !db.prepare('SELECT id FROM users WHERE phone=? AND id<>?').get(canonicalAdminPhone, maskedAdmin.id)) {
  db.prepare('UPDATE users SET phone=? WHERE id=?').run(canonicalAdminPhone, maskedAdmin.id);
}

function isDeliveryReceiver(user, delivery) {
  const userPhone = normalizePhone(user?.phone);
  return user?.role === 'customer' && userPhone && userPhone === normalizePhone(delivery?.dest_phone);
}

function canAccessDelivery(user, delivery) {
  if (!user || !delivery) return false;
  if (user.role === 'admin') return true;
  if (user.role === 'rider') return delivery.rider_id === user.id;
  if (user.role === 'business') return delivery.business_id === user.id;
  return user.role === 'customer' && (delivery.customer_id === user.id || isDeliveryReceiver(user, delivery));
}

function deliveryRelationship(user, delivery) {
  if (user?.role !== 'customer') return user?.role || null;
  if (delivery?.customer_id === user.id) return 'sender';
  if (isDeliveryReceiver(user, delivery)) return 'receiver';
  return null;
}

function customerDeliveryView(user, delivery) {
  const view = { ...delivery, relationship: deliveryRelationship(user, delivery) };
  const sender = delivery.customer_id === user.id;
  const receiver = isDeliveryReceiver(user, delivery);
  if (!sender || delivery.pickup_verified_at || ['picked_up','in_transit','arrived_dest','delivered'].includes(delivery.status)) delete view.pickup_otp;
  if (!receiver || delivery.status !== 'arrived_dest' || delivery.delivery_verified_at) delete view.delivery_otp;
  return view;
}

function deliveryView(user, delivery) {
  return user.role === 'customer' ? customerDeliveryView(user, delivery) : delivery;
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`;
  return JSON.stringify(value);
}

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
  emitDeliveryUpdate(id, { status });
}

function notifyUser(userId, type, title, body, data=null) {
  db.prepare('INSERT INTO notifications (id,user_id,type,title,body,data) VALUES (?,?,?,?,?,?)')
    .run(uuidv4(), userId, type, title, body, data ? JSON.stringify(data) : null);
  emitToUser(userId, 'notification', { type, title, body, data });
}

function audit(userId, action, entity, entityId, details) {
  db.prepare('INSERT INTO audit_log (id,user_id,action,entity,entity_id,details) VALUES (?,?,?,?,?,?)')
    .run(uuidv4(), userId, action, entity, entityId, details ? JSON.stringify(details) : null);
}

/** One-time passwords leave through the configured SMS provider, never through the API response. */
function deliverOtp(phone, otp, purpose) {
  if (runtime.otpTestMode) return;
  messaging.sendSms(phone, `MOVO verification code: ${otp}. It expires in 10 minutes. Never share this code.`, { purpose })
    .catch(error => logger.error('otp_delivery_failed', { purpose, message: error.message }));
}

/**
 * Delivery milestones reach the recipient through the app when they hold a MOVO
 * account, and always through SMS — Kigali recipients often have neither the app
 * nor a data connection (spec §17).
 */
function notifyDeliveryParticipant(delivery, type, title, body) {
  if (!delivery?.dest_phone) return;
  const canonical = normalizePhone(delivery.dest_phone);
  const recipient = canonical ? db.prepare("SELECT id FROM users WHERE phone=? AND role='customer'").get(canonical) : null;
  if (recipient && recipient.id !== delivery.customer_id) {
    notifyUser(recipient.id, type, title, body, { delivery_id: delivery.id });
  }
  messaging.sendSms(delivery.dest_phone, body, { purpose: 'delivery_update', deliveryId: delivery.id })
    .catch(error => logger.warn('recipient_sms_failed', { deliveryId: delivery.id, message: error.message }));
}

// ─── Auth Middleware ─────────────────────────────────────────
function issueToken(user) {
  return jwt.sign({ id: user.id, role: user.role }, JWT_SECRET, { expiresIn: JWT_EXPIRY, issuer: 'movo', audience: 'movo-clients' });
}

function verifyToken(token) {
  return jwt.verify(token, JWT_SECRET, { issuer: 'movo', audience: 'movo-clients' });
}

function auth(req, res, next) {
  const hdr = req.headers.authorization;
  if (!hdr || !hdr.startsWith('Bearer ')) return resErr(res, 'Authentication required', 401, 'unauthenticated');
  try {
    const token = hdr.slice(7).trim();
    const payload = verifyToken(token);
    const user = db.prepare('SELECT id,phone,full_name,role,status FROM users WHERE id=?').get(payload.id);
    if (!user) return resErr(res, 'Account inactive or not found', 401, 'account_unavailable');
    if (user.status !== 'active') return resErr(res, `Account is ${user.status}`, 403, 'account_' + user.status);
    req.user = user;
    next();
  } catch(e) { resErr(res, 'Invalid or expired token', 401, 'invalid_token'); }
}

function roleAuth(...roles) {
  return (req, res, next) => {
    if (!roles.includes(req.user.role)) return resErr(res, 'Insufficient permissions', 403, 'forbidden');
    next();
  };
}

// ─── Credential-attack controls ──────────────────────────────
function lockoutState(user) {
  if (!user?.locked_until) return { locked: false };
  const until = new Date(`${user.locked_until.replace(' ', 'T')}Z`);
  if (Number.isNaN(until.getTime()) || until <= new Date()) return { locked: false };
  return { locked: true, until, minutes: Math.max(1, Math.ceil((until - new Date()) / 60000)) };
}

function registerFailedLogin(user) {
  const attempts = (user.failed_login_count || 0) + 1;
  if (attempts >= runtime.security.maxLoginAttempts) {
    db.prepare("UPDATE users SET failed_login_count=0, locked_until=datetime('now', ?) WHERE id=?")
      .run(`+${runtime.security.lockoutMinutes} minutes`, user.id);
    audit(user.id, 'account_locked', 'user', user.id, { reason: 'failed_login_threshold' });
    return true;
  }
  db.prepare('UPDATE users SET failed_login_count=? WHERE id=?').run(attempts, user.id);
  return false;
}

function clearLoginFailures(userId) {
  db.prepare("UPDATE users SET failed_login_count=0, locked_until=NULL, otp_attempts=0, last_login_at=datetime('now') WHERE id=?").run(userId);
}

function authProfile(user) {
  const profile = { id: user.id, phone: user.phone, full_name: user.full_name, role: user.role, email: user.email };
  if (user.role === 'rider') Object.assign(profile, db.prepare('SELECT * FROM riders WHERE user_id=?').get(user.id));
  if (user.role === 'business') Object.assign(profile, db.prepare('SELECT * FROM businesses WHERE user_id=?').get(user.id));
  delete profile.password; delete profile.otp_code;
  return profile;
}

// ─── AUTH ROUTES ─────────────────────────────────────────────
app.post('/api/auth/register', route((req, res) => {
  {
    const { phone, full_name, email, password, role, company_name, tax_id, national_id, license_number, motorcycle_plate } = req.body;
    if (!phone || !full_name || !role) return resErr(res, 'Phone, name, and role are required', 400, 'missing_field');
    const canonicalPhone = normalizePhone(phone);
    if (!canonicalPhone) return resErr(res, 'Enter a valid Rwanda mobile number', 400, 'invalid_phone');
    if (!['customer','rider','business'].includes(role)) return resErr(res, 'Invalid role', 400, 'unsupported_value');
    if (password) validate.password(password);
    if (role === 'business' && !company_name?.trim()) return resErr(res, 'Company name is required', 400, 'missing_field');
    if (role === 'rider' && (!national_id?.trim() || !license_number?.trim() || !motorcycle_plate?.trim())) return resErr(res, 'National ID, license number, and motorcycle plate are required', 400, 'missing_field');
    if (db.prepare('SELECT id FROM users WHERE phone=?').get(canonicalPhone)) return resErr(res, 'Phone number already registered', 409, 'phone_taken');
    const hash = password ? bcrypt.hashSync(password, BCRYPT_ROUNDS) : null;
    const otp = genOTP();
    const otpHash = bcrypt.hashSync(otp, BCRYPT_ROUNDS);
    const id = uuidv4();
    const create = db.transaction(() => {
      db.prepare("INSERT INTO users (id,phone,email,password,full_name,role,otp_code,otp_expires) VALUES (?,?,?,?,?,?,?,datetime('now','+10 minutes'))")
        .run(id, canonicalPhone, email || null, hash, full_name.trim(), role, otpHash);
      if (role === 'rider') db.prepare('INSERT INTO riders (user_id,national_id,license_number,motorcycle_plate) VALUES (?,?,?,?)').run(id, national_id.trim(), license_number.trim(), motorcycle_plate.trim().toUpperCase());
      if (role === 'business') db.prepare('INSERT INTO businesses (user_id,company_name,tax_id) VALUES (?,?,?)').run(id, company_name.trim(), tax_id?.trim() || null);
    });
    create();
    deliverOtp(canonicalPhone, otp, 'registration');
    const response = { id, phone: canonicalPhone, message: runtime.otpTestMode ? 'OTP generated for test mode' : 'OTP sent to your phone' };
    if (runtime.otpTestMode) response.otp = otp;
    resOK(res, response);
  }
}));

app.post('/api/auth/verify-otp', route((req, res) => {
  const { phone, otp } = req.body;
  if (!phone || !otp) return resErr(res, 'Phone and OTP required', 400, 'missing_field');
  const canonicalPhone = normalizePhone(phone);
  const user = canonicalPhone ? db.prepare('SELECT * FROM users WHERE phone=?').get(canonicalPhone) : null;
  if (!user) return resErr(res, 'User not found', 404, 'account_not_found');
  const lock = lockoutState(user);
  if (lock.locked) return resErr(res, `Too many attempts. Try again in ${lock.minutes} minute(s).`, 423, 'account_locked');
  if (!/^[0-9]{6}$/.test(otp)) return resErr(res, 'OTP must be six digits', 400, 'invalid_otp');
  if (!runtime.otpTestMode) {
    if ((user.otp_attempts || 0) >= runtime.security.maxOtpAttempts) {
      db.prepare("UPDATE users SET otp_code=NULL,otp_expires=NULL,otp_attempts=0,locked_until=datetime('now', ?) WHERE id=?")
        .run(`+${runtime.security.lockoutMinutes} minutes`, user.id);
      return resErr(res, 'Too many verification attempts. Request a new code shortly.', 429, 'otp_attempts_exceeded');
    }
    if (!user.otp_code || !bcrypt.compareSync(otp, user.otp_code)) {
      db.prepare('UPDATE users SET otp_attempts=COALESCE(otp_attempts,0)+1 WHERE id=?').run(user.id);
      return resErr(res, 'Invalid OTP', 400, 'invalid_otp');
    }
    if (new Date(`${String(user.otp_expires).replace(' ', 'T')}Z`) < new Date()) return resErr(res, 'OTP expired', 400, 'otp_expired');
  }
  db.prepare("UPDATE users SET otp_code=NULL,otp_expires=NULL,otp_attempts=0,status='active' WHERE id=?").run(user.id);
  clearLoginFailures(user.id);
  audit(user.id, 'otp_verified', 'user', user.id, null);
  resOK(res, { token: issueToken(user), user: authProfile(user) });
}));

app.post('/api/auth/login', route((req, res) => {
  const { phone, password } = req.body;
  if (!phone) return resErr(res, 'Phone number required', 400, 'missing_field');
  const canonicalPhone = normalizePhone(phone);
  const user = canonicalPhone ? db.prepare('SELECT * FROM users WHERE phone=?').get(canonicalPhone) : null;
  if (!user) return resErr(res, 'Account not found', 404, 'account_not_found');
  const lock = lockoutState(user);
  if (lock.locked) return resErr(res, `Account temporarily locked. Try again in ${lock.minutes} minute(s).`, 423, 'account_locked');
  if (password && user.password) {
    if (!bcrypt.compareSync(password, user.password)) {
      const locked = registerFailedLogin(user);
      audit(user.id, 'login_failed', 'user', user.id, { locked });
      return locked
        ? resErr(res, `Account locked for ${runtime.security.lockoutMinutes} minutes after repeated failures.`, 423, 'account_locked')
        : resErr(res, 'Invalid password', 401, 'invalid_credentials');
    }
  } else {
    const otp = genOTP();
    const otpHash = bcrypt.hashSync(otp, BCRYPT_ROUNDS);
    db.prepare("UPDATE users SET otp_code=?,otp_expires=datetime('now','+10 minutes'),otp_attempts=0 WHERE id=?").run(otpHash, user.id);
    deliverOtp(canonicalPhone, otp, 'login');
    const response = { requires_otp: true, phone: canonicalPhone, message: runtime.otpTestMode ? 'OTP generated for test mode' : 'OTP sent to your phone' };
    if (runtime.otpTestMode) response.otp = otp;
    return resOK(res, response);
  }
  if (user.status !== 'active') return resErr(res, 'Account is ' + user.status, 403, 'account_' + user.status);
  clearLoginFailures(user.id);
  audit(user.id, 'login_succeeded', 'user', user.id, null);
  resOK(res, { token: issueToken(user), user: authProfile(user) });
}));

// Password change keeps the account-security baseline reachable from every client.
app.post('/api/auth/change-password', auth, route((req, res) => {
  const { current_password, new_password } = req.body;
  const user = db.prepare('SELECT * FROM users WHERE id=?').get(req.user.id);
  if (user.password && !bcrypt.compareSync(current_password || '', user.password)) {
    return resErr(res, 'Current password is incorrect', 401, 'invalid_credentials');
  }
  validate.password(new_password, 'new_password');
  db.prepare("UPDATE users SET password=?,updated_at=datetime('now') WHERE id=?").run(bcrypt.hashSync(new_password, BCRYPT_ROUNDS), user.id);
  audit(user.id, 'password_changed', 'user', user.id, null);
  resOK(res, { message: 'Password updated' });
}));

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

// Riders pick one of four availability states (spec §7.3). Only `online` riders
// receive offers; `busy` is set by the platform while a delivery is in flight.
const RIDER_AVAILABILITY = ['online', 'busy', 'unavailable', 'offline'];

app.put('/api/rider/status', auth, roleAuth('rider'), route((req, res) => {
  const rider = db.prepare('SELECT approval_status,online_status FROM riders WHERE user_id=?').get(req.user.id);
  if (!rider) return resErr(res, 'Rider profile not found', 404, 'rider_not_found');
  if (rider.approval_status !== 'approved') return resErr(res, 'Rider not yet approved', 403, 'rider_not_approved');
  const requested = req.body.status !== undefined
    ? validate.oneOf(req.body.status, RIDER_AVAILABILITY, 'status')
    : (req.body.online ? 'online' : 'offline');
  const active = db.prepare("SELECT id FROM deliveries WHERE rider_id=? AND status NOT IN ('delivered','cancelled','failed')").get(req.user.id);
  if (active && requested !== 'busy') return resErr(res, 'Finish or hand back your active delivery before changing availability', 409, 'active_delivery');
  db.prepare("UPDATE riders SET online_status=?,availability=?,updated_at=datetime('now') WHERE user_id=?").run(requested, requested, req.user.id);
  audit(req.user.id, 'rider_availability_changed', 'rider', req.user.id, { from: rider.online_status, to: requested });
  resOK(res, { online_status: requested, availability: requested, accepting_offers: requested === 'online' });
}));

// ─── Rider safety: incidents and SOS (spec §7.10) ────────────
const INCIDENT_KINDS = ['accident', 'unsafe_item', 'suspicious_customer', 'theft', 'vehicle_breakdown', 'harassment', 'sos', 'other'];

app.post('/api/rider/incidents', auth, roleAuth('rider'), route((req, res) => {
  const kind = validate.oneOf(req.body.kind, INCIDENT_KINDS, 'kind');
  const severity = validate.oneOf(req.body.severity, ['low', 'medium', 'high', 'critical'], 'severity', { optional: true }) || (kind === 'sos' ? 'critical' : 'medium');
  const description = validate.optionalString(req.body.description, 'description', { max: 2000 });
  const deliveryId = validate.optionalString(req.body.delivery_id, 'delivery_id', { max: 64 });
  if (deliveryId) {
    const delivery = db.prepare('SELECT id FROM deliveries WHERE id=? AND rider_id=?').get(deliveryId, req.user.id);
    if (!delivery) return resErr(res, 'Delivery not found for this rider', 404, 'delivery_not_found');
  }
  const lat = req.body.lat === undefined ? null : validate.latitude(req.body.lat);
  const lng = req.body.lng === undefined ? null : validate.longitude(req.body.lng);
  const id = uuidv4();
  db.prepare('INSERT INTO incidents (id,delivery_id,reporter_id,kind,severity,description,lat,lng) VALUES (?,?,?,?,?,?,?,?)')
    .run(id, deliveryId, req.user.id, kind, severity, description, lat, lng);
  audit(req.user.id, 'incident_reported', 'incident', id, { kind, severity, deliveryId });
  // Operations sees SOS immediately; a ticket keeps it in the support workflow too.
  const ticketId = uuidv4();
  db.prepare('INSERT INTO tickets (id,delivery_id,reporter_id,category,subject,description,priority) VALUES (?,?,?,?,?,?,?)')
    .run(ticketId, deliveryId, req.user.id, 'incident', `Rider ${kind.replace('_', ' ')} report`, description || `Rider reported ${kind}`, severity === 'critical' ? 'high' : severity);
  for (const admin of db.prepare("SELECT id FROM users WHERE role='admin' AND status='active'").all()) {
    notifyUser(admin.id, 'incident', severity === 'critical' ? 'SOS from a rider' : 'Rider incident reported',
      `${req.user.full_name} reported ${kind.replace('_', ' ')}`, { incident_id: id, delivery_id: deliveryId, severity });
  }
  logger.warn('rider_incident', { incidentId: id, kind, severity, riderId: req.user.id, deliveryId });
  resOK(res, { id, ticket_id: ticketId, kind, severity, status: 'open', message: 'MOVO operations has been alerted' }, 201);
}));

app.get('/api/rider/incidents', auth, roleAuth('rider'), route((req, res) => {
  resOK(res, db.prepare('SELECT * FROM incidents WHERE reporter_id=? ORDER BY created_at DESC LIMIT 50').all(req.user.id));
}));

// ─── Rider offer feed (spec §7.4) ────────────────────────────
app.get('/api/mobile/v1/rider/offers', auth, roleAuth('rider'), route((req, res) => {
  expireSelectedOffers();
  const offers = db.prepare(`SELECT o.id AS offer_id,o.expires_at,o.created_at,
      d.id,d.order_no,d.service_type,d.pickup_address,d.pickup_lat,d.pickup_lng,
      d.dest_address,d.dest_lat,d.dest_lng,d.rider_earnings,d.distance_km,d.item_description,d.special_instructions
    FROM delivery_offers o JOIN deliveries d ON d.id=o.delivery_id
    WHERE o.rider_id=? AND o.status='offered' AND o.expires_at>datetime('now') ORDER BY o.expires_at`).all(req.user.id);
  // Sensitive sender/recipient identity is withheld until the rider accepts.
  resOK(res, { offers, serverTime: new Date().toISOString() });
}));

app.put('/api/rider/location', auth, roleAuth('rider'), (req, res) => {
  const { lat, lng } = req.body;
  if (!Number.isFinite(Number(lat)) || !Number.isFinite(Number(lng)) || Math.abs(Number(lat)) > 90 || Math.abs(Number(lng)) > 180) return resErr(res, 'Valid latitude and longitude required');
  const rider = db.prepare('SELECT approval_status,online_status FROM riders WHERE user_id=?').get(req.user.id);
  if (!rider || rider.approval_status !== 'approved' || !['online','busy'].includes(rider.online_status)) return resErr(res, 'Approved online rider required', 403);
  db.prepare('UPDATE riders SET current_lat=?,current_lng=?,last_location_update=datetime(\'now\') WHERE user_id=?')
    .run(lat, lng, req.user.id);
  const active = db.prepare("SELECT id FROM deliveries WHERE rider_id=? AND status IN ('assigned','going_pickup','arrived_pickup','picked_up','in_transit','arrived_dest') ORDER BY updated_at DESC LIMIT 1").get(req.user.id);
  db.prepare('INSERT INTO rider_locations (id,rider_id,delivery_id,lat,lng) VALUES (?,?,?,?,?)').run(uuidv4(), req.user.id, active?.id || null, lat, lng);
  if (active) io.to(`delivery:${active.id}`).emit('rider_location', { delivery_id: active.id, lat: Number(lat), lng: Number(lng) });
  resOK(res, { message: 'Location updated' });
});

app.get('/api/mobile/v1/rider/home', auth, roleAuth('rider'), (req, res) => {
  expireSelectedOffers();
  const rider = db.prepare("SELECT r.approval_status,r.online_status,r.last_location_update,r.total_deliveries,r.total_earnings,r.avg_rating,r.rating_count,r.profile_photo,r.motorcycle_plate,r.motorcycle_make,r.motorcycle_type,r.motorcycle_color,u.full_name FROM riders r JOIN users u ON u.id=r.user_id WHERE r.user_id=?").get(req.user.id);
  const activeDelivery = db.prepare("SELECT * FROM deliveries WHERE rider_id=? AND status IN ('assigned','going_pickup','arrived_pickup','picked_up','in_transit','arrived_dest') ORDER BY updated_at DESC LIMIT 1").get(req.user.id) || null;
  const offers = db.prepare(`SELECT o.id as offer_id,o.expires_at,d.id,d.order_no,d.service_type,d.pickup_address,d.pickup_lat,d.pickup_lng,d.pickup_name,d.pickup_phone,d.dest_address,d.dest_lat,d.dest_lng,d.dest_name,d.dest_phone,d.rider_earnings,d.distance_km
    FROM delivery_offers o JOIN deliveries d ON d.id=o.delivery_id WHERE o.rider_id=? AND o.status='offered' AND o.expires_at>datetime('now') ORDER BY o.expires_at`).all(req.user.id);
  resOK(res, { ...rider, profile_photo_url: rider.profile_photo ? `/api/rider/documents/${req.user.id}/profile` : null, profile_photo: undefined, activeDelivery, offers, serverTime: new Date().toISOString() });
});

app.put('/api/mobile/v1/rider/offers/:offerId/decline', auth, roleAuth('rider'), (req, res) => {
  const offer = db.prepare("SELECT o.id,o.delivery_id,d.dispatch_mode FROM delivery_offers o JOIN deliveries d ON d.id=o.delivery_id WHERE o.id=? AND o.rider_id=? AND o.status='offered' AND o.expires_at>datetime('now')").get(req.params.offerId, req.user.id);
  if (!offer) return resErr(res, 'Offer is unavailable', 409);
  db.transaction(() => {
    db.prepare("UPDATE delivery_offers SET status='declined',responded_at=datetime('now') WHERE id=?").run(offer.id);
    db.prepare('UPDATE riders SET declined_offers=COALESCE(declined_offers,0)+1 WHERE user_id=?').run(req.user.id);
    if (offer.dispatch_mode === 'selected') updateDeliveryStatus(offer.delivery_id, 'awaiting_rider_selection', { note: 'Selected rider declined' });
  })();
  if (offer.dispatch_mode === 'selected') emitDeliveryUpdate(offer.delivery_id, { status: 'awaiting_rider_selection' });
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

function expireSelectedOffers() {
  const transitioned = db.transaction(() => {
    db.prepare("UPDATE delivery_offers SET status='expired',responded_at=COALESCE(responded_at,datetime('now')) WHERE status='offered' AND expires_at<=datetime('now')").run();
    const candidates = db.prepare(`SELECT d.id FROM deliveries d
      WHERE d.dispatch_mode='selected' AND d.status='searching'
        AND NOT EXISTS (SELECT 1 FROM delivery_offers o WHERE o.delivery_id=d.id AND o.status='offered' AND o.expires_at>datetime('now'))`).all();
    const ids = [];
    const transition = db.prepare("UPDATE deliveries SET status='awaiting_rider_selection',updated_at=datetime('now') WHERE id=? AND dispatch_mode='selected' AND status='searching'");
    for (const candidate of candidates) {
      if (!transition.run(candidate.id).changes) continue;
      addEvent(candidate.id, 'awaiting_rider_selection', null, null, 'Selected rider offer expired');
      ids.push(candidate.id);
    }
    return ids;
  })();
  for (const deliveryId of transitioned) emitDeliveryUpdate(deliveryId, { status: 'awaiting_rider_selection' });
  return transitioned;
}

function customerDeliveries(user) {
  expireSelectedOffers();
  const canonicalPhone = normalizePhone(user.phone);
  return db.prepare(`SELECT * FROM deliveries
    WHERE customer_id=? OR (? IS NOT NULL AND normalize_phone(dest_phone)=?)
    ORDER BY created_at DESC LIMIT 200`).all(user.id, canonicalPhone, canonicalPhone);
}

function eligibleNearbyRiders(lat, lng, radiusKm) {
  const freshnessSeconds = Math.max(1, parseInt(getConfig('rider_location_freshness_sec', '120'), 10) || 120);
  const riders = db.prepare(`SELECT u.id,u.full_name,r.avg_rating,r.motorcycle_plate,r.motorcycle_make,r.motorcycle_type,r.motorcycle_color,
    r.current_lat,r.current_lng,r.last_location_update,
    (SELECT COUNT(*) FROM deliveries d WHERE d.rider_id=r.user_id AND d.status NOT IN ('delivered','cancelled','failed')) AS active_count
    FROM users u JOIN riders r ON r.user_id=u.id
    WHERE u.status='active' AND r.approval_status='approved' AND r.online_status='online'
      AND r.current_lat BETWEEN -90 AND 90 AND r.current_lng BETWEEN -180 AND 180
      AND r.last_location_update >= datetime('now', ?)`)
    .all(`-${freshnessSeconds} seconds`);
  return riders
    .filter(rider => rider.active_count === 0)
    .map(rider => ({ ...rider, distance_km: Math.round(haversine(lat, lng, rider.current_lat, rider.current_lng) * 100) / 100 }))
    .filter(rider => rider.distance_km <= radiusKm)
    .sort((a, b) => a.distance_km - b.distance_km)
    .map(({ active_count, ...rider }) => rider);
}

app.get('/api/mobile/v1/customer/nearby-riders', auth, roleAuth('customer'), (req, res) => {
  const lat = Number(req.query.lat);
  const lng = Number(req.query.lng);
  const requestedRadius = req.query.radius_km === undefined ? 5 : Number(req.query.radius_km);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180)
    return resErr(res, 'Valid latitude and longitude required');
  if (!Number.isFinite(requestedRadius) || requestedRadius <= 0) return resErr(res, 'Valid radius required');
  const radiusKm = Math.min(requestedRadius, 20);
  resOK(res, { riders: eligibleNearbyRiders(lat, lng, radiusKm), radius_km: radiusKm, serverTime: new Date().toISOString() });
});

app.get('/api/mobile/v1/customer/home', auth, roleAuth('customer'), (req, res) => {
  const deliveries = customerDeliveries(req.user);
  const profile = db.prepare('SELECT id,phone,full_name,email,avatar,role FROM users WHERE id=?').get(req.user.id);
  const terminal = new Set(['delivered','cancelled','failed']);
  const sent = deliveries.filter(delivery => delivery.customer_id === req.user.id);
  const received = deliveries.filter(delivery => isDeliveryReceiver(req.user, delivery));
  const map = rows => rows.map(delivery => customerDeliveryView(req.user, delivery));
  resOK(res, {
    profile,
    activeSent: map(sent.filter(delivery => !terminal.has(delivery.status))),
    activeReceived: map(received.filter(delivery => !terminal.has(delivery.status))),
    recentSent: map(sent.filter(delivery => terminal.has(delivery.status)).slice(0, 20)),
    recentReceived: map(received.filter(delivery => terminal.has(delivery.status)).slice(0, 20)),
    serverTime: new Date().toISOString()
  });
});

app.get('/api/mobile/v1/customer/deliveries', auth, roleAuth('customer'), (req, res) => {
  const role = req.query.role || 'all';
  if (!['sent','received','all'].includes(role)) return resErr(res, 'role must be sent, received, or all');
  const deliveries = customerDeliveries(req.user).filter(delivery =>
    role === 'all' || (role === 'sent' ? delivery.customer_id === req.user.id : isDeliveryReceiver(req.user, delivery))
  );
  resOK(res, { deliveries: deliveries.map(delivery => customerDeliveryView(req.user, delivery)), serverTime: new Date().toISOString() });
});

/**
 * Creates one delivery from a validated request body and starts dispatch.
 * Shared by the single-request endpoint and the business bulk upload so both
 * paths apply identical pricing, verification codes and dispatch rules.
 */
function createDelivery(actor, body, { idempotencyKey = null, idempotencyHash = null } = {}) {
  const {
    service_type, pickup_address, pickup_lat, pickup_lng, pickup_name, pickup_phone, pickup_instructions,
    dest_address, dest_lat, dest_lng, dest_name, dest_phone, dest_instructions,
    item_description, item_weight, item_category, special_instructions,
    payment_method, preferred_time, business_ref, department, preferred_rider_id, scheduled_for
  } = body;

  if (!pickup_address || !dest_address || !pickup_name || !pickup_phone || !dest_name || !dest_phone) {
    throw new ValidationError('Pickup and destination details are required', { code: 'missing_field' });
  }
  validate.oneOf(service_type, ['parcel', 'document'], 'service_type');
  const pickupLat = validate.latitude(pickup_lat, 'pickup_lat');
  const pickupLng = validate.longitude(pickup_lng, 'pickup_lng');
  const destLat = validate.latitude(dest_lat, 'dest_lat');
  const destLng = validate.longitude(dest_lng, 'dest_lng');
  const pickupPhone = validate.requiredPhone(pickup_phone, 'pickup_phone');
  const destPhone = validate.requiredPhone(dest_phone, 'dest_phone');

  const price = calcPrice(pickupLat, pickupLng, destLat, destLng, service_type);
  if (!price) throw new ValidationError('Cannot calculate price for this route', { code: 'route_unsupported' });
  if (preferred_rider_id) {
    const selectionRadius = Math.min(20, parseFloat(getConfig('rider_search_radius_km', '5')) || 5);
    const eligible = eligibleNearbyRiders(pickupLat, pickupLng, selectionRadius).some(rider => rider.id === preferred_rider_id);
    if (!eligible) throw new ValidationError('Selected rider is not eligible or nearby', { code: 'rider_unavailable', status: 409 });
  }

  // A scheduled request waits in the queue until its window opens (spec §8.4).
  const scheduledFor = validate.optionalString(scheduled_for, 'scheduled_for', { max: 40 });
  const scheduledDate = scheduledFor ? new Date(scheduledFor) : null;
  if (scheduledFor && Number.isNaN(scheduledDate?.getTime())) throw new ValidationError('scheduled_for must be an ISO timestamp', { code: 'invalid_timestamp', field: 'scheduled_for' });
  const isScheduled = Boolean(scheduledDate && scheduledDate.getTime() > Date.now() + 60_000);

  const id = uuidv4();
  const orderNo = genOrderNo();
  const pickupOtp = genOTP().slice(0, 4);
  const deliveryOtp = genOTP().slice(0, 4);

  db.prepare(`INSERT INTO deliveries (
    id,order_no,customer_id,business_id,service_type,status,
    pickup_address,pickup_lat,pickup_lng,pickup_name,pickup_phone,pickup_instructions,pickup_otp,
    dest_address,dest_lat,dest_lng,dest_name,dest_phone,dest_instructions,delivery_otp,
    item_description,item_weight,item_category,special_instructions,
    origin_zone,dest_zone,distance_km,customer_price,rider_earnings,platform_fee,total_charge,
    payment_method,preferred_time,business_ref,department,preferred_rider_id,dispatch_mode,scheduled_for,
    idempotency_actor_id,idempotency_key,idempotency_hash
  ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`).run(
    id, orderNo, actor.id, actor.role === 'business' ? actor.id : null, service_type, isScheduled ? 'scheduled' : 'created',
    pickup_address, pickupLat, pickupLng, pickup_name, pickupPhone, pickup_instructions || null, pickupOtp,
    dest_address, destLat, destLng, dest_name, destPhone, dest_instructions || null, deliveryOtp,
    item_description || null, item_weight || null, item_category || null, special_instructions || null,
    price.originZone.name, price.destZone.name, price.distance_km,
    price.customerPrice, price.riderEarnings, price.platformFee, price.totalCharge,
    payment_method || 'mobile_money', preferred_time || null, business_ref || null, department || null,
    preferred_rider_id || null, preferred_rider_id ? 'selected' : 'automatic', isScheduled ? scheduledDate.toISOString() : null,
    idempotencyKey ? actor.id : null, idempotencyKey, idempotencyHash
  );
  addEvent(id, isScheduled ? 'scheduled' : 'created', pickupLat, pickupLng, isScheduled ? `Scheduled for ${scheduledDate.toISOString()}` : 'Delivery requested');

  if (!isScheduled) {
    if (preferred_rider_id) dispatchSelectedDelivery(id, preferred_rider_id);
    else dispatchDelivery(id);
  }
  return { id, scheduled: isScheduled, delivery: db.prepare('SELECT * FROM deliveries WHERE id=?').get(id) };
}

app.post('/api/deliveries', auth, roleAuth('customer','business'), route((req, res) => {
  const rawIdempotencyKey = req.get('Idempotency-Key');
  const idempotencyKey = rawIdempotencyKey?.trim() || null;
  if (idempotencyKey && idempotencyKey.length > 128) return resErr(res, 'Idempotency-Key is too long', 400, 'idempotency_key_too_long');
  const idempotencyHash = idempotencyKey ? crypto.createHash('sha256').update(stableJson(req.body)).digest('hex') : null;
  if (idempotencyKey) {
    const existing = db.prepare('SELECT * FROM deliveries WHERE idempotency_actor_id=? AND idempotency_key=?').get(req.user.id, idempotencyKey);
    if (existing) {
      if (existing.idempotency_hash !== idempotencyHash) return resErr(res, 'Idempotency-Key was already used with a different request', 409, 'idempotency_conflict');
      return resOK(res, { delivery: deliveryView(req.user, existing), message: 'Delivery already created' });
    }
  }
  const created = createDelivery(req.user, req.body, { idempotencyKey, idempotencyHash });
  resOK(res, {
    delivery: deliveryView(req.user, created.delivery),
    message: created.scheduled ? 'Delivery scheduled' : 'Delivery created, searching for rider...'
  }, 201);
}));

/** Bulk upload for business logistics teams (spec §8.4) — partial success is reported per row. */
app.post('/api/business/deliveries/bulk', auth, roleAuth('business'), route((req, res) => {
  const rows = Array.isArray(req.body.deliveries) ? req.body.deliveries : null;
  if (!rows || rows.length === 0) return resErr(res, 'Provide a deliveries array', 400, 'missing_field');
  if (rows.length > 50) return resErr(res, 'A bulk upload carries at most 50 deliveries', 400, 'batch_too_large');
  const created = [];
  const rejected = [];
  rows.forEach((row, index) => {
    try {
      const result = createDelivery(req.user, row);
      created.push({ index, id: result.id, order_no: result.delivery.order_no, status: result.delivery.status, price: result.delivery.total_charge });
    } catch (error) {
      rejected.push({ index, error: error.message, code: error.code || 'invalid_request', reference: row.business_ref || null });
    }
  });
  audit(req.user.id, 'bulk_deliveries_created', 'business', req.user.id, { requested: rows.length, created: created.length });
  resOK(res, { created, rejected, summary: { requested: rows.length, created: created.length, rejected: rejected.length } }, created.length ? 201 : 400);
}));

app.put('/api/deliveries/:id/select-rider', auth, roleAuth('customer','business'), (req, res) => {
  expireSelectedOffers();
  const delivery = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!delivery || (delivery.customer_id !== req.user.id && delivery.business_id !== req.user.id)) return resErr(res, 'Not found', 404);
  if (delivery.status !== 'awaiting_rider_selection') return resErr(res, 'Delivery is not awaiting rider selection', 409);
  if (!req.body.preferred_rider_id) return resErr(res, 'preferred_rider_id is required');
  try {
    dispatchSelectedDelivery(delivery.id, req.body.preferred_rider_id);
    resOK(res, { delivery: deliveryView(req.user, db.prepare('SELECT * FROM deliveries WHERE id=?').get(delivery.id)) });
  } catch (error) { resErr(res, error.message, 409); }
});

app.get('/api/deliveries', auth, route((req, res) => {
  const { limit, offset } = validate.pagination(req.query, { defaultLimit: 50, maxLimit: 100 });
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
  if (req.query.status) { query += ' AND status=?'; params.push(String(req.query.status).slice(0, 40)); }
  query += ' ORDER BY created_at DESC LIMIT ? OFFSET ?';
  params.push(limit, offset);
  resOK(res, db.prepare(query).all(...params).map(delivery => deliveryView(req.user, delivery)));
}));

app.get('/api/deliveries/:id', auth, (req, res) => {
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!d) return resErr(res, 'Delivery not found', 404);
  if (!canAccessDelivery(req.user, d)) return resErr(res, 'Not found', 404);
  const events = db.prepare('SELECT * FROM delivery_events WHERE delivery_id=? ORDER BY created_at').all(d.id);
  resOK(res, { delivery: deliveryView(req.user, d), events });
});

app.get('/api/deliveries/:id/track', auth, (req, res) => {
  expireSelectedOffers();
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!d) return resErr(res, 'Delivery not found', 404);
  if (!canAccessDelivery(req.user, d)) return resErr(res, 'Not found', 404);
  const events = db.prepare('SELECT * FROM delivery_events WHERE delivery_id=? ORDER BY created_at').all(d.id);
  let riderLoc = d.rider_id ? db.prepare('SELECT lat,lng,created_at FROM rider_locations WHERE rider_id=? AND delivery_id=? ORDER BY created_at DESC LIMIT 1').get(d.rider_id, d.id) : null;
  if (!riderLoc && d.rider_id && !['delivered','cancelled','failed'].includes(d.status)) {
    riderLoc = db.prepare('SELECT current_lat AS lat,current_lng AS lng,last_location_update AS created_at FROM riders WHERE user_id=? AND current_lat BETWEEN -90 AND 90 AND current_lng BETWEEN -180 AND 180').get(d.rider_id) || null;
  }
  const rider = d.rider_id ? db.prepare('SELECT u.full_name,u.avatar,u.phone,r.motorcycle_plate,r.motorcycle_make,r.motorcycle_color,r.avg_rating FROM users u JOIN riders r ON u.id=r.user_id WHERE u.id=?').get(d.rider_id) : null;
  resOK(res, {
    delivery: deliveryView(req.user, d),
    events,
    riderLocation: riderLoc,
    rider,
    relationship: deliveryRelationship(req.user, d),
    serverTime: new Date().toISOString()
  });
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
  db.prepare("UPDATE riders SET online_status='busy',availability='busy',accepted_offers=COALESCE(accepted_offers,0)+1 WHERE user_id=?").run(req.user.id);
  notifyUser(d.customer_id, 'rider_assigned', 'Rider Assigned', `A rider has been assigned to your delivery ${d.order_no}`, { delivery_id: d.id });
  notifyDeliveryParticipant(d, 'rider_assigned', 'Delivery on the way', `A MOVO rider is collecting a ${d.service_type} addressed to you (${d.order_no}).`);
  emitDeliveryUpdate(d.id, { status: 'assigned', rider_id: req.user.id });
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
  // The recipient — who may have no MOVO account — receives the handover code by SMS.
  notifyDeliveryParticipant(d, 'rider_arrived_dest', 'Your MOVO delivery has arrived',
    `Your MOVO rider has arrived for ${d.order_no}. Share code ${d.delivery_otp} only after receiving your item.`);
  resOK(res, { message: 'Arrived at destination' });
});

app.put('/api/deliveries/:id/complete', auth, roleAuth('rider'), route((req, res) => {
  const d = db.prepare("SELECT * FROM deliveries WHERE id=? AND rider_id=? AND status='arrived_dest'").get(req.params.id, req.user.id);
  if (!d) return resErr(res, 'Delivery not found', 404, 'delivery_not_found');
  const { otp, photo, recipient_name, notes, signature } = req.body;
  if (d.delivery_otp && otp !== d.delivery_otp) return resErr(res, 'Invalid delivery OTP', 400, 'invalid_otp');
  const now = new Date().toISOString();
  const podReference = `POD-${d.order_no}`;
  const settle = db.transaction(() => {
    updateDeliveryStatus(d.id, 'delivered', {
      delivery_verified_at: now, delivery_photo: photo || d.delivery_photo || null,
      recipient_name: recipient_name || d.dest_name, delivery_notes: notes || null,
      delivered_at: now, payment_status: 'paid', paid_at: now
    });
    db.prepare("UPDATE deliveries SET pod_reference=? WHERE id=?").run(podReference, d.id);
    if (signature) db.prepare("UPDATE deliveries SET delivery_notes=COALESCE(delivery_notes,'') || ? WHERE id=?")
      .run(` | signed: ${String(signature).slice(0, 120)}`, d.id);
    db.prepare("UPDATE riders SET total_deliveries=total_deliveries+1, total_earnings=total_earnings+?, online_status='online', availability='online', updated_at=datetime('now') WHERE user_id=?")
      .run(d.rider_earnings, req.user.id);
    // Settlement split (spec §10 stage 9): customer charge, rider payout, platform fee.
    const payment = db.prepare('INSERT INTO payments (id,delivery_id,user_id,amount,type,method,status,processed_at) VALUES (?,?,?,?,?,?,?,?)');
    payment.run(uuidv4(), d.id, req.user.id, d.rider_earnings, 'rider_payout', 'mobile_money', 'completed', now);
    payment.run(uuidv4(), d.id, d.customer_id, d.total_charge, 'customer_pay', d.payment_method, 'completed', now);
    payment.run(uuidv4(), d.id, d.customer_id, d.platform_fee, 'platform_fee', d.payment_method, 'completed', now);
  });
  settle();
  notifyUser(d.customer_id, 'delivered', 'Delivery Completed', `Your ${d.service_type} has been delivered! Order: ${d.order_no}`, { delivery_id: d.id, pod_reference: podReference });
  notifyDeliveryParticipant(d, 'delivered', 'Delivery completed', `Your MOVO ${d.service_type} (${d.order_no}) was delivered. Proof of delivery: ${podReference}.`);
  audit(req.user.id, 'delivery_completed', 'delivery', d.id, { pod: podReference });
  resOK(res, { message: 'Delivery completed', pod_reference: podReference });
}));

app.put('/api/deliveries/:id/cancel', auth, route((req, res) => {
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!d) return resErr(res, 'Delivery not found', 404, 'delivery_not_found');
  if (['delivered','cancelled','failed'].includes(d.status)) return resErr(res, 'Cannot cancel this delivery', 409, 'invalid_state');
  const isCustomer = req.user.id === d.customer_id || req.user.id === d.business_id;
  const canCancel = (isCustomer && ['scheduled','created','searching','awaiting_rider_selection','assigned'].includes(d.status)) ||
                    (req.user.id === d.rider_id && ['assigned','going_pickup'].includes(d.status)) ||
                    req.user.role === 'admin';
  if (!canCancel) return resErr(res, 'Cannot cancel at this stage', 409, 'invalid_state');

  // A rider was already committed, so a late customer cancellation carries the
  // configured fee (spec §9.7). Cancelling before assignment is always free.
  const feeApplies = isCustomer && ['assigned'].includes(d.status);
  const cancellationFee = feeApplies ? Number(getConfig('cancel_fee_customer', '0')) || 0 : 0;
  const now = new Date().toISOString();
  db.transaction(() => {
    updateDeliveryStatus(d.id, 'cancelled', {
      cancelled_at: now,
      cancel_reason: validate.optionalString(req.body.reason, 'reason', { max: 240 }) || `Cancelled by ${req.user.role}`,
      cancelled_by: req.user.role
    });
    db.prepare('UPDATE deliveries SET cancellation_fee=? WHERE id=?').run(cancellationFee, d.id);
    db.prepare("UPDATE delivery_offers SET status='expired',responded_at=datetime('now') WHERE delivery_id=? AND status='offered'").run(d.id);
    if (cancellationFee > 0) {
      db.prepare('INSERT INTO payments (id,delivery_id,user_id,amount,type,method,status,processed_at) VALUES (?,?,?,?,?,?,?,?)')
        .run(uuidv4(), d.id, d.customer_id, cancellationFee, 'customer_pay', d.payment_method, 'completed', now);
    }
  })();
  if (d.rider_id) {
    db.prepare("UPDATE riders SET online_status='online',availability='online' WHERE user_id=?").run(d.rider_id);
    notifyUser(d.rider_id, 'delivery_cancelled', 'Delivery Cancelled', `Delivery ${d.order_no} has been cancelled`);
  }
  notifyUser(d.customer_id, 'delivery_cancelled', 'Delivery Cancelled',
    cancellationFee > 0
      ? `Delivery ${d.order_no} was cancelled. A ${cancellationFee} RWF cancellation fee applies.`
      : `Your delivery ${d.order_no} has been cancelled`);
  audit(req.user.id, 'delivery_cancelled', 'delivery', d.id, { by: req.user.role, fee: cancellationFee });
  resOK(res, { message: 'Delivery cancelled', cancellation_fee: cancellationFee });
}));

// ─── Proof of delivery (spec §13.9) ──────────────────────────
app.get('/api/deliveries/:id/pod', auth, route((req, res) => {
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!d) return resErr(res, 'Delivery not found', 404, 'delivery_not_found');
  if (!canAccessDelivery(req.user, d)) return resErr(res, 'Not found', 404, 'delivery_not_found');
  if (d.status !== 'delivered') return resErr(res, 'Proof of delivery is available once the delivery is completed', 409, 'invalid_state');
  const rider = d.rider_id ? db.prepare('SELECT u.full_name,r.motorcycle_plate FROM users u JOIN riders r ON r.user_id=u.id WHERE u.id=?').get(d.rider_id) : null;
  const events = db.prepare("SELECT status,lat,lng,created_at FROM delivery_events WHERE delivery_id=? AND status IN ('picked_up','delivered') ORDER BY created_at").all(d.id);
  resOK(res, {
    reference: d.pod_reference || `POD-${d.order_no}`,
    order_no: d.order_no,
    service_type: d.service_type,
    issued_at: new Date().toISOString(),
    pickup: { address: d.pickup_address, contact: d.pickup_name, verified_at: d.pickup_verified_at, otp_verified: Boolean(d.pickup_verified_at), photo: d.pickup_photo ? `/api/deliveries/${d.id}/proof/pickup` : null },
    delivery: { address: d.dest_address, recipient: d.recipient_name || d.dest_name, verified_at: d.delivery_verified_at, otp_verified: Boolean(d.delivery_verified_at), photo: d.delivery_photo ? `/api/deliveries/${d.id}/proof/delivery` : null, notes: d.delivery_notes },
    rider: rider ? { name: rider.full_name, plate: rider.motorcycle_plate } : null,
    coordinates: events,
    charges: { customer_price: d.customer_price, platform_fee: d.platform_fee, rider_earnings: d.rider_earnings, total_charge: d.total_charge, payment_method: d.payment_method, payment_status: d.payment_status }
  });
}));

app.get('/api/deliveries/:id/proof/:kind', auth, route((req, res) => {
  const kind = validate.oneOf(req.params.kind, ['pickup', 'delivery'], 'kind');
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!d || !canAccessDelivery(req.user, d)) return resErr(res, 'Not found', 404, 'delivery_not_found');
  const filename = kind === 'pickup' ? d.pickup_photo : d.delivery_photo;
  if (!filename) return resErr(res, 'Proof image not available', 404, 'proof_not_found');
  const filePath = path.join(UPLOAD_DIR, path.basename(filename));
  if (!fs.existsSync(filePath)) return resErr(res, 'Proof image not available', 404, 'proof_not_found');
  res.sendFile(filePath);
}));

// Digital receipt for the customer's records (spec §6.13).
app.get('/api/deliveries/:id/receipt', auth, route((req, res) => {
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!d) return resErr(res, 'Delivery not found', 404, 'delivery_not_found');
  if (!canAccessDelivery(req.user, d)) return resErr(res, 'Not found', 404, 'delivery_not_found');
  const payments = db.prepare('SELECT type,amount,method,status,processed_at FROM payments WHERE delivery_id=? ORDER BY created_at').all(d.id);
  resOK(res, {
    receipt_no: `RC-${d.order_no}`,
    order_no: d.order_no,
    issued_at: new Date().toISOString(),
    status: d.status,
    service_type: d.service_type,
    route: { from: d.pickup_address, to: d.dest_address, zone_from: d.origin_zone, zone_to: d.dest_zone, distance_km: d.distance_km },
    amounts: {
      delivery_fee: d.customer_price,
      platform_fee: d.platform_fee,
      cancellation_fee: d.cancellation_fee || 0,
      total: (d.status === 'cancelled' ? (d.cancellation_fee || 0) : d.total_charge),
      currency: getConfig('currency', 'RWF')
    },
    payment: { method: d.payment_method, status: d.payment_status, reference: d.payment_ref, paid_at: d.paid_at },
    payments
  });
}));

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

app.get('/api/admin/users', auth, roleAuth('admin'), route((req, res) => {
  const { role, status, search } = req.query;
  const { limit, offset } = validate.pagination(req.query, { defaultLimit: 20, maxLimit: 100 });
  let query = 'SELECT u.id,u.phone,u.email,u.full_name,u.role,u.status,u.created_at';
  // The rider list is the approval and dispatch queue: it must carry the vehicle
  // and availability facts operations decides on, not just the counters.
  if (role === 'rider') query += ',r.approval_status,r.online_status,r.availability,r.avg_rating,r.rating_count,r.total_deliveries,r.total_earnings,r.motorcycle_plate,r.motorcycle_make,r.motorcycle_type,r.last_location_update';
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
  params.push(limit, offset);
  resOK(res, db.prepare(query).all(...params));
}));

/**
 * Account suspension and reinstatement (spec §9.4). Riders keep their dedicated
 * approval workflow; this covers customers, businesses and staff accounts that
 * operations must be able to stop immediately — for fraud, abuse or a dispute.
 */
app.put('/api/admin/users/:id/status', auth, roleAuth('admin'), route((req, res) => {
  const status = validate.oneOf(req.body.status, ['active', 'suspended'], 'status');
  const reason = validate.optionalString(req.body.reason, 'reason', { max: 500 });
  const user = db.prepare('SELECT id,role,status,full_name FROM users WHERE id=?').get(req.params.id);
  if (!user) return resErr(res, 'Account not found', 404, 'account_not_found');
  if (user.role === 'admin') return resErr(res, 'Administrator accounts cannot be suspended from the portal', 409, 'forbidden_target');
  if (user.status === status) return resErr(res, `Account is already ${status}`, 409, 'invalid_state');

  const active = db.prepare("SELECT COUNT(*) AS c FROM deliveries WHERE (customer_id=? OR business_id=?) AND status NOT IN ('delivered','cancelled','failed')").get(user.id, user.id).c;
  if (status === 'suspended' && active > 0 && !req.body.force) {
    return resErr(res, `This account has ${active} delivery/deliveries in flight. Resolve them first or resend with force.`, 409, 'active_deliveries');
  }

  db.prepare("UPDATE users SET status=?,updated_at=datetime('now') WHERE id=?").run(status, user.id);
  if (status === 'suspended' && user.role === 'rider') {
    db.prepare("UPDATE riders SET online_status='offline',availability='offline' WHERE user_id=?").run(user.id);
  }
  audit(req.user.id, status === 'suspended' ? 'account_suspended' : 'account_reinstated', 'user', user.id, { role: user.role, reason });
  notifyUser(user.id, 'account_status', status === 'suspended' ? 'Account suspended' : 'Account reinstated',
    status === 'suspended'
      ? `Your MOVO account has been suspended.${reason ? ` Reason: ${reason}` : ''} Contact support to appeal.`
      : 'Your MOVO account is active again. Welcome back.');
  logger.warn('account_status_changed', { targetId: user.id, role: user.role, status, by: req.user.id });
  resOK(res, { id: user.id, status, message: `Account ${status}` });
}));

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

app.get('/api/admin/deliveries', auth, roleAuth('admin'), route((req, res) => {
  const { status, search } = req.query;
  const { limit, offset } = validate.pagination(req.query, { defaultLimit: 20, maxLimit: 100 });
  let query = 'SELECT d.*,u1.full_name as customer_name,u2.full_name as rider_name FROM deliveries d LEFT JOIN users u1 ON d.customer_id=u1.id LEFT JOIN users u2 ON d.rider_id=u2.id';
  const conditions = []; const params = [];
  if (status) { conditions.push('d.status=?'); params.push(status); }
  if (search) { conditions.push('(d.order_no LIKE ? OR d.pickup_address LIKE ? OR d.dest_address LIKE ?)'); params.push(`%${search}%`,`%${search}%`,`%${search}%`); }
  if (conditions.length) query += ' WHERE ' + conditions.join(' AND ');
  query += ' ORDER BY d.created_at DESC LIMIT ? OFFSET ?';
  params.push(limit, offset);
  resOK(res, db.prepare(query).all(...params));
}));

app.put('/api/admin/deliveries/:id', auth, roleAuth('admin'), (req, res) => {
  const { status, note } = req.body;
  if (!status) return resErr(res, 'Status required');
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!d) return resErr(res, 'Not found', 404);
  updateDeliveryStatus(d.id, status, { note: note || 'Admin update' });
  audit(req.user.id, 'admin_status_change', 'delivery', d.id, { from: d.status, to: status });
  resOK(res, { message: 'Status updated' });
});

// Operations can hand a stalled delivery to another approved rider (spec §9.6).
app.put('/api/admin/deliveries/:id/reassign', auth, roleAuth('admin'), route((req, res) => {
  const riderId = validate.requiredString(req.body.rider_id, 'rider_id');
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(req.params.id);
  if (!d) return resErr(res, 'Delivery not found', 404, 'delivery_not_found');
  if (['delivered','cancelled','failed'].includes(d.status)) return resErr(res, 'Delivery is already closed', 409, 'invalid_state');
  const rider = db.prepare("SELECT u.id,u.full_name FROM users u JOIN riders r ON r.user_id=u.id WHERE u.id=? AND r.approval_status='approved' AND u.status='active'").get(riderId);
  if (!rider) return resErr(res, 'Target rider is not an approved active rider', 409, 'rider_unavailable');
  const previousRider = d.rider_id;
  db.transaction(() => {
    db.prepare("UPDATE deliveries SET rider_id=?,status='assigned',assigned_at=datetime('now'),updated_at=datetime('now') WHERE id=?").run(riderId, d.id);
    db.prepare("UPDATE delivery_offers SET status='expired',responded_at=datetime('now') WHERE delivery_id=? AND status='offered'").run(d.id);
    db.prepare("UPDATE riders SET online_status='busy',availability='busy' WHERE user_id=?").run(riderId);
    if (previousRider && previousRider !== riderId) db.prepare("UPDATE riders SET online_status='online',availability='online' WHERE user_id=?").run(previousRider);
    addEvent(d.id, 'assigned', null, null, `Reassigned by operations to ${rider.full_name}`);
  })();
  audit(req.user.id, 'delivery_reassigned', 'delivery', d.id, { from: previousRider, to: riderId });
  notifyUser(riderId, 'delivery_assigned', 'Delivery assigned to you', `MOVO operations assigned delivery ${d.order_no} to you.`, { delivery_id: d.id });
  if (previousRider && previousRider !== riderId) notifyUser(previousRider, 'delivery_reassigned', 'Delivery reassigned', `Delivery ${d.order_no} was reassigned by operations.`);
  notifyUser(d.customer_id, 'rider_assigned', 'New rider assigned', `A new rider is handling delivery ${d.order_no}.`, { delivery_id: d.id });
  emitDeliveryUpdate(d.id, { status: 'assigned', rider_id: riderId });
  resOK(res, { message: 'Delivery reassigned', rider_id: riderId });
}));

// ─── Admin: incidents, audit trail and KPIs ──────────────────
app.get('/api/admin/incidents', auth, roleAuth('admin'), route((req, res) => {
  const { limit, offset } = validate.pagination(req.query, { defaultLimit: 50 });
  const status = validate.oneOf(req.query.status, ['open', 'investigating', 'resolved'], 'status', { optional: true });
  const rows = db.prepare(`SELECT i.*, u.full_name AS reporter_name, u.phone AS reporter_phone, d.order_no
    FROM incidents i JOIN users u ON u.id=i.reporter_id LEFT JOIN deliveries d ON d.id=i.delivery_id
    ${status ? 'WHERE i.status=?' : ''} ORDER BY CASE i.severity WHEN 'critical' THEN 0 WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END, i.created_at DESC
    LIMIT ? OFFSET ?`).all(...(status ? [status, limit, offset] : [limit, offset]));
  resOK(res, rows);
}));

app.put('/api/admin/incidents/:id', auth, roleAuth('admin'), route((req, res) => {
  const status = validate.oneOf(req.body.status, ['open', 'investigating', 'resolved'], 'status');
  const resolution = validate.optionalString(req.body.resolution, 'resolution', { max: 2000 });
  const changed = db.prepare("UPDATE incidents SET status=?,resolution=COALESCE(?,resolution),resolved_at=CASE WHEN ?='resolved' THEN datetime('now') ELSE resolved_at END WHERE id=?")
    .run(status, resolution, status, req.params.id).changes;
  if (!changed) return resErr(res, 'Incident not found', 404, 'incident_not_found');
  audit(req.user.id, 'incident_updated', 'incident', req.params.id, { status });
  resOK(res, { message: 'Incident updated' });
}));

// Every administrative change is queryable — the tamper-evident record in spec §15.5.
app.get('/api/admin/audit', auth, roleAuth('admin'), route((req, res) => {
  const { limit, offset } = validate.pagination(req.query, { defaultLimit: 50, maxLimit: 200 });
  const entity = validate.optionalString(req.query.entity, 'entity', { max: 40 });
  const actorId = validate.optionalString(req.query.user_id, 'user_id', { max: 64 });
  const conditions = [];
  const params = [];
  if (entity) { conditions.push('a.entity=?'); params.push(entity); }
  if (actorId) { conditions.push('a.user_id=?'); params.push(actorId); }
  const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : '';
  const rows = db.prepare(`SELECT a.*, u.full_name AS actor_name, u.role AS actor_role FROM audit_log a
    LEFT JOIN users u ON u.id=a.user_id ${where} ORDER BY a.created_at DESC LIMIT ? OFFSET ?`).all(...params, limit, offset);
  const total = db.prepare(`SELECT COUNT(*) AS c FROM audit_log a ${where}`).get(...params).c;
  resOK(res, { entries: rows, total });
}));

/** Platform KPI pack (spec §22) — the numbers operations reviews daily. */
app.get('/api/admin/kpis', auth, roleAuth('admin'), route((req, res) => {
  const days = validate.integerInRange(req.query.days, 'days', { min: 1, max: 365, fallback: 30 });
  const since = `-${days} days`;
  const one = (sql, ...params) => db.prepare(sql).get(...params);

  const customers = one(`SELECT
      (SELECT COUNT(*) FROM users WHERE role='customer') AS registered,
      (SELECT COUNT(DISTINCT customer_id) FROM deliveries WHERE created_at >= datetime('now', ?)) AS active,
      (SELECT COUNT(*) FROM (SELECT customer_id FROM deliveries WHERE created_at >= datetime('now', ?) GROUP BY customer_id HAVING COUNT(*) > 1)) AS repeat_customers`, since, since);
  const deliveries = one(`SELECT COUNT(*) AS requested,
      SUM(CASE WHEN status='delivered' THEN 1 ELSE 0 END) AS completed,
      SUM(CASE WHEN status='cancelled' THEN 1 ELSE 0 END) AS cancelled,
      SUM(CASE WHEN status='failed' THEN 1 ELSE 0 END) AS failed,
      AVG(CASE WHEN status='delivered' AND picked_up_at IS NOT NULL AND assigned_at IS NOT NULL
            THEN (julianday(picked_up_at)-julianday(assigned_at))*24*60 END) AS avg_pickup_minutes,
      AVG(CASE WHEN status='delivered' AND delivered_at IS NOT NULL
            THEN (julianday(delivered_at)-julianday(created_at))*24*60 END) AS avg_delivery_minutes
    FROM deliveries WHERE created_at >= datetime('now', ?)`, since);
  const riders = one(`SELECT
      (SELECT COUNT(*) FROM riders WHERE approval_status='approved') AS approved,
      (SELECT COUNT(*) FROM riders WHERE online_status IN ('online','busy')) AS online,
      (SELECT COALESCE(AVG(avg_rating),0) FROM riders WHERE rating_count > 0) AS avg_rating,
      (SELECT COALESCE(SUM(accepted_offers),0) FROM riders) AS accepted_offers,
      (SELECT COALESCE(SUM(declined_offers),0) FROM riders) AS declined_offers`);
  const finance = one(`SELECT COALESCE(SUM(total_charge),0) AS gross_delivery_value,
      COALESCE(SUM(platform_fee),0) AS platform_revenue,
      COALESCE(SUM(rider_earnings),0) AS rider_payouts,
      COALESCE(AVG(platform_fee),0) AS avg_platform_fee
    FROM deliveries WHERE status='delivered' AND delivered_at >= datetime('now', ?)`, since);
  const operations = one(`SELECT
      (SELECT COUNT(*) FROM tickets WHERE created_at >= datetime('now', ?)) AS tickets,
      (SELECT COUNT(*) FROM tickets WHERE status='open') AS open_tickets,
      (SELECT COUNT(*) FROM incidents WHERE created_at >= datetime('now', ?)) AS incidents,
      (SELECT COALESCE(AVG((julianday(resolved_at)-julianday(created_at))*24),0) FROM tickets WHERE resolved_at IS NOT NULL AND created_at >= datetime('now', ?)) AS avg_resolution_hours`, since, since, since);

  const requested = deliveries.requested || 0;
  const offers = (riders.accepted_offers || 0) + (riders.declined_offers || 0);
  resOK(res, {
    window_days: days,
    customers: {
      registered: customers.registered,
      active: customers.active,
      repeat_rate: customers.active ? Math.round((customers.repeat_customers / customers.active) * 100) : 0
    },
    deliveries: {
      requested,
      completed: deliveries.completed || 0,
      completion_rate: requested ? Math.round(((deliveries.completed || 0) / requested) * 100) : 0,
      cancellation_rate: requested ? Math.round(((deliveries.cancelled || 0) / requested) * 100) : 0,
      failure_rate: requested ? Math.round(((deliveries.failed || 0) / requested) * 100) : 0,
      avg_pickup_minutes: Math.round(deliveries.avg_pickup_minutes || 0),
      avg_delivery_minutes: Math.round(deliveries.avg_delivery_minutes || 0)
    },
    riders: {
      approved: riders.approved,
      online: riders.online,
      avg_rating: Math.round((riders.avg_rating || 0) * 10) / 10,
      acceptance_rate: offers ? Math.round(((riders.accepted_offers || 0) / offers) * 100) : 0,
      deliveries_per_rider: riders.approved ? Math.round(((deliveries.completed || 0) / riders.approved) * 10) / 10 : 0
    },
    financial: {
      gross_delivery_value: Math.round(finance.gross_delivery_value),
      platform_revenue: Math.round(finance.platform_revenue),
      rider_payouts: Math.round(finance.rider_payouts),
      avg_platform_fee: Math.round(finance.avg_platform_fee),
      revenue_per_active_customer: customers.active ? Math.round(finance.platform_revenue / customers.active) : 0,
      currency: getConfig('currency', 'RWF')
    },
    operations: {
      tickets: operations.tickets,
      open_tickets: operations.open_tickets,
      incidents: operations.incidents,
      avg_resolution_hours: Math.round((operations.avg_resolution_hours || 0) * 10) / 10,
      uptime_seconds: Math.round(process.uptime())
    }
  });
}));

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

function emitToUser(userId, event, payload) {
  const sockets = userSockets[userId];
  if (!sockets) return;
  if (sockets instanceof Set) for (const socket of sockets) socket.emit(event, payload);
  else sockets.emit(event, payload);
}

function emitDeliveryUpdate(deliveryId, payload = {}) {
  io.to(`delivery:${deliveryId}`).emit('delivery_update', { delivery_id: deliveryId, ...payload });
}

function dispatchSelectedDelivery(deliveryId, riderId) {
  const delivery = db.prepare('SELECT * FROM deliveries WHERE id=?').get(deliveryId);
  if (!delivery) throw new Error('Delivery not found');
  const radius = Math.min(20, parseFloat(getConfig('rider_search_radius_km', '5')) || 5);
  const rider = eligibleNearbyRiders(Number(delivery.pickup_lat), Number(delivery.pickup_lng), radius).find(candidate => candidate.id === riderId);
  if (!rider) throw new Error('Selected rider is not eligible or nearby');
  const timeoutSeconds = parseInt(getConfig('rider_accept_timeout_sec', '30'), 10) || 30;
  const offerId = uuidv4();
  db.transaction(() => {
    db.prepare("UPDATE delivery_offers SET status='expired',responded_at=datetime('now') WHERE delivery_id=? AND status='offered'").run(deliveryId);
    db.prepare("UPDATE deliveries SET preferred_rider_id=?,dispatch_mode='selected',status='searching',updated_at=datetime('now') WHERE id=?").run(riderId, deliveryId);
    db.prepare("INSERT INTO delivery_offers (id,delivery_id,rider_id,expires_at) VALUES (?,?,?,datetime('now',?))")
      .run(offerId, deliveryId, riderId, `+${timeoutSeconds} seconds`);
    addEvent(deliveryId, 'searching', null, null, 'Waiting for selected rider');
  })();
  emitToUser(riderId, 'new_delivery', {
    offer_id: offerId,
    id: delivery.id, order_no: delivery.order_no, service_type: delivery.service_type,
    pickup_address: delivery.pickup_address, pickup_lat: delivery.pickup_lat, pickup_lng: delivery.pickup_lng,
    pickup_name: delivery.pickup_name, pickup_phone: delivery.pickup_phone,
    dest_address: delivery.dest_address, dest_lat: delivery.dest_lat, dest_lng: delivery.dest_lng,
    dest_name: delivery.dest_name, dest_phone: delivery.dest_phone,
    earnings: delivery.rider_earnings, distance_km: delivery.distance_km,
    timeout: timeoutSeconds
  });
  setTimeout(() => {
    expireSelectedOffers();
  }, timeoutSeconds * 1000 + 50);
}

function dispatchDelivery(deliveryId) {
  updateDeliveryStatus(deliveryId, 'searching');
  const d = db.prepare('SELECT * FROM deliveries WHERE id=?').get(deliveryId);
  if (!d) return;

  let radius = parseFloat(getConfig('rider_search_radius_km', '5'));
  const expandStep = parseFloat(getConfig('rider_search_expand_km', '2'));
  const maxRadius = radius + expandStep * 3;

  function search() {
    const nearby = eligibleNearbyRiders(Number(d.pickup_lat), Number(d.pickup_lng), radius)
      .sort((a, b) => (b.avg_rating || 0) - (a.avg_rating || 0));

    if (nearby.length > 0) {
      for (const rider of nearby.slice(0, 5)) {
        const timeoutSeconds = parseInt(getConfig('rider_accept_timeout_sec', '30'));
        const existingOffer = db.prepare("SELECT id FROM delivery_offers WHERE delivery_id=? AND rider_id=? AND status='offered' AND expires_at>datetime('now')").get(d.id, rider.id);
        const offerId = existingOffer?.id || uuidv4();
        if (!existingOffer) db.prepare("INSERT INTO delivery_offers (id,delivery_id,rider_id,expires_at) VALUES (?,?,?,datetime('now',?))")
          .run(offerId, d.id, rider.id, `+${timeoutSeconds} seconds`);
        emitToUser(rider.id, 'new_delivery', {
            offer_id: offerId,
            id: d.id, order_no: d.order_no, service_type: d.service_type,
            pickup_address: d.pickup_address, pickup_lat: d.pickup_lat, pickup_lng: d.pickup_lng,
            pickup_name: d.pickup_name, pickup_phone: d.pickup_phone,
            dest_address: d.dest_address, dest_lat: d.dest_lat, dest_lng: d.dest_lng,
            dest_name: d.dest_name, dest_phone: d.dest_phone,
            earnings: d.rider_earnings, distance_km: d.distance_km,
            estimated_minutes: d.est_delivery_time,
            timeout: timeoutSeconds
          });
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

/**
 * Releases scheduled deliveries into dispatch once their window opens. Runs on a
 * timer rather than a per-delivery timeout so a restart never loses a booking.
 */
function releaseScheduledDeliveries() {
  const due = db.prepare("SELECT id,preferred_rider_id FROM deliveries WHERE status='scheduled' AND scheduled_for IS NOT NULL AND scheduled_for <= datetime('now') LIMIT 25").all();
  for (const delivery of due) {
    try {
      const claimed = db.prepare("UPDATE deliveries SET status='created',updated_at=datetime('now') WHERE id=? AND status='scheduled'").run(delivery.id).changes;
      if (!claimed) continue;
      addEvent(delivery.id, 'created', null, null, 'Scheduled delivery released to dispatch');
      if (delivery.preferred_rider_id) dispatchSelectedDelivery(delivery.id, delivery.preferred_rider_id);
      else dispatchDelivery(delivery.id);
    } catch (error) {
      logger.error('scheduled_release_failed', { deliveryId: delivery.id, message: error.message });
    }
  }
  return due.length;
}

const scheduleTimer = setInterval(releaseScheduledDeliveries, 30_000);
if (typeof scheduleTimer.unref === 'function') scheduleTimer.unref();

// ─── WebSocket ───────────────────────────────────────────────
io.on('connection', (socket) => {
  let currentUser = null;

  socket.on('authenticate', (token) => {
    try {
      const payload = jwt.verify(token, JWT_SECRET);
      const user = db.prepare('SELECT id,phone,full_name,role,status FROM users WHERE id=?').get(payload.id);
      if (!user || user.status !== 'active') throw new Error('Account unavailable');
      currentUser = user;
      if (!userSockets[user.id]) userSockets[user.id] = new Set();
      userSockets[user.id].add(socket);
      socket.emit('authenticated', { user_id: user.id, role: user.role });
    } catch(e) {
      socket.emit('authentication_error', { error: 'Authentication failed' });
    }
  });

  socket.on('subscribe_delivery', (data) => {
    if (!currentUser) return socket.emit('authentication_error', { error: 'Authentication required' });
    const deliveryId = typeof data === 'string' ? data : data?.delivery_id;
    const delivery = deliveryId ? db.prepare('SELECT * FROM deliveries WHERE id=?').get(deliveryId) : null;
    if (!canAccessDelivery(currentUser, delivery)) return socket.emit('authentication_error', { error: 'Delivery unavailable' });
    socket.join(`delivery:${delivery.id}`);
    socket.emit('delivery_subscribed', { delivery_id: delivery.id });
  });

  socket.on('rider_location', (data) => {
    if (!currentUser || currentUser.role !== 'rider') return;
    const lat = Number(data?.lat);
    const lng = Number(data?.lng);
    const deliveryId = data?.delivery_id;
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180 || !deliveryId) return;
    const rider = db.prepare('SELECT approval_status,online_status FROM riders WHERE user_id=?').get(currentUser.id);
    const delivery = db.prepare("SELECT id FROM deliveries WHERE id=? AND rider_id=? AND status IN ('assigned','going_pickup','arrived_pickup','picked_up','in_transit','arrived_dest')").get(deliveryId, currentUser.id);
    if (!rider || rider.approval_status !== 'approved' || !['online','busy'].includes(rider.online_status) || !delivery) return;
    db.prepare("UPDATE riders SET current_lat=?,current_lng=?,last_location_update=datetime('now') WHERE user_id=?").run(lat, lng, currentUser.id);
    db.prepare('INSERT INTO rider_locations (id,rider_id,delivery_id,lat,lng) VALUES (?,?,?,?,?)').run(uuidv4(), currentUser.id, deliveryId, lat, lng);
    io.to(`delivery:${deliveryId}`).emit('rider_location', { delivery_id: deliveryId, lat, lng });
  });

  socket.on('disconnect', () => {
    if (currentUser && userSockets[currentUser.id]) {
      userSockets[currentUser.id].delete(socket);
      if (userSockets[currentUser.id].size === 0) delete userSockets[currentUser.id];
    }
  });
});

// ─── Health, readiness and metrics ───────────────────────────
let shuttingDown = false;

function databaseHealthy() {
  try { db.prepare('SELECT 1').get(); return true; } catch { return false; }
}

app.get('/health', (req, res) => {
  const healthy = databaseHealthy() && !shuttingDown;
  res.status(healthy ? 200 : 503).json({
    status: healthy ? 'ok' : 'error',
    service: 'movo',
    version: require('./package.json').version,
    uptimeSeconds: Math.round(process.uptime()),
    timestamp: new Date().toISOString()
  });
});

/** Readiness reflects dependency and configuration health, so orchestrators can gate traffic. */
app.get('/ready', (req, res) => {
  const readiness = evaluateReadiness(runtime, { database: databaseHealthy() });
  const ready = readiness.ready && !shuttingDown;
  res.status(ready ? 200 : 503).json({
    status: ready ? 'ready' : 'not_ready',
    failures: shuttingDown ? [...readiness.failures, 'shutting down'] : readiness.failures,
    environment: runtime.nodeEnv,
    timestamp: new Date().toISOString()
  });
});

metrics.registerGauge('movo_deliveries_active', 'Deliveries currently in flight.', () =>
  db.prepare("SELECT COUNT(*) AS c FROM deliveries WHERE status NOT IN ('delivered','cancelled','failed')").get().c);
metrics.registerGauge('movo_riders_online', 'Approved riders currently online or busy.', () =>
  db.prepare("SELECT COUNT(*) AS c FROM riders WHERE online_status IN ('online','busy')").get().c);
metrics.registerGauge('movo_offers_pending', 'Delivery offers awaiting a rider response.', () =>
  db.prepare("SELECT COUNT(*) AS c FROM delivery_offers WHERE status='offered' AND expires_at>datetime('now')").get().c);
metrics.registerGauge('movo_tickets_open', 'Open customer support tickets.', () =>
  db.prepare("SELECT COUNT(*) AS c FROM tickets WHERE status='open'").get().c);

app.get('/metrics', (req, res) => {
  if (runtime.metricsToken) {
    const provided = req.headers.authorization?.replace('Bearer ', '') || req.query.token;
    if (provided !== runtime.metricsToken) return res.status(401).type('text/plain').send('unauthorized\n');
  } else if (runtime.production) {
    return res.status(404).type('text/plain').send('not found\n');
  }
  res.type('text/plain; version=0.0.4').send(metrics.render());
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

// ─── Not Found (API namespace) ───────────────────────────────
app.use('/api', (req, res) => resErr(res, `No API route matches ${req.method} ${req.path}`, 404, 'route_not_found'));

// ─── Error Handler ───────────────────────────────────────────
app.use((err, req, res, next) => {
  if (err instanceof ValidationError) return resErr(res, err.message, err.status, err.code);
  if (err?.type === 'entity.too.large') return resErr(res, 'Request body is too large', 413, 'payload_too_large');
  if (err instanceof SyntaxError && 'body' in err) return resErr(res, 'Request body is not valid JSON', 400, 'invalid_json');
  if (err?.code === 'LIMIT_FILE_SIZE') return resErr(res, 'Uploaded file exceeds the 5 MB limit', 413, 'file_too_large');
  const log = req.log || logger;
  log.error('unhandled_error', { message: err?.message, stack: IS_PRODUCTION ? undefined : err?.stack, path: req.originalUrl });
  resErr(res, 'Internal server error', 500, 'internal_error');
});

// ─── Retention & housekeeping ────────────────────────────────
// Location breadcrumbs and notifications are personal data: keep only what the
// operations and dispute workflows actually need.
function pruneRetainedData() {
  try {
    const locations = db.prepare("DELETE FROM rider_locations WHERE created_at < datetime('now', ?)")
      .run(`-${runtime.retention.riderLocationDays} days`).changes;
    const notifications = db.prepare("DELETE FROM notifications WHERE is_read=1 AND created_at < datetime('now', ?)")
      .run(`-${runtime.retention.notificationDays} days`).changes;
    if (locations || notifications) logger.info('retention_pruned', { locations, notifications });
  } catch (error) {
    logger.error('retention_prune_failed', { message: error.message });
  }
}

const retentionTimer = setInterval(pruneRetainedData, 6 * 60 * 60 * 1000);
if (typeof retentionTimer.unref === 'function') retentionTimer.unref();

// ─── Graceful shutdown ───────────────────────────────────────
function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  logger.info('shutdown_started', { signal });
  const forced = setTimeout(() => {
    logger.error('shutdown_forced', { signal });
    process.exit(1);
  }, runtime.shutdownGraceMs);
  if (typeof forced.unref === 'function') forced.unref();

  io.close(() => logger.info('socket_server_closed'));
  server.close(() => {
    try { db.pragma('wal_checkpoint(TRUNCATE)'); db.close(); } catch (error) { logger.warn('db_close_failed', { message: error.message }); }
    logger.info('shutdown_complete', { signal });
    clearTimeout(forced);
    process.exit(0);
  });
}

for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => shutdown(signal));
process.on('unhandledRejection', reason => logger.error('unhandled_rejection', { message: reason instanceof Error ? reason.message : String(reason) }));
process.on('uncaughtException', error => {
  logger.error('uncaught_exception', { message: error.message, stack: error.stack });
  shutdown('uncaughtException');
});

// ─── Start Server ────────────────────────────────────────────
seedData();
server.listen(PORT, () => {
  logger.info('server_started', { port: PORT, environment: runtime.nodeEnv, smsProvider: runtime.providers.sms, rateLimiting: runtime.rateLimit.enabled });
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
