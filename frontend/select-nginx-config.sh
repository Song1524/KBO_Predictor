#!/bin/sh
set -eu

certificate=/etc/letsencrypt/live/playball.ai.kr/fullchain.pem
private_key=/etc/letsencrypt/live/playball.ai.kr/privkey.pem
target=/etc/nginx/conf.d/default.conf

if [ -r "$certificate" ] && [ -r "$private_key" ]; then
    source_config=/etc/nginx/playball/https.conf
    echo "playball nginx: enabling HTTPS configuration"
else
    source_config=/etc/nginx/playball/http.conf
    echo "playball nginx: certificate not found; using HTTP bootstrap configuration"
fi

cp "$source_config" "$target"
