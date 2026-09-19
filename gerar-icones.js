const fs = require('fs');

// PNG 1x1 azul transparente com assinatura válida
const samplePngBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
const buffer = Buffer.from(samplePngBase64, 'base64');

fs.writeFileSync('icon-192.png', buffer);
fs.writeFileSync('icon-512.png', buffer);
console.log('Ícones gerados com sucesso!');
