import { HttpClient } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';
import { catchError, of } from 'rxjs';
import { environment } from '../../../environments/environment';

export type BackendStatus = 'checking' | 'online' | 'offline';

@Injectable({ providedIn: 'root' })
export class BackendStatusService {
  private readonly statusSignal = signal<BackendStatus>('checking');

  readonly status = this.statusSignal.asReadonly();

  constructor(private readonly http: HttpClient) {
    this.check();
  }

  check(): void {
    this.statusSignal.set('checking');
    // /actuator/health est publique (voir PUBLIC_ROUTES côté backend) : contrairement à une route
    // protégée comme /api/modules, son statut HTTP ne dépend jamais d'un token présent/valide — un 401
    // sur une route protégée ne prouve absolument pas que le serveur est injoignable (il a bien
    // répondu), mais catchError() ne distinguait pas les deux cas et affichait "serveur injoignable"
    // à chaque 401 (avant login, ou token expiré), alors que l'API fonctionnait normalement.
    this.http.get(environment.healthUrl)
      .pipe(catchError(() => of(null)))
      .subscribe(result => this.statusSignal.set(result === null ? 'offline' : 'online'));
  }
}
