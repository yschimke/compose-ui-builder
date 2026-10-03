// The demo video. Set this to a YouTube embed URL (https://www.youtube-nocookie.com/embed/<id>)
// or a direct .mp4 URL, and the page shows the "Watch the demo" button and the video section.
const VIDEO_URL = '';

if (VIDEO_URL) {
  const frame = document.querySelector('.video-frame');
  if (/\.(mp4|webm)(\?|$)/.test(VIDEO_URL)) {
    const video = document.createElement('video');
    video.src = VIDEO_URL;
    video.controls = true;
    video.poster = 'img/hero.png';
    frame.appendChild(video);
  } else {
    const iframe = document.createElement('iframe');
    iframe.src = VIDEO_URL;
    iframe.title = 'Compose UI Builder demo';
    iframe.allow = 'accelerometer; encrypted-media; gyroscope; picture-in-picture; fullscreen';
    iframe.allowFullscreen = true;
    frame.appendChild(iframe);
  }
  document.getElementById('video').hidden = false;
  document.getElementById('video-cta').hidden = false;
}

// Agent tabs, with arrow-key navigation. Every tab list on the page offers the same agents, so
// choosing one in any list switches them all.
const tabs = [...document.querySelectorAll('[role="tab"]')];
function select(agent) {
  for (const t of tabs) {
    const on = t.dataset.agent === agent;
    t.setAttribute('aria-selected', String(on));
    t.tabIndex = on ? 0 : -1;
    document.getElementById(t.getAttribute('aria-controls')).hidden = !on;
  }
}
for (const list of document.querySelectorAll('[role="tablist"]')) {
  const group = [...list.querySelectorAll('[role="tab"]')];
  group.forEach((tab, i) => {
    tab.addEventListener('click', () => select(tab.dataset.agent));
    tab.addEventListener('keydown', (event) => {
      const step = { ArrowRight: 1, ArrowLeft: -1 }[event.key];
      if (!step) return;
      const next = group[(i + step + group.length) % group.length];
      select(next.dataset.agent);
      next.focus();
    });
  });
}

// Copy buttons on code blocks.
for (const pre of document.querySelectorAll('pre')) {
  const button = document.createElement('button');
  button.className = 'copy';
  button.type = 'button';
  button.textContent = 'Copy';
  button.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(pre.querySelector('code').innerText);
      button.textContent = 'Copied';
    } catch {
      button.textContent = 'Press Ctrl+C';
    }
    setTimeout(() => (button.textContent = 'Copy'), 1500);
  });
  pre.appendChild(button);
}

// The agent prompt: collapsed to its first lines until asked for, so Get started stays readable.
for (const pre of document.querySelectorAll('pre.prompt')) {
  const toggle = document.createElement('button');
  toggle.type = 'button';
  toggle.className = 'prompt-toggle';
  const label = () => (toggle.textContent = pre.classList.contains('collapsed')
    ? 'Show the whole prompt' : 'Show less');
  label();
  toggle.setAttribute('aria-expanded', 'false');
  toggle.addEventListener('click', () => {
    const collapsed = pre.classList.toggle('collapsed');
    toggle.setAttribute('aria-expanded', String(!collapsed));
    label();
  });
  pre.after(toggle);
}

// Screenshots open full size in a viewer on the page; a click outside the picture, or Esc,
// closes it. Without JS the step pictures still link to their PNGs.
const viewer = document.createElement('dialog');
viewer.className = 'viewer';
viewer.innerHTML = '<figure><img alt=""><figcaption></figcaption></figure>';
document.body.appendChild(viewer);
const viewerImg = viewer.querySelector('img');
const viewerCaption = viewer.querySelector('figcaption');
viewer.addEventListener('click', (event) => {
  // The dialog itself is the backdrop area; the figure stops short of it.
  if (!event.target.closest('figure')) viewer.close();
});
viewer.addEventListener('close', () => viewerImg.removeAttribute('src'));
for (const img of document.querySelectorAll('.shot img, .hero-shot img, .feature-shots img')) {
  const target = img.closest('a') ?? img;
  target.classList.add('zoomable');
  if (target === img) {
    img.tabIndex = 0;
    img.setAttribute('role', 'button');
    img.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); target.click(); }
    });
  }
  target.addEventListener('click', (event) => {
    event.preventDefault();
    viewerImg.src = img.currentSrc || img.src;
    viewerImg.alt = img.alt;
    viewerCaption.textContent = img.alt;
    viewer.showModal();
  });
}
