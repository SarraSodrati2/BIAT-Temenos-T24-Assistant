import { Injectable, computed, signal } from '@angular/core';

/** Compteur de requêtes HTTP en vol, alimenté par loadingInterceptor ; consommé par AppComponent
 * pour afficher une barre de progression globale pendant tout appel réseau. */
@Injectable({ providedIn: 'root' })
export class LoadingService {
  private readonly activeRequests = signal(0);

  readonly loading = computed(() => this.activeRequests() > 0);

  start(): void {
    this.activeRequests.update(n => n + 1);
  }

  stop(): void {
    this.activeRequests.update(n => Math.max(0, n - 1));
  }
}
