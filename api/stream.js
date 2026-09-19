export const config = {
  runtime: 'edge',
};

export default async function handler(request) {
  if (request.method === 'OPTIONS') {
    return new Response(null, {
      status: 204,
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
        'Access-Control-Allow-Headers': 'Range',
      },
    });
  }

  const { searchParams } = new URL(request.url);
  let id = searchParams.get('id');
  let targetUrl = searchParams.get('url');

  if (!id && targetUrl) {
    const match = targetUrl.match(/\/file\/d\/([a-zA-Z0-9_-]+)/) || targetUrl.match(/[?&]id=([a-zA-Z0-9_-]+)/);
    if (match) id = match[1];
  }

  if (!id && !targetUrl) {
    return new Response('Missing id or url parameter', { status: 400 });
  }

  const finalUrl = id
    ? 'https://drive.usercontent.google.com/download?id=' + id + '&export=download&confirm=t'
    : targetUrl;

  const fetchHeaders = new Headers();
  let range = request.headers.get('range');
  if (!range) {
    range = 'bytes=0-';
  }
  fetchHeaders.set('range', range);
  fetchHeaders.set('User-Agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');

  try {
    const response = await fetch(finalUrl, {
      headers: fetchHeaders,
      redirect: 'follow',
    });

    const responseHeaders = new Headers();
    responseHeaders.set('Access-Control-Allow-Origin', '*');
    responseHeaders.set('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');
    responseHeaders.set('Access-Control-Allow-Headers', 'Range, Content-Range, Content-Type, Accept-Ranges');
    responseHeaders.set('Access-Control-Expose-Headers', 'Content-Range, Content-Length, Accept-Ranges');
    responseHeaders.set('Accept-Ranges', 'bytes');
    responseHeaders.set('Cache-Control', 'public, max-age=3600');

    const contentType = response.headers.get('content-type');
    responseHeaders.set('Content-Type', contentType || 'video/mp4');

    const contentLength = response.headers.get('content-length');
    if (contentLength) responseHeaders.set('Content-Length', contentLength);

    const contentRange = response.headers.get('content-range');
    if (contentRange) responseHeaders.set('Content-Range', contentRange);

    return new Response(response.body, {
      status: response.status,
      headers: responseHeaders,
    });
  } catch (err) {
    return new Response('Error streaming video: ' + err.message, { status: 500 });
  }
}
