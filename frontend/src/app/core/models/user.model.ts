/** UserDTO (backend/dto/UserDTO.java) : le compte applicatif (authentification, gestion des accès). */
export type UserRole = 'CONSULTANT' | 'ADMIN' | 'SUPER_ADMIN';

export interface User {
  id: number;
  username: string;
  email: string;
  role: UserRole;
  createdAt: string;
  isActive: boolean;
}

/** Payload d'écriture (POST/PUT /api/users) : le mot de passe n'est jamais renvoyé par le backend (WRITE_ONLY). */
export interface UserRequest {
  username: string;
  email: string;
  password?: string;
  role: UserRole;
  isActive?: boolean;
}
