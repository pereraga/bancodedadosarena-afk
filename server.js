const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = 3000;
const PUBLIC_DIR = __dirname;
const UPLOADS_DIR = path.join(__dirname, 'uploads');
const DB_FILE = path.join(__dirname, 'database.json');

// Criar pasta de uploads se não existir
if (!fs.existsSync(UPLOADS_DIR)) {
  fs.mkdirSync(UPLOADS_DIR, { recursive: true });
}

function carregarBanco() {
  try {
    if (fs.existsSync(DB_FILE)) {
      const data = JSON.parse(fs.readFileSync(DB_FILE, 'utf-8'));
      if (!data.devices) data.devices = [];
      if (!data.videos) data.videos = [];
      return data;
    }
  } catch (e) {
    console.error('Erro ao ler database.json:', e);
  }

  return {
    devices: [],
    currentVideo: {
      id: 'demo-1',
      title: 'Vídeo Demonstrativo (Natureza)',
      url: 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4',
      updatedAt: new Date().toISOString()
    },
    videos: [
      {
        id: 'demo-1',
        title: 'Vídeo Demonstrativo (Natureza)',
        url: 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4',
        fileSizeMb: '15.2',
        createdAt: new Date().toISOString()
      },
      {
        id: 'demo-2',
        title: 'Vídeo Demonstrativo (Animação)',
        url: 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4',
        fileSizeMb: '22.8',
        createdAt: new Date().toISOString()
      }
    ]
  };
}

let db = carregarBanco();

function salvarBanco() {
  try {
    fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2), 'utf-8');
  } catch (e) {
    console.error('Erro ao salvar database.json:', e);
  }
}

// Clientes ouvindo em tempo real via Server-Sent Events (SSE)
const sseClients = new Set();

