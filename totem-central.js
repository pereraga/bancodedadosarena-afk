/**
 * Totem Central Engine - Supabase Cloud & Realtime
 */
class TotemCentralEngine {
  constructor() {
    this.client = null;
    this.currentUser = null;
    this.isConfigured = false;
    this.config = this.loadConfig();

    this.listeners = {
      onAuthStateChanged: null,
      onDevicesChanged: null,
      onCatalogChanged: null,
      onVideoCommandReceived: null,
      onDeviceApproved: null
    };

    this.init();
  }

  loadConfig() {
    try {
      const saved = localStorage.getItem('totem_central_supabase_config');
      if (saved) {
        return JSON.parse(saved);
      }
    } catch (e) {
      console.warn('Configuração não encontrada no localStorage:', e);
    }
    return null;
  }

  saveConfig(url, anonKey) {
    if (!url || !anonKey) throw new Error('URL e Chave Anon do Supabase são obrigatórias');
    const cfg = { url: url.trim(), anonKey: anonKey.trim() };
    localStorage.setItem('totem_central_supabase_config', JSON.stringify(cfg));
    this.config = cfg;
    this.init();
  }

  clearConfig() {
    localStorage.removeItem('totem_central_supabase_config');
    this.config = null;
    this.client = null;
    this.isConfigured = false;
    this.currentUser = null;
    window.location.reload();
  }

  init() {
    if (!this.config || !this.config.url || !this.config.anonKey) {
      this.isConfigured = false;
      console.log('⚠️ Totem Central aguardando configuração de chaves do Supabase.');
      return;
    }

    try {
      if (window.supabase) {
        this.client = window.supabase.createClient(this.config.url, this.config.anonKey);
        this.isConfigured = true;

        // Ouvir mudanças de autenticação (Login / Logout / Cadastro)
        this.client.auth.onAuthStateChange((event, session) => {
          this.currentUser = session ? session.user : null;
          if (this.listeners.onAuthStateChanged) {
            this.listeners.onAuthStateChanged(this.currentUser);
          }
          if (this.currentUser) {
            this.setupRealtimeSubscriptions();
            this.refreshCatalog();
            this.refreshDevices();
          }
        });

        // Verificar sessão ativa
        this.client.auth.getSession().then(({ data }) => {
          this.currentUser = data && data.session ? data.session.user : null;
          if (this.listeners.onAuthStateChanged) {
            this.listeners.onAuthStateChanged(this.currentUser);
          }
          if (this.currentUser) {
            this.setupRealtimeSubscriptions();
            this.refreshCatalog();
            this.refreshDevices();
          }
        });
      }
    } catch (e) {
      console.error('Erro ao inicializar Supabase Client:', e);
    }
  }

