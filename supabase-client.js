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
      }
    } catch (e) {
      console.warn('Configuração salva não carregada:', e);
    }
    if (!this.config || !this.config.url) {
      this.config = {
        url: 'https://qvnsahvdjhimlmtqrnif.supabase.co',
        anonKey: 'sb_publishable_vvqh9vB0Dr0EqR9JGhy4kA_XnJ-9h0z'
      };
    }
    this.initSupabase(this.config.url, this.config.anonKey);
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
    this.config = {
      url: 'https://qvnsahvdjhimlmtqrnif.supabase.co',
      anonKey: 'sb_publishable_vvqh9vB0Dr0EqR9JGhy4kA_XnJ-9h0z'
    };
    this.initSupabase(this.config.url, this.config.anonKey);
  }

  initSupabase(url, anonKey) {
    try {
      if (window.supabase) {
        this.client = window.supabase.createClient(url, anonKey);
        this.isSupabaseConnected = true;
        console.log('✅ Conectado ao Supabase Cloud com sucesso!');

        // Configurar Supabase Realtime Channels para comandos instantâneos
        const channel = this.client.channel('totem-broadcast');
        channel
          .on('broadcast', { event: 'PLAY_VIDEO' }, payload => {
            console.log('⚡ Ordem PLAY_VIDEO recebida:', payload);
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

        const globalChannel = this.client.channel('totem-global-channel');
        globalChannel
          .on('broadcast', { event: 'PLAY_VIDEO' }, payload => {
            if (this.listeners.onVideoChanged) {
              this.listeners.onVideoChanged(payload.payload);
            }
          })
          .on('broadcast', { event: 'DEVICE_STATUS' }, payload => {
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
    // SSE local como fallback
    this.initLocalMode();
  }

  async fetchVideosFromSupabase() {
    if (!this.client) return;
    const { data, error } = await this.client
      .from('videos')
      .select('*')
      .order('created_at', { ascending: false });
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
    let list = [];
    if (this.client) {
      try {
        const { data, error } = await this.client.from('devices').select('*');
        if (!error && data) {
          list = data.map(d => ({
            id: d.id,
            deviceName: d.device_name,
            ipAddress: d.ip_address,
            status: (d.status === 'rejected' || d.status === 'unlinked') ? d.status : 'approved',
            lastSeen: d.last_seen
          }));
        }
      } catch (e) {}
    }

    // Mesclar com dispositivos salvos localmente
    try {
      const localSaved = JSON.parse(localStorage.getItem('totem_bound_devices') || '[]');
      localSaved.forEach(ld => {
        if (!list.some(d => d.id === ld.id)) {
          list.push(ld);
        }
      });
    } catch (e) {}

    if (this.listeners.onDevicesUpdated) {
      this.listeners.onDevicesUpdated(list);
    }
    return list;
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
          status: 'approved',
          last_seen: new Date().toISOString()
        });
      } catch (err) {
        console.warn('Erro ao registrar device no Supabase:', err);
      }
    }

    return localData.device;
  }

  // Vincular Totem manualmente por ID e dar autorização imediata
  async bindAndAuthorizeDevice(deviceId, deviceName = 'Modelo Totem') {
    if (!deviceId) throw new Error('Informe o ID do Totem');
    deviceId = deviceId.trim();

    // 1. Salvar no localStorage para garantia de persistência local
    try {
      const bound = JSON.parse(localStorage.getItem('totem_bound_devices') || '[]');
      const idx = bound.findIndex(d => d.id === deviceId);
      const devObj = {
        id: deviceId,
        deviceName: deviceName || 'Modelo Totem',
        status: 'approved',
        lastSeen: new Date().toISOString()
      };
      if (idx >= 0) bound[idx] = devObj;
      else bound.push(devObj);
      localStorage.setItem('totem_bound_devices', JSON.stringify(bound));
    } catch (e) {}

    // 2. Salvar no Supabase (se a tabela estiver criada)
    if (this.client) {
      try {
        await this.client
          .from('devices')
          .upsert({
            id: deviceId,
            device_name: deviceName || 'Modelo Totem',
            status: 'approved',
            last_seen: new Date().toISOString()
          });
      } catch (err) {
        console.warn('Aviso ao salvar no Supabase:', err);
      }

      // 3. Emitir Realtime Broadcasts instantâneos para destravar a tela
      try {
        // Canal individual do Totem
        const screenChan = this.client.channel(`device-${deviceId}`);
        await screenChan.send({
          type: 'broadcast',
          event: 'AUTHORIZATION',
          payload: { id: deviceId, status: 'approved', deviceName: deviceName || 'Modelo Totem' }
        });

        // Canal global
        const globalChan = this.client.channel('totem-global-channel');
        await globalChan.send({
          type: 'broadcast',
          event: 'DEVICE_STATUS',
          payload: { id: deviceId, status: 'approved', deviceName: deviceName || 'Modelo Totem' }
        });

        // Canal broadcast
        const broadChan = this.client.channel('totem-broadcast');
        await broadChan.send({
          type: 'broadcast',
          event: 'DEVICE_AUTHORIZED',
          payload: { id: deviceId, status: 'approved', deviceName: deviceName || 'Modelo Totem' }
        });
      } catch (err) {
        console.warn('Aviso ao emitir Realtime Broadcast:', err);
      }
    }

    try {
      await fetch('/api/devices/authorize', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId, approve: true })
      });
    } catch (e) {}

    await this.fetchDevicesFromSupabase();
    return { id: deviceId, status: 'approved', deviceName };
  }

  // Autorizar ou recusar Totem (pelo App de Controle)
  async authorizeDevice(deviceId, approve = true) {
    deviceId = String(deviceId).trim();
    if (!approve) {
      return await this.deleteDevice(deviceId);
    }

    const newStatus = 'approved';

    // Atualizar no localStorage
    try {
      let bound = JSON.parse(localStorage.getItem('totem_bound_devices') || '[]');
      const found = bound.find(d => d.id === deviceId);
      if (found) found.status = 'approved';
      localStorage.setItem('totem_bound_devices', JSON.stringify(bound));
    } catch (e) {}

    if (this.client) {
      try {
        await this.client.from('devices').upsert({
          id: deviceId,
          status: newStatus,
          last_seen: new Date().toISOString()
        });
      } catch (err) {}

      try {
        // Disparar via Realtime Broadcast para todos os canais possíveis
        const screenChan = this.client.channel(`device-${deviceId}`);
        await screenChan.send({
          type: 'broadcast',
          event: 'AUTHORIZATION',
          payload: { id: deviceId, status: newStatus }
        });

        const globalChan = this.client.channel('totem-global-channel');
        await globalChan.send({
          type: 'broadcast',
          event: 'DEVICE_STATUS',
          payload: { id: deviceId, status: newStatus }
        });

        const broadChan = this.client.channel('totem-broadcast');
        await broadChan.send({
          type: 'broadcast',
          event: 'DEVICE_AUTHORIZED',
          payload: { id: deviceId, status: newStatus }
        });
      } catch (err) {}
    }

    await this.fetchDevicesFromSupabase();
    return { id: deviceId, status: newStatus };
  }

  // Desvincular e Excluir Totem Permanentemente
  async deleteDevice(deviceId) {
    deviceId = String(deviceId).trim();

    if (this.client) {
      try {
        // Notificar a tela que foi desvinculada antes de remover
        const screenChan = this.client.channel(`device-${deviceId}`);
        await screenChan.send({
          type: 'broadcast',
          event: 'AUTHORIZATION',
          payload: { id: deviceId, status: 'unlinked' }
        });

        const globalChan = this.client.channel('totem-global-channel');
        await globalChan.send({
          type: 'broadcast',
          event: 'DEVICE_STATUS',
          payload: { id: deviceId, status: 'unlinked' }
        });

        // Excluir permanentemente do banco de dados
        await this.client.from('devices').delete().eq('id', deviceId);
      } catch (err) {
        console.warn('Erro ao deletar totem do Supabase:', err);
      }
    }

    try {
      let bound = JSON.parse(localStorage.getItem('totem_bound_devices') || '[]');
      bound = bound.filter(d => d.id !== deviceId);
      localStorage.setItem('totem_bound_devices', JSON.stringify(bound));
    } catch (e) {}

    await this.fetchDevicesFromSupabase();
    return { id: deviceId, deleted: true };
  }

  // UPLOAD DIRETO DE VÍDEO COM NOME PERSONALIZADO
  async uploadVideo(file, videoTitle, onProgress = () => {}) {
    if (!file) throw new Error('Selecione um arquivo de vídeo');
    if (!videoTitle) throw new Error('Informe o nome do vídeo');

    const fileSizeMb = (file.size / (1024 * 1024)).toFixed(2);
    const formattedSize = file.size >= 1024 * 1024 * 1024
      ? (file.size / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
      : fileSizeMb + ' MB';

    // 1. Se estiver conectado ao Supabase, faz upload no Supabase Storage (até 200 MB)
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
        if (uploadError.message && (uploadError.message.includes('Bucket not found') || uploadError.message.includes('bucket_not_found'))) {
          throw new Error('O bucket "videos" ainda não foi criado no Supabase. Abra o SQL Editor no painel do Supabase e execute o script para criar o bucket "videos" público com limite de 200 MB.');
        }
        if (uploadError.message && (uploadError.message.includes('exceeded the maximum allowed size') || uploadError.message.includes('Payload too large'))) {
          throw new Error(`O arquivo possui ${formattedSize}.\n\nO plano gratuito do Supabase possui um limite rígido de 50 MB por arquivo no Storage.\n\nPara usar vídeos maiores que 50 MB (como este de ${formattedSize}), use a "Opção 2: Inserir Link Direto do Vídeo" logo abaixo — aceita links do Google Drive, Dropbox ou links diretos .mp4 sem limite!`);
        }
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
      try {
        await fetch('/api/videos/register-supabase', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            id: dbVideo ? dbVideo.id : 'supa-' + Date.now(),
            title: videoTitle,
            videoUrl,
            fileSizeMb: formattedSize,
            storagePath
          })
        });
      } catch (e) {}

      onProgress(100);
      await this.fetchVideosFromSupabase();

      return {
        id: dbVideo ? dbVideo.id : 'vid-' + Date.now(),
        title: videoTitle,
        url: videoUrl,
        fileSizeMb: formattedSize
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

  // ADICIONAR VÍDEO DIRETAMENTE POR LINK/URL (Sem Limite de Tamanho)
  async addVideoByUrl(videoTitle, videoUrl) {
    if (!videoTitle) throw new Error('Informe o nome do vídeo');
    if (!videoUrl) throw new Error('Informe o link direto do vídeo');

    let cleanUrl = videoUrl.trim();
    const cleanTitle = videoTitle.trim();

    // Suporte automático para links do Google Drive (Streaming via API do Totem)
    if (cleanUrl.includes('drive.google.com') || cleanUrl.includes('drive.usercontent.google.com')) {
      const gmatch = cleanUrl.match(/\/file\/d\/([a-zA-Z0-9_-]+)/) || cleanUrl.match(/[?&]id=([a-zA-Z0-9_-]+)/);
      if (gmatch && gmatch[1]) {
        cleanUrl = `/api/stream?id=${gmatch[1]}`;
      }
    }

    // Suporte automático para Dropbox
    if (cleanUrl.includes('dropbox.com')) {
      cleanUrl = cleanUrl.replace(/[?&]dl=0/, '?raw=1').replace(/[?&]dl=1/, '?raw=1');
      if (!cleanUrl.includes('raw=1')) {
        cleanUrl += (cleanUrl.includes('?') ? '&' : '?') + 'raw=1';
      }
    }

    if (this.client && this.isSupabaseConnected) {
      const { data: dbVideo, error: dbError } = await this.client
        .from('videos')
        .insert({
          title: cleanTitle,
          storage_path: 'external-url',
          video_url: cleanUrl,
          file_size_mb: null
        })
        .select()
        .single();

      if (dbError) {
        throw new Error('Erro ao salvar vídeo no Supabase: ' + dbError.message);
      }

      try {
        await fetch('/api/videos/register-supabase', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            id: dbVideo ? dbVideo.id : 'supa-' + Date.now(),
            title: cleanTitle,
            videoUrl: cleanUrl,
            fileSizeMb: 'Link Direto',
            storagePath: 'external-url'
          })
        });
      } catch (e) {}

      await this.fetchVideosFromSupabase();

      return {
        id: dbVideo ? dbVideo.id : 'vid-' + Date.now(),
        title: cleanTitle,
        url: cleanUrl,
        fileSizeMb: 'Link Direto'
      };
    } else {
      // Modo local
      const res = await fetch('/api/videos/register-supabase', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          id: 'local-' + Date.now(),
          title: cleanTitle,
          videoUrl: cleanUrl,
          fileSizeMb: 'Link Direto',
          storagePath: 'external-url'
        })
      });
      const data = await res.json();
      await this.fetchVideosFromSupabase();
      return data;
    }
  }

  // TRANSMITIR VÍDEO EM TEMPO REAL
  async playVideoOnTotem(video, targetDeviceId = null) {
    let mediaUrl = video.video_url || video.url;
    if (mediaUrl && mediaUrl.startsWith('/')) {
      mediaUrl = 'https://totemarena.vercel.app' + mediaUrl;
    }
    const payload = {
      id: video.id,
      title: video.title,
      url: mediaUrl,
      targetDeviceId,
      updatedAt: new Date().toISOString()
    };

    if (this.client) {
      try {
        // 1. Enviar para canal broadcast compatível
        const broadChan = this.client.channel('totem-broadcast');
        await broadChan.send({
          type: 'broadcast',
          event: 'PLAY_VIDEO',
          payload
        });

        // 2. Enviar para canal broadcast global
        const globalChan = this.client.channel('totem-global-channel');
        await globalChan.send({
          type: 'broadcast',
          event: 'PLAY_VIDEO',
          payload
        });

        // 3. Se for para um totem específico, enviar no canal do aparelho
        if (targetDeviceId) {
          const screenChan = this.client.channel(`device-${targetDeviceId}`);
          await screenChan.send({
            type: 'broadcast',
            event: 'PLAY_VIDEO',
            payload
          });

          await this.client
            .from('devices')
            .update({ current_video_id: video.id, status: 'approved' })
            .eq('id', targetDeviceId);
        } else {
          // Atualizar todos os dispositivos ativos com este vídeo
          await this.client
            .from('devices')
            .update({ current_video_id: video.id, status: 'approved' })
            .neq('status', 'rejected');
        }
      } catch (err) {
        console.warn('Aviso ao emitir Realtime Broadcast no Supabase:', err);
      }
    }

    try {
      await fetch('/api/change-video', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ videoId: video.id })
      });
    } catch (e) {}
  }

  async deleteVideo(videoId, storagePath = null) {
    if (this.client) {
      try {
        if (storagePath) {
          await this.client.storage.from('videos').remove([storagePath]);
        }
        await this.client.from('videos').delete().eq('id', videoId);
        await this.fetchVideosFromSupabase();
      } catch (err) {
        console.warn('Erro ao deletar no Supabase:', err);
      }
    }
    try {
      await fetch(`/api/videos/${videoId}`, { method: 'DELETE' });
    } catch (e) {}
  }
}

window.supabaseEngine = new SupabaseEngine();
