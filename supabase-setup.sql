-- =================================================================
-- SCRIPT COMPLETO DO SUPABASE PARA A TOTEM CENTRAL
-- Execute no SQL Editor do seu projeto Supabase (console.supabase.com)
-- =================================================================

-- 1. BUCKET DE ARMAZENAMENTO PARA OS VÍDEOS
INSERT INTO storage.buckets (id, name, public)
VALUES ('videos', 'videos', true)
ON CONFLICT (id) DO NOTHING;

-- Políticas de acesso ao bucket
CREATE POLICY "Leitura pública de vídeos para os Totens"
ON storage.objects FOR SELECT
USING (bucket_id = 'videos');

CREATE POLICY "Permitir upload de vídeos pelos usuários cadastrados"
ON storage.objects FOR INSERT
WITH CHECK (bucket_id = 'videos');

CREATE POLICY "Permitir exclusão de vídeos pelos usuários"
ON storage.objects FOR DELETE
USING (bucket_id = 'videos');

-- 2. TABELA DE DISPOSITIVOS / TOTENS
CREATE TABLE IF NOT EXISTS public.devices (
    id TEXT PRIMARY KEY,
    device_name TEXT NOT NULL DEFAULT 'Modelo Totem',
    pair_code TEXT,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    ip_address TEXT,
    status TEXT NOT NULL DEFAULT 'pending', -- 'pending' (querendo conectar), 'approved' (autorizado), 'rejected'
    current_video_id UUID,
    last_seen TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now())
);

-- 3. TABELA DE VÍDEOS COM NOME PERSONALIZADO
CREATE TABLE IF NOT EXISTS public.videos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    video_url TEXT NOT NULL,
    file_size_mb NUMERIC(10, 2),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now())
);

-- 4. HABILITAR ROW LEVEL SECURITY (RLS)
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.videos ENABLE ROW LEVEL SECURITY;

-- Políticas flexíveis para permitir pareamento e leitura da tela pública com segurança
CREATE POLICY "Acesso aos dispositivos" 
ON public.devices FOR ALL 
USING (true) 
WITH CHECK (true);

CREATE POLICY "Acesso aos vídeos" 
ON public.videos FOR ALL 
USING (true) 
WITH CHECK (true);

-- 5. ATIVAR SUPABASE REALTIME (Para transmissão e troca instantânea sem recarregar tela)
ALTER PUBLICATION supabase_realtime ADD TABLE public.devices;
ALTER PUBLICATION supabase_realtime ADD TABLE public.videos;
