# Guia de Deploy da Totem Central na Vercel

A **Totem Central** está 100% pronta para ser publicada na nuvem global da Vercel com domínio próprio e HTTPS automático gratuito.

---

## 🚀 Opção 1: Deploy com 1 Comando pelo Terminal (Mais Rápido)

Abra o terminal na pasta do projeto:
`C:\Users\PC\.gemini\antigravity\scratch\video-display-app`

Execute:
```bash
npx vercel
```

1. Se for a primeira vez, o terminal pedirá para fazer login na sua conta da Vercel (basta dar enter e autenticar no navegador).
2. Responda às perguntas simples do terminal:
   - `Set up and deploy?` Digite **y**
   - `Which scope?` Pressione **Enter** (sua conta)
   - `Link to existing project?` Digite **n**
   - `What's your project's name?` Digite **totem-central** (ou pressione Enter)
   - `In which directory is your code located?` Pressione **Enter** (`./`)
3. Em menos de 30 segundos, a Vercel gerará o link oficial do seu site:
   🎉 `https://totem-central.vercel.app` (ou similar)

Quando quiser publicar a versão final de produção:
```bash
npx vercel --prod
```

---

## 🌐 Opção 2: Deploy pelo GitHub e Painel da Vercel

1. Crie um repositório no seu GitHub (ex: `totem-central`).
2. Envie os arquivos desta pasta para o repositório:
```bash
git init
git add .
git commit -m "Totem Central inicial"
git branch -M main
git remote add origin https://github.com/SEU_USUARIO/totem-central.git
git push -u origin main
```
3. Acesse [vercel.com](https://vercel.com) e clique em **"Add New Project"**.
4. Selecione o repositório `totem-central` e clique em **Deploy**.
5. Pronto! Sempre que você atualizar o código no GitHub, a Vercel atualiza o site na hora automaticamente.

---

## ⚙️ Conectando com seu Supabase (1 Minuto)

1. Acesse seu painel no [Supabase](https://supabase.com).
2. Abra o **SQL Editor** e execute o script [supabase-setup.sql](supabase-setup.sql).
3. Abra a sua **Totem Central** na Vercel (`https://seu-site.vercel.app`), clique no ícone da **engrenagem** no topo e cole:
   - **Project URL** (ex: `https://xyz.supabase.co`)
   - **Anon Public Key**
4. Crie seu cadastro com seu e-mail e senha na tela inicial da Totem Central!
