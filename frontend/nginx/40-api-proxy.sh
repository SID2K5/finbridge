#!/bin/sh
# Runs when the container starts, before nginx. If BACKEND_URL is set (local Docker Compose),
# requests to /api are forwarded to the backend, so the browser sees one origin. If it is not
# set (AWS, where the load balancer routes /api), /api is simply not served by this container.
set -eu

target=/etc/nginx/conf.d/api-proxy.inc

if [ -n "${BACKEND_URL:-}" ]; then
    # Only scheme://host[:port] is accepted, so the value cannot inject nginx directives.
    case "$BACKEND_URL" in
        http://*|https://*) ;;
        *) echo "40-api-proxy.sh: BACKEND_URL must start with http:// or https://" >&2; exit 1 ;;
    esac
    if printf '%s' "$BACKEND_URL" | grep -Eq '[^A-Za-z0-9.:/_-]'; then
        echo "40-api-proxy.sh: BACKEND_URL contains characters that are not allowed" >&2
        exit 1
    fi
    cat > "$target" <<EOF
    location /api/ {
        proxy_pass ${BACKEND_URL%/};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_connect_timeout 5s;
        proxy_read_timeout 60s;
        client_max_body_size 1m;
    }
EOF
    echo "40-api-proxy.sh: /api is forwarded to ${BACKEND_URL%/}"
else
    : > "$target"
    echo "40-api-proxy.sh: BACKEND_URL is not set; /api is not served by this container"
fi
