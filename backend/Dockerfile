# Imagem da API. O contexto é a própria pasta backend/ (não precisa de nada de fora dela).
#   docker build -t raykookie-api backend          (da raiz do repo)   ou   docker build -t raykookie-api .   (de dentro de backend/)
#   docker run --network host --env-file backend/.env.docker --restart unless-stopped \
#     -v "$(pwd)/downloads:/app/downloads" --name raykookie-api raykookie-api
# Os downloads ficam em /app/downloads (o padrão do backend é <pasta do backend>/downloads, e a pasta aqui é /app).
FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:22-bookworm-slim
WORKDIR /app
COPY --from=build /app ./
ENV NODE_ENV=production
EXPOSE 10005
CMD ["node", "server.mjs"]
