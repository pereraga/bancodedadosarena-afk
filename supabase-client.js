/**
 * Supabase & Local Engine - Gerenciamento de Upload, Sincronização em Tempo Real e Dispositivos
 */
class SupabaseEngine {
  constructor() {
    this.client = null;
    this.config = null;
    this.isSupabaseConnected = false;
    this.sseSource = null;
    this.deviceId = this.getOrCreateDeviceId();
    this.deviceName = localStorage.getItem('totem_device_name') || 'Modelo Totem';

    this.listeners = {
      onVideoChanged: null,
      onCatalogUpdated: null,
      onDevicesUpdated: null,
      onDeviceStatusChanged: null,
      onNewDevicePending: null
    };

    this.loadConfig();
  }

  getOrCreateDeviceId() {
    let id = localStorage.getItem('totem_device_id');
    if (!id) {
      id = 'totem-' + Math.random().toString(36).substring(2, 9);
      localStorage.setItem('totem_device_id', id);
    }
    return id;
  }

  loadConfig() {
    try {
      const saved = localStorage.getItem('totem_supabase_config');
      if (saved) {
        this.config = JSON.parse(saved);
        if (this.config.url && this.config.anonKey) {
          this.initSupabase(this.config.url, this.config.anonKey);
          return;
        }
      }
    } catch (e) {
      console.warn('Configuração do Supabase não encontrada. Usando modo local.', e);
    }
    this.initLocalMode();
  }

  saveSupabaseConfig(url, anonKey) {
    if (!url || !anonKey) throw new Error('URL e Anon Key do Supabase são obrigatórios');
    const cfg = { url: url.trim(), anonKey: anonKey.trim() };
    localStorage.setItem('totem_supabase_config', JSON.stringify(cfg));
    this.config = cfg;
    this.initSupabase(cfg.url, cfg.anonKey);
  }

  clearSupabaseConfig() {
    localStorage.removeItem('totem_supabase_config');
    this.isSupabaseConnected = false;
    this.client = null;
    this.initLocalMode();
  }

  initSupabase(url, anonKey) {
    try {
      if (window.supabase) {
        this.client = window.supabase.createClient(url, anonKey);
        this.isSupabaseConnected = true;
        console.log('✅ Conectado ao Supabase Cloud com sucesso!');

        // Configurar Supabase Realtime Channel para comandos instantâneos
        const channel = this.client.channel('totem-broadcast');
        channel
          .on('broadcast', { event: 'PLAY_VIDEO' }, payload => {
            console.log('⚡ Ordem do Supabase Realtime recebida:', payload);
            if (this.listeners.onVideoChanged) {
              this.listeners.onVideoChanged(payload.payload);
            }
          })
          .on('broadcast', { event: 'DEVICE_AUTHORIZED' }, payload => {
            if (this.listeners.onDeviceStatusChanged) {
              this.listeners.onDeviceStatusChanged(payload.payload);
            }
          })
          .subscribe();

        // Ouvir alterações no banco Supabase
        this.client
          .channel('public:videos')
          .on('postgres_changes', { event: '*', schema: 'public', table: 'videos' }, () => {
            this.fetchVideosFromSupabase();
          })
          .subscribe();

        this.client
          .channel('public:devices')
          .on('postgres_changes', { event: '*', schema: 'public', table: 'devices' }, payload => {
            this.fetchDevicesFromSupabase();
          })
          .subscribe();

        this.fetchVideosFromSupabase();
        this.fetchDevicesFromSupabase();
      }
    } catch (err) {
      console.error('Falha ao inicializar cliente Supabase:', err);
    }
    // Mantém também o SSE local para garantia e pareamento no mesmo Wi-Fi
    this.initLocalMode();
  }

  async fetchVideosFromSupabase() {
    if (!this.client) return;
    const { data, error } = await this.client
      .from('videos')
      .select('*')
      .orderBy('created_at', { ascending: false });
    if (!error && data) {
      const list = data.map(v => ({
        id: v.id,
        title: v.title,
        url: v.video_url,
        fileSizeMb: v.file_size_mb,
        createdAt: v.created_at
      }));
      if (this.listeners.onCatalogUpdated) {
        this.listeners.onCatalogUpdated(list);
      }
    }
  }

