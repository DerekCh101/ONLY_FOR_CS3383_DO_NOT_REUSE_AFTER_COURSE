# Ashore Prospect — Node API + web app.
# Alpine keeps the image small; bcrypt 6 ships a musl prebuild, so no compiler is needed.
FROM node:22-alpine

WORKDIR /app

# Dependencies first so Docker can cache this layer between rebuilds.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

# App code. Static routes read ./index.html, ./app.js, ./vault.js, ./shell.js
# relative to the working directory, so everything must sit in /app.
COPY . .

ENV NODE_ENV=production
EXPOSE 3456

CMD ["node", "server.js"]