  // Configurar canais em tempo real (Supabase Realtime)
  setupRealtimeSubscriptions() {
    if (!this.client) return;

    // Canal Broadcast de ordens imediatas para as telas
    const broadcastChannel = this.client.channel('totem-global-channel');
    broadcastChannel
      .on('broadcast', { event: 'PLAY_VIDEO' }, payload => {
        if (this.listeners.onVideoCommandReceived) {
          this.listeners.onVideoCommandReceived(payload.payload);
        }
      })
      .on('broadcast', { event: 'DEVICE_STATUS' }, payload => {
        if (this.listeners.onDeviceApproved) {
          this.listeners.onDeviceApproved(payload.payload);
        }
      })
      .subscribe();

    // Ouvir alterações no banco de dispositivos e vídeos
    this.client
      .channel('db-changes')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'devices' }, () => {
        this.refreshDevices();
      })
      .on('postgres_changes', { event: '*', schema: 'public', table: 'videos' }, () => {
        this.refreshCatalog();
      })
      .subscribe();
  }

  // --- AUTENTICAÇÃO (CADASTRO E LOGIN) ---

  async signUp(email, password) {
    if (!this.isConfigured) throw new Error('Configure o Supabase primeiro nas configurações.');
    const { data, error } = await this.client.auth.signUp({
      email,
      password
    });
    if (error) throw error;
    return data.user;
  }

  async signIn(email, password) {
    if (!this.isConfigured) throw new Error('Configure o Supabase primeiro nas configurações.');
    const { data, error } = await this.client.auth.signInWithPassword({
      email,
      password
    });
    if (error) throw error;
    this.currentUser = data.user;
    return data.user;
  }

  async signOut() {
    if (this.client) {
      await this.client.auth.signOut();
    }
    this.currentUser = null;
    if (this.listeners.onAuthStateChanged) {
      this.listeners.onAuthStateChanged(null);
    }
  }

  // --- DISPOSITIVOS / TELAS TOTEM ---

  async registerScreenDevice(deviceName = 'Modelo Totem') {
    let deviceId = localStorage.getItem('totem_screen_device_id');
    if (!deviceId) {
      deviceId = 'totem-' + Math.random().toString(36).substring(2, 9);
      localStorage.setItem('totem_screen_device_id', deviceId);
    }

    const pairCode = Math.floor(100000 + Math.random() * 900000).toString();

    const deviceData = {
      id: deviceId,
      device_name: deviceName,
      pair_code: pairCode,
      status: 'pending',
      last_seen: new Date().toISOString()
    };

    if (this.client) {
      try {
        await this.client.from('devices').upsert(deviceData);

        // Ouvir aprovação deste dispositivo específico via Realtime
        const screenChannel = this.client.channel(`device-${deviceId}`);
        screenChannel
          .on('broadcast', { event: 'AUTHORIZATION' }, payload => {
            if (this.listeners.onDeviceApproved) {
              this.listeners.onDeviceApproved(payload.payload);
            }
          })
          .on('broadcast', { event: 'PLAY_VIDEO' }, payload => {
            if (this.listeners.onVideoCommandReceived) {
              this.listeners.onVideoCommandReceived(payload.payload);
            }
          })
          .subscribe();

      } catch (err) {
        console.warn('Erro ao registrar tela no Supabase:', err);
      }
    }

    return deviceData;
  }

  async refreshDevices() {
    if (!this.client) return;
    const { data, error } = await this.client
      .from('devices')
      .select('*')
      .order('created_at', { ascending: false });

    if (!error && data) {
      if (this.listeners.onDevicesChanged) {
        this.listeners.onDevicesChanged(data);
      }
    }
  }

  async authorizeDevice(deviceId, approve = true) {
    if (!this.client) return;

    const newStatus = approve ? 'approved' : 'rejected';
    const { error } = await this.client
      .from('devices')
      .update({
        status: newStatus,
        user_id: this.currentUser ? this.currentUser.id : null,
        last_seen: new Date().toISOString()
      })
      .eq('id', deviceId);

    if (error) throw error;

    // Notificar a tela em tempo real via Broadcast
    const channel = this.client.channel(`device-${deviceId}`);
    await channel.send({
      type: 'broadcast',
      event: 'AUTHORIZATION',
      payload: { id: deviceId, status: newStatus }
    });

    const globalChannel = this.client.channel('totem-global-channel');
    await globalChannel.send({
      type: 'broadcast',
      event: 'DEVICE_STATUS',
      payload: { id: deviceId, status: newStatus }
    });

    this.refreshDevices();
  }

  // --- UPLOAD DE VÍDEOS COM NOME NO SUPABASE STORAGE ---

  async uploadVideo(file, videoTitle, onProgress = () => {}) {
    if (!this.isConfigured || !this.client) {
      throw new Error('Configure as credenciais do Supabase na aba Configurações antes de fazer upload.');
    }
    if (!file) throw new Error('Selecione um arquivo de vídeo.');
    if (!videoTitle) throw new Error('Informe um nome para o vídeo.');

    onProgress(10);
    const ext = file.name.split('.').pop() || 'mp4';
    const cleanTitle = videoTitle.toLowerCase().replace(/[^a-z0-9]/g, '-');
    const storagePath = `uploads/${Date.now()}-${cleanTitle}.${ext}`;

    onProgress(25);
    // 1. Upload do Arquivo para o Bucket 'videos'
    const { error: uploadError } = await this.client.storage
      .from('videos')
      .upload(storagePath, file, {
        cacheControl: '3600',
        upsert: false
      });

    if (uploadError) {
      throw new Error('Falha no upload para o Supabase Storage: ' + uploadError.message);
    }

    onProgress(70);
    // 2. Obter URL Pública do Arquivo
    const { data: urlData } = this.client.storage
      .from('videos')
      .getPublicUrl(storagePath);

    const videoUrl = urlData.publicUrl;
    const fileSizeMb = (file.size / (1024 * 1024)).toFixed(2);

    onProgress(85);
    // 3. Salvar metadados na tabela 'videos' com o Nome e o Usuário
    const { data: dbData, error: dbError } = await this.client
      .from('videos')
      .insert({
        title: videoTitle,
        storage_path: storagePath,
        video_url: videoUrl,
        file_size_mb: parseFloat(fileSizeMb),
        user_id: this.currentUser ? this.currentUser.id : null
      })
      .select()
      .single();

    if (dbError) {
      console.warn('Aviso ao salvar metadados na tabela videos:', dbError);
    }

    onProgress(100);
    this.refreshCatalog();

    return {
      id: dbData ? dbData.id : 'vid-' + Date.now(),
      title: videoTitle,
      url: videoUrl,
      fileSizeMb
    };
  }

  async refreshCatalog() {
    if (!this.client) return;
    const { data, error } = await this.client
      .from('videos')
      .select('*')
      .order('created_at', { ascending: false });

    if (!error && data) {
      if (this.listeners.onCatalogChanged) {
        this.listeners.onCatalogChanged(data);
      }
    }
  }

  async deleteVideo(videoId, storagePath) {
    if (!this.client) return;

    if (storagePath) {
      await this.client.storage.from('videos').remove([storagePath]);
    }

    await this.client.from('videos').delete().eq('id', videoId);
    this.refreshCatalog();
  }

  // --- TRANSMISSÃO DE VÍDEO EM TEMPO REAL ---

  async transmitVideo(video, targetDeviceId = null) {
    if (!this.client) return;

    const payload = {
      id: video.id,
      title: video.title,
      url: video.video_url || video.url,
      targetDeviceId,
      updatedAt: new Date().toISOString()
    };

    // Disparar no canal broadcast global para todos os totens conectados
    const globalChannel = this.client.channel('totem-global-channel');
    await globalChannel.send({
      type: 'broadcast',
      event: 'PLAY_VIDEO',
      payload
    });

    // Se for para um totem específico
    if (targetDeviceId) {
      const screenChannel = this.client.channel(`device-${targetDeviceId}`);
      await screenChannel.send({
        type: 'broadcast',
        event: 'PLAY_VIDEO',
        payload
      });
    }

    // Registrar no banco
    if (targetDeviceId) {
      await this.client
        .from('devices')
        .update({ current_video_id: video.id })
        .eq('id', targetDeviceId);
    }
  }
}

window.totemCentral = new TotemCentralEngine();
