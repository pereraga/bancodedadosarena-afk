# TotemScreen - Projeto Android Studio para APK

Este projeto é um aplicativo nativo Android (APK) configurado para rodar a tela do totem (`https://totemarena.vercel.app/screen`) em **Modo Kiosk Profissional**.

---

## 🚀 Como Abrir e Gerar o APK no Android Studio

### Passo 1: Abrir no Android Studio
1. Abra o **Android Studio**.
2. Clique em **Open** (ou **File > Open...**).
3. Selecione a pasta `android` deste projeto:
   `C:\Users\PC\.gemini\antigravity\scratch\video-display-app\android`
4. Aguarde o Gradle sincronizar os arquivos automaticamente (1 a 2 minutos).

### Passo 2: Gerar o APK Instalável
1. No menu superior do Android Studio, clique em:
   **Build > Build Bundle(s) / APK(s) > Build APK(s)**
2. Quando a compilação terminar, aparecerá uma notificação no canto inferior direito:
   `APK(s) generated successfully for 1 module: 'app'`
3. Clique em **locate** para abrir a pasta onde o arquivo `.apk` foi salvo (geralmente em `app/build/outputs/apk/debug/app-debug.apk`).
4. Copie o arquivo `.apk` para o seu totem, tablet, celular ou TV Box Android e instale!

---

## ⚙️ Recursos Implementados no APK:

* **Modo Kiosk Imersivo**: Oculta 100% das barras de navegação e status do Android.
* **Tela Sempre Ligada (WakeLock)**: O display nunca entra em suspensão nem desliga (24/7).
* **Aceleração por Hardware**: Renderização ultra-fluida de vídeos Full HD / 4K.
* **Autoplay Sem Toque**: Os vídeos iniciam automaticamente sem exigir clique manual.
* **Auto-Reconexão**: Se o sinal Wi-Fi oscilar ou cair, o app tenta se reconectar automaticamente sem travar.
* **Persistência de ID e Pareamento**: O código de pareamento e o identificador do totem ficam gravados permanentemente no armazenamento do app (DOM Storage e Cookies).
* **Bloqueio do Botão Voltar**: Impede que clientes ou toques acidentais fechem o aplicativo no totem.
* **Orientação Adaptativa (fullSensor)**: Funciona perfeitamente tanto em totens verticais (em pé) quanto em TVs/monitores horizontais (deitados).