  async fetchDevicesFromSupabase() {
    if (!this.client) return;
    const { data, error } = await this.client.from('devices').select('*');
    if (!error && data) {
      const mapped = data.map(d => ({
        id: d.id,
        deviceName: d.device_name,
        ipAddress: d.ip_address,
        status: d.status,
        lastSeen: d.last_seen
      }));
      if (this.listeners.onDevicesUpdated) {
        this.listeners.onDevicesUpdated(mapped);
      }
    }
  }

  initLocalMode() {
    if (this.sseSource) this.sseSource.close();

    try {
      this.sseSource = new EventSource('/api/events');

      this.sseSource.addEventListener('init', (e) => {
        const data = JSON.parse(e.data);
        if (!this.isSupabaseConnected) {
          if (this.listeners.onCatalogUpdated && data.videos) {
            this.listeners.onCatalogUpdated(data.videos);
          }
        }
        if (this.listeners.onDevicesUpdated && data.devices) {
          this.listeners.onDevicesUpdated(data.devices);
        }
        if (this.listeners.onVideoChanged && data.currentVideo) {
          this.listeners.onVideoChanged(data.currentVideo);
        }
      });

      this.sseSource.addEventListener('video-changed', (e) => {
        const video = JSON.parse(e.data);
        if (this.listeners.onVideoChanged) {
          this.listeners.onVideoChanged(video);
        }
      });

      this.sseSource.addEventListener('device-discovered', (e) => {
        const device = JSON.parse(e.data);
        if (this.listeners.onNewDevicePending) {
          this.listeners.onNewDevicePending(device);
        }
      });

      this.sseSource.addEventListener('device-authorized', (e) => {
        const device = JSON.parse(e.data);
        if (this.listeners.onDeviceStatusChanged) {
          this.listeners.onDeviceStatusChanged(device);
        }
      });

      this.sseSource.addEventListener('devices-list', (e) => {
        const list = JSON.parse(e.data);
        if (this.listeners.onDevicesUpdated) {
          this.listeners.onDevicesUpdated(list);
        }
      });

      this.sseSource.addEventListener('catalog-updated', (e) => {
        if (!this.isSupabaseConnected) {
          const list = JSON.parse(e.data);
          if (this.listeners.onCatalogUpdated) {
            this.listeners.onCatalogUpdated(list);
          }
        }
      });
    } catch (e) {
      console.warn('Erro ao conectar SSE:', e);
    }
  }

