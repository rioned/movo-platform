const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function setup(reduced = false) {
  const element = (dataset = {}) => ({ dataset, attrs: {}, events: {}, style: { setProperty(k,v) { this[k] = v; } }, textContent: '', hidden: false,
    setAttribute(k,v) { this.attrs[k] = String(v); }, getAttribute(k) { return this.attrs[k]; },
    addEventListener(k,f) { this.events[k] = f; }, focus() { this.focused = true; },
    getBoundingClientRect() { return {left:0,top:0,width:500,height:500}; }
  });
  const scene = element(), motion = element(), status = element(), menu = element(), nav = element();
  const parcel = element({scene:'parcel'}), documentButton = element({scene:'document'});
  const els = {'delivery-scene':scene,'motion-toggle':motion,'scene-description':status,'menu-toggle':menu,'primary-nav':nav};
  const doc = { hidden:false, events:{}, documentElement:{classList:{add(){}}}, getElementById:k=>els[k], querySelectorAll:()=>[parcel,documentButton], addEventListener(k,f){this.events[k]=f;} };
  const media = {matches:reduced,addEventListener(k,f){this.change=f;}};
  let intersection;
  const win = {matchMedia:()=>media,addEventListener(){},IntersectionObserver:class {constructor(f){intersection=f;} observe(){}}};
  const context = {document:doc,window:win,IntersectionObserver:win.IntersectionObserver};
  const code = fs.existsSync('public/landing.js') ? fs.readFileSync('public/landing.js','utf8') : '';
  vm.runInNewContext(code,context);
  return {scene,motion,status,menu,nav,parcel,documentButton,doc,media,intersect:value=>intersection([{isIntersecting:value}])};
}

test('scene motion respects live reduced motion, visibility, offscreen and explicit pause', () => {
  const x = setup(true);
  assert.equal(x.scene.dataset.motion, 'paused', 'reduced motion must initially pause the scene');
  x.media.matches=false; x.media.change();
  assert.equal(x.scene.dataset.motion,'running');
  x.motion.events.click();
  assert.equal(x.scene.dataset.motion,'paused');
  assert.equal(x.motion.attrs['aria-pressed'],'true');
  x.doc.hidden=true; x.doc.events.visibilitychange();
  x.doc.hidden=false; x.doc.events.visibilitychange();
  assert.equal(x.scene.dataset.motion,'paused', 'visibility must not undo user pause');
  x.motion.events.click(); x.intersect(false);
  assert.equal(x.scene.dataset.motion,'paused');
  x.intersect(true); assert.equal(x.scene.dataset.motion,'running');
  x.media.matches=true; x.media.change();
  assert.equal(x.scene.dataset.motion,'paused');
});

test('delivery buttons change the illustration and announce the selection', () => {
  const x = setup();
  assert.equal(typeof x.documentButton.events.click, 'function');
  x.documentButton.events.click();
  assert.equal(x.scene.dataset.kind, 'document');
  assert.equal(x.documentButton.attrs['aria-pressed'], 'true');
  assert.equal(x.parcel.attrs['aria-pressed'], 'false');
  assert.match(x.status.textContent, /Document/);
  x.parcel.events.click();
  assert.equal(x.scene.dataset.kind, 'parcel');
});

test('pointer tilt resets on leave and cannot run when motion is paused', () => {
  const x = setup();
  assert.equal(typeof x.scene.events.pointermove, 'function');
  x.scene.events.pointermove({clientX:500,clientY:0,pointerType:'mouse'});
  assert.notEqual(x.scene.style['--tilt-x'],'0deg');
  x.scene.events.pointerleave();
  assert.equal(x.scene.style['--tilt-x'],'0deg');
  x.motion.events.click();
  x.scene.events.pointermove({clientX:500,clientY:0,pointerType:'mouse'});
  assert.equal(x.scene.style['--tilt-x'],'0deg');
});

test('mobile navigation toggles, closes on links and returns focus with Escape', () => {
  const x = setup();
  assert.equal(typeof x.menu.events.click, 'function');
  assert.equal(x.menu.hidden, false);
  x.menu.events.click();
  assert.equal(x.menu.attrs['aria-expanded'],'true');
  assert.equal(x.nav.dataset.open,'true');
  x.doc.events.keydown({key:'Escape'});
  assert.equal(x.menu.attrs['aria-expanded'],'false');
  assert.equal(x.menu.focused,true);
  x.menu.events.click();
  x.nav.events.click({target:{closest:()=>({})}});
  assert.equal(x.nav.dataset.open,'false');
});
