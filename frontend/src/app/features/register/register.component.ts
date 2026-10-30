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

/** Auto-inscription publique : compte toujours créé CONSULTANT côté serveur (voir AuthService.register). */
@Component({
  selector: 'app-register',
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
  templateUrl: './register.component.html',
  styleUrls: ['../../shared/components/auth-layout/auth-card.scss'],
})
export class RegisterComponent {
  username = '';
  email = '';
  password = '';
  confirmPassword = '';
  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly successMessage = signal<string | null>(null);

  constructor(
    private readonly auth: AuthService,
    private readonly router: Router,
  ) {}

  submit(form: NgForm): void {
    if (form.invalid || this.loading()) {
      return;
    }
    if (this.password !== this.confirmPassword) {
      this.errorMessage.set('Les mots de passe ne correspondent pas.');
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(null);

    this.auth
      .register({
        username: this.username,
        email: this.email,
        password: this.password,
        confirmPassword: this.confirmPassword,
      })
      .subscribe({
        next: () => {
          this.successMessage.set('Compte créé. Vous pouvez maintenant vous connecter.');
          this.loading.set(false);
          setTimeout(() => this.router.navigateByUrl('/login'), 1500);
        },
        error: (error: HttpErrorResponse) => {
          this.errorMessage.set(this.describeError(error));
          this.loading.set(false);
        },
      });
  }

  private describeError(error: HttpErrorResponse): string {
    if (error.status === 400) {
      return error.error?.message ?? 'Requête invalide.';
    }
    if (error.status === 0) {
      return "Impossible de joindre le serveur (vérifiez qu'il est démarré sur http://localhost:8080).";
    }
    if (error.status >= 500) {
      return 'Erreur serveur. Réessayez plus tard.';
    }
    return error.error?.message ?? `Échec de l'inscription (code ${error.status}).`;
  }
}
