const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1024, height: 1024 } });
  for (const [src, out, size] of JSON.parse(process.argv[2])) {
    const svg = fs.readFileSync(src, 'utf8');
    await p.setViewportSize({ width: size, height: size });
    await p.setContent(`<html><body style="margin:0;background:transparent"><div style="width:${size}px;height:${size}px">${svg.replace('width="1024" height="1024"', `width="${size}" height="${size}"`)}</div></body></html>`);
    await p.screenshot({ path: out, omitBackground: true, clip: { x: 0, y: 0, width: size, height: size } });
  }
  await b.close();
})();
