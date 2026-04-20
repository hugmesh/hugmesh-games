module.exports = {
  '/api': {
    target: 'http://localhost:8080',
    secure: false,
    changeOrigin: true,
    // SSE streams require keep-alive; the Vite proxy injects connection:close by
    // default which terminates the stream after the first chunk.
    configure: (proxy) => {
      proxy.on('proxyRes', (proxyRes, req) => {
        if (req.url && req.url.includes('/game/stream')) {
          proxyRes.headers['connection'] = 'keep-alive';
          proxyRes.headers['cache-control'] = 'no-cache';
        }
      });
    }
  }
};
