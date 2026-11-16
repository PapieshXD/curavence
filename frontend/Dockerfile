FROM node:22.12.0-bookworm-slim AS build
WORKDIR /source
RUN npm install --global npm@11.6.2
COPY package.json package-lock.json ./
COPY shims shims
RUN npm ci
COPY angular.json tsconfig*.json ./
COPY public public
COPY src src
RUN npm run build -- --configuration production

FROM nginxinc/nginx-unprivileged:1.28.0-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /source/dist/frontend/browser /usr/share/nginx/html
USER 101:101
EXPOSE 8080
