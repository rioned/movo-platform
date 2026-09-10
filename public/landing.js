(() => {
  'use strict';
  const menu = document.getElementById('menu-toggle');
  const nav = document.getElementById('primary-nav');
  if (menu && nav) {
    const setMenu = open => {
      nav.dataset.open = String(open);
      menu.setAttribute('aria-expanded', String(open));
    };
    menu.hidden = false;
    document.documentElement.classList.add('nav-enhanced');
    setMenu(false);
    menu.addEventListener('click', () => setMenu(nav.dataset.open !== 'true'));
    nav.addEventListener('click', event => {
      if (event.target.closest('a')) setMenu(false);
    });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape' && nav.dataset.open === 'true') {
        setMenu(false);
        menu.focus();
      }
    });
  }
  const scene = document.getElementById('delivery-scene');
  const motion = document.getElementById('motion-toggle');
  if (!scene || !motion) return;
  const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
  let userPaused = false;
  let inView = true;
  const resetTilt = () => {
    scene.style.setProperty('--tilt-x', '0deg');
    scene.style.setProperty('--tilt-y', '0deg');
  };
  const syncMotion = () => {
    const paused = userPaused || preference.matches || document.hidden || !inView;
    scene.dataset.motion = paused ? 'paused' : 'running';
    motion.setAttribute('aria-pressed', String(userPaused || preference.matches));
    motion.textContent = preference.matches ? 'Motion reduced' : userPaused ? 'Resume motion' : 'Pause motion';
    motion.disabled = preference.matches;
    if (paused) resetTilt();
  };
  motion.addEventListener('click', () => { userPaused = !userPaused; syncMotion(); });
  preference.addEventListener('change', syncMotion);
  document.addEventListener('visibilitychange', syncMotion);
  if ('IntersectionObserver' in window) {
    new IntersectionObserver(entries => {
      inView = entries[0].isIntersecting;
      syncMotion();
    }, { threshold: 0 }).observe(scene);
  }
  const choices = document.querySelectorAll('[data-scene]');
  scene.addEventListener('pointermove', event => {
    if (scene.dataset.motion !== 'running' || event.pointerType === 'touch') return;
    const box = scene.getBoundingClientRect();
    const x = Math.max(-.5, Math.min(.5, (event.clientX - box.left) / box.width - .5));
    const y = Math.max(-.5, Math.min(.5, (event.clientY - box.top) / box.height - .5));
    scene.style.setProperty('--tilt-x', `${-y * 10}deg`);
    scene.style.setProperty('--tilt-y', `${x * 12}deg`);
  });
  scene.addEventListener('pointerleave', resetTilt);
  scene.addEventListener('pointercancel', resetTilt);
  const description = document.getElementById('scene-description');
  choices.forEach(button => button.addEventListener('click', () => {
    scene.dataset.kind = button.dataset.scene;
    choices.forEach(choice => choice.setAttribute('aria-pressed', String(choice === button)));
    description.textContent = button.dataset.scene === 'document'
      ? 'Document delivery, accounted for.' : 'Parcel delivery, reimagined.';
  }));
  syncMotion();
})();
