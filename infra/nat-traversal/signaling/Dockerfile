FROM node:24-alpine

ENV NODE_ENV=production
ENV HOST=0.0.0.0
ENV PORT=8080

WORKDIR /app
COPY --chown=node:node server.mjs ./server.mjs

USER node
EXPOSE 8080
CMD ["node", "server.mjs"]
