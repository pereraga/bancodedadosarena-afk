/**
 * SyncEngine - Motor de Sincronização Híbrido (Firebase Firestore + Servidor SSE Local)
 * Permite que o app funcione instantaneamente tanto via nuvem quanto na rede local.
 */
class SyncEngine {
  constructor() {
    this.callbacks = {
      onVideoChanged: null,
      onCatalogUpdated: null,
      onStatusChanged: null
    };

    this.mode = 'local'; // 'firebase' ou 'local'
    this.firebaseApp = null;
    this.firestore = null;
    this.sseSource = null;
    this.currentVideo = null;
    this.videos = [];

    this.loadSavedConfig();
  }

  loadSavedConfig() {
    try {
      const savedConfig = localStorage.getItem('totemplay_firebase_config');
      if (savedConfig) {
        const config = JSON.parse(savedConfig);
        if (config && config.apiKey && config.projectId) {
          this.initFirebase(config);
          return;
        }
      }
    } catch (e) {
      console.warn('Configuração do Firebase não encontrada ou inválida. Usando modo servidor local.', e);
    }
    this.initLocalMode();
  }

  setFirebaseConfig(config) {
    if (!config || !config.apiKey) {
      throw new Error('Configuração inválida do Firebase');
    }
    localStorage.setItem('totemplay_firebase_config', JSON.stringify(config));
    this.initFirebase(config);
  }

  clearFirebaseConfig() {
    localStorage.removeItem('totemplay_firebase_config');
    window.location.reload();
  }

  initFirebase(config) {
    try {
      if (window.firebase) {
        if (!firebase.apps.length) {
          this.firebaseApp = firebase.initializeApp(config);
        } else {
          this.firebaseApp = firebase.app();
        }
        this.firestore = firebase.firestore();
        this.mode = 'firebase';

        if (this.callbacks.onStatusChanged) {
          this.callbacks.onStatusChanged({
            connected: true,
            mode: 'firebase',
            message: `Conectado ao Firebase Cloud (${config.projectId})`
          });
        }

        // Ouvir alterações do vídeo em exibição em tempo real
        this.firestore.collection('display_settings').doc('active_media')
          .onSnapshot((doc) => {
            if (doc.exists) {
              const data = doc.data();
              this.currentVideo = data;
              if (this.callbacks.onVideoChanged) {
                this.callbacks.onVideoChanged(data);
              }
            }
          }, (err) => {
            console.error('Erro no listener do Firebase:', err);
            if (this.callbacks.onStatusChanged) {
              this.callbacks.onStatusChanged({
                connected: false,
                mode: 'firebase',
                message: 'Erro na conexão com Firebase: ' + err.message
              });
            }
          });

        // Ouvir catálogo de vídeos
        this.firestore.collection('video_catalog').orderBy('createdAt', 'desc')
          .onSnapshot((snapshot) => {
            const list = [];
            snapshot.forEach(doc => {
              list.push({ id: doc.id, ...doc.data() });
            });
            this.videos = list;
            if (this.callbacks.onCatalogUpdated) {
              this.callbacks.onCatalogUpdated(list);
            }
          });

        return;
      }
    } catch (e) {
      console.error('Falha ao inicializar Firebase. Voltando para modo local:', e);
    }
    this.initLocalMode();
  }

  initLocalMode() {
    this.mode = 'local';
    
    // Conectar via Server-Sent Events ao backend Node.js
    const connectSSE = () => {
      try {
        if (this.sseSource) {
          this.sseSource.close();
        }

        this.sseSource = new EventSource('/api/events');

        this.sseSource.onopen = () => {
          if (this.callbacks.onStatusChanged) {
            this.callbacks.onStatusChanged({
              connected: true,
              mode: 'local',
              message: 'Conectado em tempo real ao Servidor Local'
            });
          }
        };

        this.sseSource.addEventListener('init', (e) => {
          const data = JSON.parse(e.data);
          this.currentVideo = data.currentVideo;
          this.videos = data.videos || [];
          if (this.callbacks.onVideoChanged && this.currentVideo) {
            this.callbacks.onVideoChanged(this.currentVideo);
          }
          if (this.callbacks.onCatalogUpdated) {
            this.callbacks.onCatalogUpdated(this.videos);
          }
        });

        this.sseSource.addEventListener('video-changed', (e) => {
          const video = JSON.parse(e.data);
          this.currentVideo = video;
          if (this.callbacks.onVideoChanged) {
            this.callbacks.onVideoChanged(video);
          }
        });

        this.sseSource.addEventListener('catalog-updated', (e) => {
          const videos = JSON.parse(e.data);
          this.videos = videos;
          if (this.callbacks.onCatalogUpdated) {
            this.callbacks.onCatalogUpdated(videos);
          }
        });

        this.sseSource.onerror = () => {
          if (this.callbacks.onStatusChanged) {
            this.callbacks.onStatusChanged({
              connected: false,
              mode: 'local',
              message: 'Tentando reconectar ao servidor...'
            });
          }
        };
      } catch (err) {
        console.warn('Erro ao conectar SSE local:', err);
      }
    };

    connectSSE();
  }

  async changeActiveVideo(video) {
    if (this.mode === 'firebase' && this.firestore) {
      const payload = {
        id: video.id || 'custom-' + Date.now(),
        title: video.title || 'Vídeo sem título',
        url: video.url,
        updatedAt: new Date().toISOString()
      };
      await this.firestore.collection('display_settings').doc('active_media').set(payload);
      return payload;
    } else {
      const res = await fetch('/api/change-video', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          videoId: video.id,
          title: video.title,
          url: video.url
        })
      });
      return await res.json();
    }
  }

  async addVideoToCatalog(title, url) {
    if (this.mode === 'firebase' && this.firestore) {
      const newDoc = {
        title,
        url,
        createdAt: new Date().toISOString()
      };
      const ref = await this.firestore.collection('video_catalog').add(newDoc);
      return { id: ref.id, ...newDoc };
    } else {
      const res = await fetch('/api/videos', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title, url })
      });
      return await res.json();
    }
  }

  async deleteVideo(videoId) {
    if (this.mode === 'firebase' && this.firestore) {
      await this.firestore.collection('video_catalog').doc(videoId).delete();
    } else {
      await fetch(`/api/videos/${videoId}`, { method: 'DELETE' });
    }
  }
}

window.syncEngine = new SyncEngine();