function broadcastEvent(eventType, data) {
  const payload = `event: ${eventType}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const client of sseClients) {
    try {
      client.write(payload);
    } catch (err) {
      sseClients.delete(client);
    }
  }
}

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css',
  '.js': 'application/javascript',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.mov': 'video/quicktime',
  '.ico': 'image/x-icon'
};

const server = http.createServer((req, res) => {
  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Range');
  res.setHeader('Access-Control-Expose-Headers', 'Content-Range, Accept-Ranges, Content-Length');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const url = new URL(req.url, `http://${req.headers.host}`);

  // 1. Endpoint SSE para eventos em tempo real
  if (url.pathname === '/api/events') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive'
    });

    res.write(`event: init\ndata: ${JSON.stringify(db)}\n\n`);
    sseClients.add(res);

    req.on('close', () => {
      sseClients.delete(res);
    });
    return;
  }

  // 2. Estado geral
  if (url.pathname === '/api/state' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(db));
    return;
  }

  // 3. REGISTRO E DESCOBERTA DE TOTEMS NA REDE WI-FI
  if (url.pathname === '/api/devices/register' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const { deviceId, deviceName } = JSON.parse(body);
        const ip = req.socket.remoteAddress.replace('::ffff:', '');

        let device = db.devices.find(d => d.id === deviceId);
        if (!device) {
          device = {
            id: deviceId || 'totem-' + Math.random().toString(36).substring(2, 8),
            deviceName: deviceName || 'Modelo Totem',
            ipAddress: ip,
            status: 'pending', // 'pending' aguardando o App Controle autorizar
            currentVideoId: db.currentVideo ? db.currentVideo.id : null,
            lastSeen: new Date().toISOString()
          };
          db.devices.push(device);
        } else {
          device.ipAddress = ip;
          device.lastSeen = new Date().toISOString();
          if (deviceName) device.deviceName = deviceName;
        }

        salvarBanco();
        broadcastEvent('device-discovered', device);
        broadcastEvent('devices-list', db.devices);

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, device }));
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  // 4. APROVAÇÃO OU RECUSA DO TOTEM PELO CONTROLE
  if (url.pathname === '/api/devices/authorize' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const { deviceId, approve } = JSON.parse(body);
        const device = db.devices.find(d => d.id === deviceId);

        if (device) {
          device.status = approve ? 'approved' : 'rejected';
          device.lastSeen = new Date().toISOString();
          salvarBanco();

          broadcastEvent('device-authorized', device);
          broadcastEvent('devices-list', db.devices);

          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ success: true, device }));
        } else {
          res.writeHead(404, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Dispositivo não encontrado' }));
        }
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  // 5. LISTA DE DISPOSITIVOS
  if (url.pathname === '/api/devices' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(db.devices));
    return;
  }

  // 6. UPLOAD DE VÍDEO DIRETO (LOCAL COM STREAMING)
  if (url.pathname === '/api/upload' && req.method === 'POST') {
    const contentType = req.headers['content-type'] || '';
    if (!contentType.includes('multipart/form-data')) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Content-Type deve ser multipart/form-data' }));
      return;
    }

    const boundary = '--' + contentType.split('boundary=')[1];
    const chunks = [];

    req.on('data', chunk => chunks.push(chunk));
    req.on('end', () => {
      try {
        const buffer = Buffer.concat(chunks);
        const parts = parseMultipart(buffer, boundary);

        let videoTitle = 'Vídeo sem título';
        let fileBuffer = null;
        let originalFileName = 'video.mp4';

        for (const part of parts) {
          if (part.name === 'title') {
            videoTitle = part.data.toString('utf-8').trim();
          } else if (part.name === 'videoFile') {
            fileBuffer = part.data;
            originalFileName = part.filename || 'video.mp4';
          }
        }

        if (!fileBuffer || fileBuffer.length === 0) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Nenhum arquivo de vídeo foi enviado' }));
          return;
        }

        const ext = path.extname(originalFileName) || '.mp4';
        const fileName = `video-${Date.now()}${ext}`;
        const filePath = path.join(UPLOADS_DIR, fileName);
        fs.writeFileSync(filePath, fileBuffer);

        const fileSizeMb = (fileBuffer.length / (1024 * 1024)).toFixed(2);
        const newVideo = {
          id: 'vid-' + Date.now(),
          title: videoTitle || originalFileName,
          url: `/uploads/${fileName}`,
          fileSizeMb,
          createdAt: new Date().toISOString()
        };

        db.videos.unshift(newVideo);
        salvarBanco();

        broadcastEvent('catalog-updated', db.videos);

        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, video: newVideo }));
      } catch (err) {
        console.error('Erro no upload local:', err);
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: err.message }));
      }
    });
    return;
  }

  // 7. REGISTRAR VÍDEO DO SUPABASE
  if (url.pathname === '/api/videos/register-supabase' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const videoData = JSON.parse(body);
        const newVid = {
          id: videoData.id || 'supa-' + Date.now(),
          title: videoData.title,
          url: videoData.videoUrl,
          fileSizeMb: videoData.fileSizeMb || 'N/A',
          storagePath: videoData.storagePath,
          createdAt: new Date().toISOString()
        };
        db.videos.unshift(newVid);
        salvarBanco();
        broadcastEvent('catalog-updated', db.videos);
        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, video: newVid }));
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  // 8. TROCAR VÍDEO EM TEMPO REAL
  if (url.pathname === '/api/change-video' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const { videoId, targetDeviceId } = JSON.parse(body);
        const selectedVideo = db.videos.find(v => v.id === videoId);

        if (selectedVideo) {
          db.currentVideo = {
            ...selectedVideo,
            targetDeviceId: targetDeviceId || null,
            updatedAt: new Date().toISOString()
          };
          salvarBanco();

          broadcastEvent('video-changed', db.currentVideo);

          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ success: true, currentVideo: db.currentVideo }));
        } else {
          res.writeHead(404, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Vídeo não encontrado' }));
        }
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  // 9. EXCLUIR VÍDEO
  if (url.pathname.startsWith('/api/videos/') && req.method === 'DELETE') {
    const videoId = url.pathname.replace('/api/videos/', '');
    db.videos = db.videos.filter(v => v.id !== videoId);
    salvarBanco();
    broadcastEvent('catalog-updated', db.videos);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true }));
    return;
  }

  // 10. SERVIR ARQUIVOS ESTÁTICOS COM SUPORTE A RANGE (Streaming de Vídeo)
  let filePath = path.join(PUBLIC_DIR, url.pathname === '/' ? 'index.html' : url.pathname);
  const ext = path.extname(filePath).toLowerCase();

  fs.stat(filePath, (err, stats) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 - Não encontrado');
      return;
    }

    // Suporte a HTTP Range para reprodução suave de vídeos (HTML5 Video Seek)
    const range = req.headers.range;
    if (range && (ext === '.mp4' || ext === '.webm' || ext === '.mov')) {
      const parts = range.replace(/bytes=/, "").split("-");
      const start = parseInt(parts[0], 10);
      const end = parts[1] ? parseInt(parts[1], 10) : stats.size - 1;
      const chunksize = (end - start) + 1;
      const file = fs.createReadStream(filePath, { start, end });

      res.writeHead(206, {
        'Content-Range': `bytes ${start}-${end}/${stats.size}`,
        'Accept-Ranges': 'bytes',
        'Content-Length': chunksize,
        'Content-Type': MIME_TYPES[ext] || 'video/mp4'
      });
      file.pipe(res);
      return;
    }

    res.writeHead(200, {
      'Content-Length': stats.size,
      'Content-Type': MIME_TYPES[ext] || 'application/octet-stream'
    });
    fs.createReadStream(filePath).pipe(res);
  });
});

