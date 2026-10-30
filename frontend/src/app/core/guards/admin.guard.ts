import { CanActivateFn } from '@angular/router';
import { roleGuard } from './role.guard';

/** Réserve les routes (ex. /users) aux rôles ADMIN/SUPER_ADMIN. */
export const adminGuard: CanActivateFn = roleGuard('ADMIN', 'SUPER_ADMIN');
