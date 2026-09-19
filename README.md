# Totem Play - Sistema de Exibição de Vídeo em Tempo Real

Aplicativo para celular (APK / PWA) que reproduz vídeos continuamente (modo totem/kiosk), sincronizado em tempo real com um banco de dados na nuvem (Firebase) e servidor local. Você pode trocar o vídeo remotamente a qualquer momento pelo computador ou por outro celular, e o celular de exibição troca na hora sem você tocar nele.

---

## 📱 O que o Aplicativo faz automaticamente para você

1. **Reprodução Contínua (Loop)**: O vídeo fica tocando sem parar.
2. **Tela Sempre Acesa (Wake Lock)**: O celular não desliga nem bloqueia a tela enquanto o app estiver aberto.
3. **Detecção em Tempo Real (Milissegundos)**: Assim que você clica em "Transmitir Agora" no painel, o aplicativo no celular detecta a mudança e carrega o novo vídeo com transição suave.
4. **Sem propagandas ou controles intrusivos**: Tela cheia limpa, ideal para vitrines, totens, academias, comércios ou uso pessoal.
5. **Modo Híbrido**: Funciona na rede Wi-Fi local ou via nuvem (Firebase Firestore de qualquer lugar do mundo).

---

## 🚀 Como Iniciar Agora (Testar no Computador e no Celular)

### 1. Iniciar o Servidor
- Dê dois cliques no arquivo `iniciar.bat` ou abra o terminal nesta pasta e execute:
```bash
node server.js
```

O terminal exibirá os endereços de acesso:
- **No seu Computador**: `http://localhost:3000`
- **No seu Celular (conectado no mesmo Wi-Fi)**: `http://192.168.x.x:3000`

---

## 📲 Como Instalar como Aplicativo (APK) no Celular

O projeto já está 100% configurado com **WebAPK / PWA**:
1. No seu celular Android, abra o navegador **Google Chrome**.
2. Digite o endereço do player (exemplo: `http://192.168.x.x:3000/player.html`).
3. Toque nos **3 pontinhos** do Chrome no canto superior direito.
4. Selecione **"Instalar aplicativo"** ou **"Adicionar à tela inicial"**.
5. O Android vai gerar o ícone do aplicativo com o nome **TotemPlay** na sua lista de apps!
6. Ao abrir pelo ícone, ele executa em **tela cheia nativa** (sem barra de pesquisa nem abas), como qualquer app da Play Store!

---

## ☁️ Como Ativar a Troca de Qualquer Lugar do Mundo (Firebase)

Para trocar os vídeos mesmo quando você estiver fora de casa (usando 4G/5G):
1. Acesse [console.firebase.google.com](https://console.firebase.google.com) (grátis com sua conta Google).
2. Clique em **"Adicionar projeto"** (ex: `meu-totem-video`).
3. No menu lateral, clique em **Criação > Cloud Firestore > Criar banco de dados** (escolha modo de teste).
4. Clique na engrenagem no topo (Configurações do Projeto) > **Geral** > Role até o final em **"Seus aplicativos"** > Selecione o ícone de Web `</>`.
5. Copie o bloco `const firebaseConfig = { ... };`.
6. Abra o seu **Painel de Controle** (`admin.html`), clique no botão azul **"Banco na Nuvem"**, cole o código e salve!
7. Pronto! O banco na nuvem está vinculado e você pode controlar de qualquer lugar do planeta!

---

## 🎬 Onde Hospedar seus Vídeos

Você pode utilizar qualquer link direto de vídeo em formato `.mp4` ou `.webm`:
- **Google Drive**: Faça upload do vídeo, compartilhe como "Qualquer pessoa com o link" e utilize um gerador de link direto.
- **Dropbox**: No link de compartilhamento, troque o final `?dl=0` por `?raw=1`.
- **Firebase Storage**: Faça upload no próprio Storage do Firebase e use o link gerado.
- **Servidores de Mídia / Cloudinary / AWS S3**.
