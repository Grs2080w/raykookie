# Imagem da API (backend/ + shared/), sem o frontend: server.mjs detecta a ausência de
# ../frontend/.next e serve só a API (ver server.mjs). O site vai para a Vercel.
#   docker build -f backend/Dockerfile -t raykookie-api .   (contexto: a raiz do repo, por causa de ../shared)
#   docker run --network host --env-file backend/.env.docker --restart unless-stopped --name raykookie-api raykookie-api
FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY backend/package*.json ./backend/
RUN npm --prefix backend ci
COPY shared ./shared
COPY backend ./backend
RUN npm --prefix backend run build

FROM node:22-bookworm-slim
WORKDIR /app
COPY --from=build /app/shared ./shared
COPY --from=build /app/backend ./backend
WORKDIR /app/backend
ENV NODE_ENV=production
EXPOSE 10005
CMD ["node", "server.mjs"]
