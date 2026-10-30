import { Component, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../core/services/auth.service';
import { AuthLayoutComponent } from '../../shared/components/auth-layout/auth-layout.component';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    AuthLayoutComponent,
    FormsModule,
    RouterLink,
    MatIconModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './login.component.html',
  styleUrls: ['../../shared/components/auth-layout/auth-card.scss'],
})
export class LoginComponent {
  username = '';
  password = '';
  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly hidePassword = signal(true);

  togglePasswordVisibility(): void {
    this.hidePassword.update(hidden => !hidden);
  }

  constructor(
    private readonly auth: AuthService,
    private readonly router: Router,
  ) {}

  submit(form: NgForm): void {
    if (form.invalid || this.loading()) {
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(null);

    this.auth.login({ username: this.username, password: this.password }).subscribe({
      next: () => this.router.navigateByUrl('/dashboard'),
      error: (error: HttpErrorResponse) => {
        // Ne jamais afficher "Identifiants invalides" pour autre chose qu'un vrai 401 : un statut 0
        // (CORS/réseau/serveur injoignable) ou une erreur 5xx n'a rien à voir avec le mot de passe,
        // et masquer la vraie cause derrière ce message rendrait le diagnostic impossible.
        console.error('Échec de connexion :', error);
        this.errorMessage.set(this.describeError(error));
        this.loading.set(false);
      },
    });
  }

  private describeError(error: HttpErrorResponse): string {
    if (error.status === 401) {
      return error.error?.message ?? 'Identifiants invalides.';
    }
    if (error.status === 0) {
      return "Impossible de joindre le serveur (vérifiez qu'il est démarré sur http://localhost:8080).";
    }
    if (error.status >= 500) {
      return 'Erreur serveur. Réessayez plus tard.';
    }
    return error.error?.message ?? `Échec de connexion (code ${error.status}).`;
  }
}
