const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

async function gerar() {
  const svgPath = path.join(__dirname, 'icon-screen.svg');
  const svgBuffer = fs.readFileSync(svgPath);

  // 1. Ícones PNG exatos
  await sharp(svgBuffer)
    .resize(192, 192)
    .png()
    .toFile(path.join(__dirname, 'icon-192.png'));
  console.log('✅ icon-192.png gerado (192x192)');

  await sharp(svgBuffer)
    .resize(512, 512)
    .png()
    .toFile(path.join(__dirname, 'icon-512.png'));
  console.log('✅ icon-512.png gerado (512x512)');

  // 2. Screenshots requeridas pelo PWABuilder
  // Screenshot Landscape (1280x720)
  await sharp({
    create: {
      width: 1280,
      height: 720,
      channels: 4,
      background: { r: 2, g: 6, b: 23, alpha: 1 }
    }
  })
  .composite([{ input: await sharp(svgBuffer).resize(256, 256).png().toBuffer(), gravity: 'center' }])
  .png()
  .toFile(path.join(__dirname, 'screenshot-wide.png'));
  console.log('✅ screenshot-wide.png gerada (1280x720)');

  // Screenshot Portrait (720x1280)
  await sharp({
    create: {
      width: 720,
      height: 1280,
      channels: 4,
      background: { r: 2, g: 6, b: 23, alpha: 1 }
    }
  })
  .composite([{ input: await sharp(svgBuffer).resize(256, 256).png().toBuffer(), gravity: 'center' }])
  .png()
  .toFile(path.join(__dirname, 'screenshot-mobile.png'));
  console.log('✅ screenshot-mobile.png gerada (720x1280)');
}

gerar().catch(console.error);
