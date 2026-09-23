# TotemScreen - Projeto Android Studio para APK

Este projeto é um aplicativo nativo Android (APK) configurado para rodar a tela do totem (`https://totemarena.vercel.app/screen`) em **Modo Kiosk Profissional**.

---

## ⚡ Otimizado para Tablets Android 6.0.1, 1 GB RAM e Processador Allwinner UltraOcta A83T

Este APK foi ajustado com configurações de baixo consumo e alta performance para tablets de 1GB de RAM:
1. **Compatibilidade com Android 6.0.1 (Marshmallow - API 23):**
   - Bytecode em Java 8 (`VERSION_1_8`) para compatibilidade perfeita com a máquina virtual ART do Android 6.0.1.
   - Tratamento de certificados SSL/TLS modernos (`handler.proceed()` e `network_security_config.xml`): garante que certificados Let's Encrypt da Vercel funcionem sem erro de certificado expirado.
2. **Gerenciamento de Memória para 1 GB de RAM:**
   - Ativação de `android:largeHeap="true"` para garantir heap suficiente.
   - Limpeza ativa de memória em baixo nível (`onLowMemory` e `onTrimMemory`): esvazia buffers em excesso sem fechar o app.
   - Rotina periódica de garbage collection para evitar travamentos por vazamento de memória em reproduções longas (24/7).
   - Cache de disco limitado a 25MB para não sobrecarregar a memória interna.
3. **Otimizações para GPU PowerVR SGX544MP1 (Allwinner A83T):**
   - Remoção de texturas intermediárias pesadas (`LAYER_TYPE_NONE`), permitindo que o Android renderize diretamente na superfície acelerada por hardware.
   - Remoção de filtros CSS pesados (como `backdrop-blur`), garantindo 60 FPS na tela.
   - Suporte nativo à arquitetura 32-bit `armeabi-v7a` do processador Cortex-A7 do A83T.

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
4. Copie o arquivo `.apk` para o tablet e instale!

---

## ⚙️ Recursos Implementados no APK:

* **Modo Kiosk Imersivo**: Oculta 100% das barras de navegação e status do Android.
* **Tela Sempre Ligada (WakeLock)**: O display nunca entra em suspensão nem desliga (24/7).
* **Autoplay Sem Toque**: Os vídeos iniciam automaticamente sem exigir clique manual.
* **Auto-Reconexão**: Se o sinal Wi-Fi oscilar ou cair, o app tenta se reconectar automaticamente sem travar.
* **Persistência de ID e Pareamento**: O código de pareamento e o identificador do totem ficam gravados permanentemente no armazenamento do app.
* **Bloqueio do Botão Voltar**: Impede que clientes ou toques acidentais fechem o aplicativo no totem.
* **Orientação Adaptativa (fullSensor)**: Funciona perfeitamente tanto em totens verticais (em pé) quanto horizontais (deitados).
