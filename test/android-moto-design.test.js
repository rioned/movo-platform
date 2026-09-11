const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = file => fs.existsSync(path.join(__dirname, '..', file)) ? fs.readFileSync(path.join(__dirname, '..', file), 'utf8') : '';

test('shared motorcycle hero has user-controlled perspective and accessible motion controls', () => {
  const source = read('android/design/src/main/kotlin/com/movo/design/MotoHero.kt');
  for (const contract of [/fun MotoHero\(/, /rotationY/, /rotationX/, /cameraDistance/, /onClickLabel/, /Animations disabled/, /ValueAnimator.areAnimatorsEnabled/, /Canvas/, /compact/]) assert.match(source, contract);
  assert.doesNotMatch(source, /rememberInfiniteTransition/);
});

test('both Android build variants use the MOVO production provider without overrides', () => {
  for (const app of ['customer-app', 'rider-app']) {
    const source = read(`android/${app}/build.gradle.kts`);
    assert.match(source, /val movoApiBaseUrl = "https:\/\/movo-vervice\.tech"/);
    assert.doesNotMatch(source, /findProperty\("movoApiBaseUrl"\)/);
  }
});

test('shared theme offers a lime accent and full-pill primary controls', () => {
  assert.match(read('android/design/src/main/kotlin/com/movo/design/MovoTheme.kt'), /val Lime = Color\(0xFFD5F878\)/);
  assert.match(read('android/design/src/main/kotlin/com/movo/design/Buttons.kt'), /shape = androidx.compose.foundation.shape.CircleShape/);
});
