const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('rider system bars remain legible when the green welcome surface is shown in either theme', () => {
  const activity = fs.readFileSync(path.join(__dirname, '../android/rider-app/src/main/kotlin/com/movo/rider/MainActivity.kt'), 'utf8');
  assert.match(activity, /isAppearanceLightStatusBars = authenticated && !dark/);
  assert.match(activity, /isAppearanceLightNavigationBars = !dark/);
  assert.match(activity, /navigationBarColor = barSurface\.toArgb\(\)/);
});
