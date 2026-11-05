export default defineEventHandler((event) =>
  proxyRequest(event, "http://127.0.0.1:18080/api/public/seo/robots"),
);
