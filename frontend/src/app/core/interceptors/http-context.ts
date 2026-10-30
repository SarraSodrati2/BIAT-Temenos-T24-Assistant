import { HttpContext, HttpContextToken } from '@angular/common/http';

/** true = l'appelant gère lui-même l'erreur : errorInterceptor n'affiche pas de notification globale
 * (ex. recherche « exacte » d'une table qui peut légitimement ne pas exister → 404 attendu). */
export const SILENT_ERRORS = new HttpContextToken<boolean>(() => false);

/** true = requête d'arrière-plan (saisie semi-automatique) : ne pas animer la barre de chargement globale. */
export const SKIP_LOADING_BAR = new HttpContextToken<boolean>(() => false);

/** Contexte pour une requête de recherche « en tapant » : ni notification d'erreur, ni barre globale. */
export function backgroundRequestContext(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true).set(SKIP_LOADING_BAR, true);
}
