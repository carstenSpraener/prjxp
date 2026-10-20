#!/bin/sh
set -eu

# Upstream for the API proxy: scheme + backend service name + port,
# as seen from inside the Docker network. Default is the compose "hub"
# service; override via .env, e.g. PRJXP_HUB_URL=http://osb-mcp:8080
: "${PRJXP_HUB_URL:=http://hub:7007}"

# Render the nginx config template.
# IMPORTANT: pass an explicit variable list to envsubst so that ONLY
# PRJXP_HUB_URL is substituted — nginx's own variables ($host, $uri,
# $remote_addr, ...) must be left untouched for the runtime config.
envsubst '$PRJXP_HUB_URL' \
  < /etc/nginx/templates/default.conf.template \
  > /etc/nginx/conf.d/default.conf

exec nginx -g "daemon off;"
