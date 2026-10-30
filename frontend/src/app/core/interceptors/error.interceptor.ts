import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { SILENT_ERRORS } from './http-context';

const SNACKBAR_DURATION_MS = 5000;

/**
 * Filet de sécurité global : affiche un message clair pour toute erreur HTTP non gérée
 * spécifiquement par l'appelant (login/chatbot/formulaire utilisateur affichent déjà leur propre
 * message inline — celui-ci s'ajoute, il ne les remplace pas). Sur 401, déconnecte
 * automatiquement : le token stocké localement n'est plus valide côté backend (expiré/révoqué).
 * L'erreur est toujours re-propagée pour que les composants gardent la main sur leur propre UI.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const snackBar = inject(MatSnackBar);
  const auth = inject(AuthService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      // L'appelant traite lui-même cette erreur (voir SILENT_ERRORS). Un 401 déconnecte quand même :
      // un token invalide l'est pour toute l'application, pas seulement pour cet appel.
      if (req.context.get(SILENT_ERRORS) && error.status !== 401) {
        return throwError(() => error);
      }
      switch (error.status) {
        case 401:
          snackBar.open('Session expirée. Veuillez vous reconnecter.', 'Fermer', { duration: SNACKBAR_DURATION_MS });
          auth.logout();
          break;
        case 403:
          snackBar.open("Accès refusé : vous n'avez pas les droits nécessaires.", 'Fermer', { duration: SNACKBAR_DURATION_MS });
          break;
        case 404:
          snackBar.open('Ressource introuvable.', 'Fermer', { duration: SNACKBAR_DURATION_MS });
          break;
        case 0:
          snackBar.open('Impossible de joindre le serveur. Vérifiez votre connexion.', 'Fermer', { duration: SNACKBAR_DURATION_MS });
          break;
        default:
          if (error.status >= 500) {
            snackBar.open('Erreur serveur. Réessayez plus tard.', 'Fermer', { duration: SNACKBAR_DURATION_MS });
          }
      }
      return throwError(() => error);
    }),
  );
};