// Parser simples para Multipart sem dependências
function parseMultipart(buffer, boundary) {
  const parts = [];
  const boundaryBuffer = Buffer.from(boundary);
  let start = 0;

  while ((start = buffer.indexOf(boundaryBuffer, start)) !== -1) {
    start += boundaryBuffer.length;
    if (buffer.slice(start, start + 2).toString() === '--') break;
    if (buffer.slice(start, start + 2).toString() === '\r\n') start += 2;

    const end = buffer.indexOf(boundaryBuffer, start);
    if (end === -1) break;

    const partBuffer = buffer.slice(start, end - 2);
    const headerEnd = partBuffer.indexOf('\r\n\r\n');
    if (headerEnd !== -1) {
      const headerStr = partBuffer.slice(0, headerEnd).toString('utf-8');
      const data = partBuffer.slice(headerEnd + 4);

      const nameMatch = headerStr.match(/name="([^"]+)"/);
      const filenameMatch = headerStr.match(/filename="([^"]+)"/);

      parts.push({
        name: nameMatch ? nameMatch[1] : null,
        filename: filenameMatch ? filenameMatch[1] : null,
        data
      });
    }
  }
  return parts;
}

function getLocalIpAddresses() {
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        addresses.push(iface.address);
      }
    }
  }
  return addresses;
}

server.listen(PORT, '0.0.0.0', () => {
  const ips = getLocalIpAddresses();
  console.log(`\n======================================================`);
  console.log(`  🚀 TOTEM PLAY - SISTEMA 2 APPS & SUPABASE ATIVO`);
  console.log(`======================================================`);
  console.log(`💻 Hub Central:          http://localhost:${PORT}`);
  console.log(`📱 App 1 (Tela Totem):   http://localhost:${PORT}/screen.html`);
  console.log(`🎮 App 2 (Controle):     http://localhost:${PORT}/control.html`);
  if (ips.length > 0) {
    console.log(`------------------------------------------------------`);
    ips.forEach(ip => {
      console.log(`📶 Acesso no Wi-Fi:`);
      console.log(`   - Tela Base:  http://${ip}:${PORT}/screen.html`);
      console.log(`   - Controle:   http://${ip}:${PORT}/control.html`);
    });
  }
  console.log(`======================================================\n`);
});
