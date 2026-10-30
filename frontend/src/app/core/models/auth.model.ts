export interface LoginRequest {
  username: string;
  password: string;
}

/** Pas de champ `role` : POST /register force toujours CONSULTANT côté serveur. */
export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
  confirmPassword: string;
}

export interface LoginResponse {
  token: string;
  tokenType: string;
  username: string;
  role: string;
}
