import { API_BASE as envApiBase } from '../../environments/environment';

/**
 * Base URL for the prjxp API.
 * - Development (ng serve): '/api' — absolute path from the origin root, forwarded by
 *   the dev proxy (src/proxy.conf.json) to the backend on :7007.
 * - Production: '/prjxp/api' — under the app prefix (baseHref /prjxp/), so a reverse
 *   proxy that only forwards /prjxp* can route it to the frontend nginx, which maps
 *   /prjxp/api/* → backend /api/*.
 */
export const API_BASE = envApiBase;
