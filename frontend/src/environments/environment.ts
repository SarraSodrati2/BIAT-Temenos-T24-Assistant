const backendBaseUrl = 'http://localhost:8080';

export const environment = {
  production: false,
  apiUrl: `${backendBaseUrl}/api`,
  // POST /login, /register et GET /actuator/health sont hors du préfixe /api (voir SecurityConfig
  // côté backend) — /actuator/health est en plus la seule route publique (PUBLIC_ROUTES) qui ne
  // nécessite aucune authentification : c'est la seule à utiliser pour un simple test de présence du
  // serveur (voir BackendStatusService), jamais une route protégée dont le 401 ne prouve rien.
  loginUrl: `${backendBaseUrl}/login`,
  registerUrl: `${backendBaseUrl}/register`,
  healthUrl: `${backendBaseUrl}/actuator/health`,
};
