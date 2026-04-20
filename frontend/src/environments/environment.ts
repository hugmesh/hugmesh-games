export const environment = {
  production: false,
  // Connect directly to Spring Boot in dev to avoid the Vite proxy
  // swallowing the SSE keep-alive stream.
  apiBase: 'http://localhost:8080',
};
