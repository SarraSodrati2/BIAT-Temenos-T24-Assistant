import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { finalize } from 'rxjs';
import { LoadingService } from '../services/loading.service';
import { SKIP_LOADING_BAR } from './http-context';

/** Incrémente/décrémente LoadingService autour de chaque requête HTTP sortante (sauf requêtes
 * d'arrière-plan marquées SKIP_LOADING_BAR, ex. suggestions de la recherche pendant la frappe). */
export const loadingInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.context.get(SKIP_LOADING_BAR)) {
    return next(req);
  }
  const loading = inject(LoadingService);
  loading.start();
  return next(req).pipe(finalize(() => loading.stop()));
};
