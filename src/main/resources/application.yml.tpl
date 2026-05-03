server:
  port: {{SERVER_PORT}}
  shutdown: graceful

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: {{JWT_JWK_SET_URI}}
          secret: {{JWT_SECRET}}
  cloud:
    gateway:
      routes:
        - id: djong-reader-engine
          uri: {{GRAPHQL_BACKEND_URI}}
          predicates:
            - Path={{GRAPHQL_BACKEND_PREDICATES}}
          filters:
            - RewritePath={{GRAPHQL_BACKEND_PREDICATES}}, {{GRAPHQL_BACKEND_REWRITES}}
            - AddResponseHeader=X-Powered-By, Djong Reader Engine
            - TokenRelay

        - id: sadewa-portfolio-svc
          uri: {{GRAPHQL_SADEWA_PORTFOLIO_SVC_URI}}
          predicates:
            - Path={{GRAPHQL_SADEWA_PORTFOLIO_SVC_PREDICATES}}
          filters:
            - RewritePath={{GRAPHQL_SADEWA_PORTFOLIO_SVC_PREDICATES}}, {{GRAPHQL_SADEWA_PORTFOLIO_SVC_REWRITES}}
            - AddResponseHeader=X-Powered-By, Sedewa Portfolio Service
            - TokenRelay

        - id: server-auth
          uri: {{SERVER_AUTH_URI}}
          predicates:
            - Path={{SERVER_AUTH_PREDICATES}}
          filters:
            - RewritePath={{SERVER_AUTH_PREDICATES}}, {{SERVER_AUTH_REWRITES}}
            - AddResponseHeader=X-Powered-By, Server Auth
            - TokenRelay

logging:
  level:
    org.springframework.security: DEBUG
