const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
test('customer parcel theme defines every requested brand token and accessible button treatment', () => {
 const p = path.join(__dirname,'../android/customer-app/src/main/kotlin/com/movo/customer/parcel/ui/ParcelTheme.kt');
 const text=fs.existsSync(p)?fs.readFileSync(p,'utf8'):'';
 for(const token of ['MovoGreen','MovoGreenPressed','MovoGreenLight','BackgroundDark','SurfaceDark','SurfaceElevated','TextPrimary','TextSecondary','Divider','DisabledBg','DisabledText','ErrorRed']) assert.ok(text.includes(token),token);
 assert.match(text,/containerColor = MovoGreenPressed/);
 assert.match(text,/R.font.inter/);
});
