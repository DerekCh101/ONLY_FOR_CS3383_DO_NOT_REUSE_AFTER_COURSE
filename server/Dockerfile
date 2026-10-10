FROM node:22-bookworm-slim

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY . .

ENV HOST_LISTEN_IP=0.0.0.0
ENV HostNodeServerListenToWherePort=3456

EXPOSE 3456

CMD ["npm", "start"]