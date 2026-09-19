-- =================================================================
-- SCRIPT COMPLETO E AUTOMÁTICO DO SUPABASE PARA A TOTEM CENTRAL
-- Execute no SQL Editor do seu projeto Supabase:
-- https://supabase.com/dashboard/project/qvnsahvdjhimlmtqrnif/sql
-- =================================================================

-- 1. CRIAR BUCKET DE ARMAZENAMENTO PARA OS VÍDEOS ('videos' PÚBLICO)
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('videos', 'videos', true, null, null)
ON CONFLICT (id) DO UPDATE SET public = true;

-- Remover políticas antigas para evitar duplicações
DROP POLICY IF EXISTS "Leitura pública de vídeos para os Totens" ON storage.objects;
DROP POLICY IF EXISTS "Permitir upload de vídeos pelos usuários cadastrados" ON storage.objects;
DROP POLICY IF EXISTS "Permitir exclusão de vídeos pelos usuários" ON storage.objects;
DROP POLICY IF EXISTS "Acesso total publico ao bucket videos" ON storage.objects;
DROP POLICY IF EXISTS "Permitir upload publico no bucket videos" ON storage.objects;
DROP POLICY IF EXISTS "Permitir leitura publica no bucket videos" ON storage.objects;
DROP POLICY IF EXISTS "Permitir delecao no bucket videos" ON storage.objects;

-- Políticas de acesso completas ao bucket 'videos' (leitura, upload e exclusão)
CREATE POLICY "Permitir leitura publica no bucket videos"
ON storage.objects FOR SELECT
USING (bucket_id = 'videos');

CREATE POLICY "Permitir upload publico no bucket videos"
ON storage.objects FOR INSERT
WITH CHECK (bucket_id = 'videos');

CREATE POLICY "Permitir delecao no bucket videos"
ON storage.objects FOR DELETE
USING (bucket_id = 'videos');

-- 2. TABELA DE DISPOSITIVOS / TOTENS
CREATE TABLE IF NOT EXISTS public.devices (
    id TEXT PRIMARY KEY,
    device_name TEXT NOT NULL DEFAULT 'Modelo Totem',
    pair_code TEXT,
    user_id UUID,
    ip_address TEXT,
    status TEXT NOT NULL DEFAULT 'pending', -- 'pending', 'approved', 'rejected'
    current_video_id UUID,
    last_seen TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now())
);

-- 3. TABELA DE VÍDEOS COM NOME PERSONALIZADO
CREATE TABLE IF NOT EXISTS public.videos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID,
    title TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    video_url TEXT NOT NULL,
    file_size_mb NUMERIC(10, 2),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now())
);

-- 4. HABILITAR ROW LEVEL SECURITY (RLS)
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.videos ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Acesso aos dispositivos" ON public.devices;
DROP POLICY IF EXISTS "Acesso aos vídeos" ON public.videos;

CREATE POLICY "Acesso aos dispositivos" 
ON public.devices FOR ALL 
USING (true) 
WITH CHECK (true);

CREATE POLICY "Acesso aos vídeos" 
ON public.videos FOR ALL 
USING (true) 
WITH CHECK (true);

-- 5. ATIVAR SUPABASE REALTIME (Transmissão imediata em tempo real)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_publication_tables 
    WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'devices'
  ) THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.devices;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_publication_tables 
    WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'videos'
  ) THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.videos;
  END IF;
END $$;
