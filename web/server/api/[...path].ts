import { proxySiteApi } from "../utils/api-proxy";

export default defineEventHandler((event) =>
  proxySiteApi(event, getRequestURL(event).pathname),
);
