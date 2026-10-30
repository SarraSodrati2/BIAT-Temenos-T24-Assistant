import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { UserRole } from '../models/user.model';

/** Factory : réserve une route à un ou plusieurs rôles. Redirige vers /assistant si non autorisé. */
export function roleGuard(...roles: UserRole[]): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    if (auth.hasRole(...roles)) {
      return true;
    }
    return inject(Router).createUrlTree(['/assistant']);
  };
}
