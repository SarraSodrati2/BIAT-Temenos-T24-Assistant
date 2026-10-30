import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/** Point d'entrée « / » : les utilisateurs connectés arrivent sur l'accueil, les autres sur la connexion. */
export const entryGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return inject(Router).createUrlTree([auth.isAuthenticated() ? '/dashboard' : '/login']);
};
