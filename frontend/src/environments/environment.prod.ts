/**
 * API base URL — production (behind a reverse proxy).
 * The app is served under /prjxp/ and customer reverse proxies (e.g. Apache)
 * only forward /prjxp* to the frontend nginx, so API calls must stay under
 * the app prefix: /prjxp/api/*. The frontend nginx rewrites /prjxp/api/*
 * to /api/* before proxying to the backend (see nginx.conf.template).
 */
export const API_BASE = '/prjxp/api';