  // Anunciar presença do Totem na rede
  async registerDevice(deviceName) {
    this.deviceName = deviceName || this.deviceName;
    localStorage.setItem('totem_device_name', this.deviceName);

    // Registro no servidor local
    const localRes = await fetch('/api/devices/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId: this.deviceId,
        deviceName: this.deviceName
      })
    });
    const localData = await localRes.json();

    // Se o Supabase estiver conectado, registra na tabela devices também
    if (this.client) {
      try {
        await this.client.from('devices').upsert({
          id: this.deviceId,
          device_name: this.deviceName,
          status: 'pending',
          last_seen: new Date().toISOString()
        });
      } catch (err) {
        console.warn('Erro ao registrar device no Supabase:', err);
      }
    }

    return localData.device;
  }

  // Autorizar ou recusar Totem (pelo App de Controle)
  async authorizeDevice(deviceId, approve = true) {
    const res = await fetch('/api/devices/authorize', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId, approve })
    });

    if (this.client) {
      try {
        await this.client.from('devices').update({
          status: approve ? 'approved' : 'rejected',
          last_seen: new Date().toISOString()
        }).eq('id', deviceId);

        // Disparar via Realtime Broadcast
        const channel = this.client.channel('totem-broadcast');
        await channel.send({
          type: 'broadcast',
          event: 'DEVICE_AUTHORIZED',
          payload: { id: deviceId, status: approve ? 'approved' : 'rejected' }
        });
      } catch (err) {
        console.warn('Erro ao autorizar no Supabase:', err);
      }
    }

    return await res.json();
  }

  // UPLOAD DIRETO DE VÍDEO COM NOME PERSONALIZADO
  async uploadVideo(file, videoTitle, onProgress = () => {}) {
    if (!file) throw new Error('Selecione um arquivo de vídeo');
    if (!videoTitle) throw new Error('Informe o nome do vídeo');

    const fileSizeMb = (file.size / (1024 * 1024)).toFixed(2);

    // 1. Se estiver conectado ao Supabase, faz upload no Supabase Storage!
    if (this.client && this.isSupabaseConnected) {
      onProgress(10);
      const ext = file.name.split('.').pop() || 'mp4';
      const cleanName = videoTitle.toLowerCase().replace(/[^a-z0-9]/g, '-');
      const storagePath = `uploads/${Date.now()}-${cleanName}.${ext}`;

      // Upload para o Bucket 'videos'
      onProgress(30);
      const { data: uploadData, error: uploadError } = await this.client.storage
        .from('videos')
        .upload(storagePath, file, {
          cacheControl: '3600',
          upsert: false
        });

      if (uploadError) {
        throw new Error('Falha no upload do Supabase Storage: ' + uploadError.message);
      }

      onProgress(75);
      // Obter URL pública do vídeo
      const { data: urlData } = this.client.storage
        .from('videos')
        .getPublicUrl(storagePath);

      const videoUrl = urlData.publicUrl;

      // Gravar na tabela 'videos' do Supabase com o título que o usuário definiu
      onProgress(90);
      const { data: dbVideo, error: dbError } = await this.client
        .from('videos')
        .insert({
          title: videoTitle,
          storage_path: storagePath,
          video_url: videoUrl,
          file_size_mb: parseFloat(fileSizeMb)
        })
        .select()
        .single();

      if (dbError) {
        console.warn('Erro ao gravar metadados no Supabase:', dbError);
      }

      // Sincronizar com o backend local também
      await fetch('/api/videos/register-supabase', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          id: dbVideo ? dbVideo.id : 'supa-' + Date.now(),
          title: videoTitle,
          videoUrl,
          fileSizeMb,
          storagePath
        })
      });

      onProgress(100);
      return {
        id: dbVideo ? dbVideo.id : 'vid-' + Date.now(),
        title: videoTitle,
        url: videoUrl,
        fileSizeMb
      };
    }

    // 2. Modo Local (Upload direto no servidor local via multipart)
    return new Promise((resolve, reject) => {
      const formData = new FormData();
      formData.append('title', videoTitle);
      formData.append('videoFile', file);

      const xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/upload', true);

      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable) {
          const percent = Math.round((event.loaded / event.total) * 100);
          onProgress(percent);
        }
      };

      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          const result = JSON.parse(xhr.responseText);
          resolve(result.video);
        } else {
          reject(new Error('Erro no upload local: ' + xhr.responseText));
        }
      };

      xhr.onerror = () => reject(new Error('Erro de conexão no upload'));
      xhr.send(formData);
    });
  }

  // TRANSMITIR VÍDEO EM TEMPO REAL
  async playVideoOnTotem(video) {
    // 1. Notificar servidor local
    await fetch('/api/change-video', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ videoId: video.id })
    });

    // 2. Se Supabase estiver conectado, envia via Supabase Realtime Broadcast
    if (this.client) {
      try {
        const channel = this.client.channel('totem-broadcast');
        await channel.send({
          type: 'broadcast',
          event: 'PLAY_VIDEO',
          payload: {
            id: video.id,
            title: video.title,
            url: video.url,
            updatedAt: new Date().toISOString()
          }
        });
      } catch (err) {
        console.warn('Erro ao emitir Realtime Broadcast no Supabase:', err);
      }
    }
  }

  async deleteVideo(videoId) {
    await fetch(`/api/videos/${videoId}`, { method: 'DELETE' });
    if (this.client) {
      try {
        await this.client.from('videos').delete().eq('id', videoId);
      } catch (err) {
        console.warn('Erro ao deletar no Supabase:', err);
      }
    }
  }
}

window.supabaseEngine = new SupabaseEngine();
