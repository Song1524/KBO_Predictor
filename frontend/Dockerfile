FROM node:22-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:1.27-alpine AS runtime

RUN mkdir -p /etc/nginx/playball

COPY nginx.conf /etc/nginx/playball/https.conf
COPY nginx-http.conf /etc/nginx/playball/http.conf
COPY nginx-common-locations.conf /etc/nginx/playball/common-locations.conf
COPY select-nginx-config.sh /usr/local/bin/select-playball-nginx-config
RUN chmod +x /usr/local/bin/select-playball-nginx-config \
    && ln -s /usr/local/bin/select-playball-nginx-config \
        /docker-entrypoint.d/40-select-playball-nginx-config.sh
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80 443

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O - http://127.0.0.1/healthz || exit 1
