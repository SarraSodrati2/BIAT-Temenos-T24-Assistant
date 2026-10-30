import { HttpClient } from '@angular/common/http';
import { Injectable, computed, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { LoginRequest, LoginResponse, RegisterRequest } from '../models/auth.model';
import { User, UserRole } from '../models/user.model';

const TOKEN_KEY = 'biat_auth_token';
const USERNAME_KEY = 'biat_auth_username';
const ROLE_KEY = 'biat_auth_role';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly tokenSignal = signal<string | null>(localStorage.getItem(TOKEN_KEY));
  private readonly usernameSignal = signal<string | null>(localStorage.getItem(USERNAME_KEY));
  private readonly roleSignal = signal<UserRole | null>(localStorage.getItem(ROLE_KEY) as UserRole | null);

  readonly token = this.tokenSignal.asReadonly();
  readonly username = this.usernameSignal.asReadonly();
  readonly role = this.roleSignal.asReadonly();
  readonly isAuthenticated = computed(() => this.tokenSignal() !== null);

  constructor(
    private readonly http: HttpClient,
    private readonly router: Router,
  ) {}

  login(request: LoginRequest): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(environment.loginUrl, request).pipe(
      tap(response => {
        // Log volontairement conservé : si un jour la forme de la réponse change côté backend sans
        // que ce fichier soit mis à jour, ceci rend l'écart immédiatement visible dans la console
        // au lieu de se traduire silencieusement par un login qui "échoue" sans raison apparente.
        console.debug('POST /login → 200, réponse reçue :', response);
        if (!response?.token) {
          throw new Error('Réponse de connexion invalide : le champ "token" est manquant.');
        }

        localStorage.setItem(TOKEN_KEY, response.token);
        localStorage.setItem(USERNAME_KEY, response.username);
        localStorage.setItem(ROLE_KEY, response.role);
        this.tokenSignal.set(response.token);
        this.usernameSignal.set(response.username);
        this.roleSignal.set(response.role as UserRole);
      }),
    );
  }

  /** Auto-inscription publique : le rôle CONSULTANT est forcé côté serveur, jamais choisi ici. */
  register(request: RegisterRequest): Observable<User> {
    return this.http.post<User>(environment.registerUrl, request);
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USERNAME_KEY);
    localStorage.removeItem(ROLE_KEY);
    this.tokenSignal.set(null);
    this.usernameSignal.set(null);
    this.roleSignal.set(null);
    this.router.navigateByUrl('/login');
  }

  getToken(): string | null {
    return this.tokenSignal();
  }

  hasRole(...roles: UserRole[]): boolean {
    const current = this.roleSignal();
    return current !== null && roles.includes(current);
  }
}
