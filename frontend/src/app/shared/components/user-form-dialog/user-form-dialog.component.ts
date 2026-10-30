import { Component, Inject, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { User, UserRequest, UserRole } from '../../../core/models/user.model';
import { UserService } from '../../../core/services/user.service';
import { AuthService } from '../../../core/services/auth.service';

export interface UserFormDialogData {
  user: User | null;
}

/** Réutilisable (shared/components) : formulaire de création/édition d'utilisateur, ouvert en MatDialog
 * depuis UsersPageComponent. */
@Component({
  selector: 'app-user-form-dialog',
  standalone: true,
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatSlideToggleModule,
  ],
  templateUrl: './user-form-dialog.component.html',
  styleUrl: './user-form-dialog.component.scss',
})
export class UserFormDialogComponent {
  // Un ADMIN ne peut gérer que des CONSULTANT (backend autoritaire dans UserServiceImpl, ce
  // masquage est une défense en profondeur) : le sélecteur ne lui propose que ce seul rôle.
  readonly roles: UserRole[];
  readonly isEdit: boolean;
  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);

  username: string;
  email: string;
  password = '';
  role: UserRole;
  isActive: boolean;

  private readonly editedUserId: number | null;

  constructor(
    private readonly dialogRef: MatDialogRef<UserFormDialogComponent, User | undefined>,
    private readonly userService: UserService,
    auth: AuthService,
    @Inject(MAT_DIALOG_DATA) data: UserFormDialogData,
  ) {
    this.roles = auth.hasRole('SUPER_ADMIN') ? ['CONSULTANT', 'ADMIN', 'SUPER_ADMIN'] : ['CONSULTANT'];
    this.isEdit = data.user !== null;
    this.editedUserId = data.user?.id ?? null;
    this.username = data.user?.username ?? '';
    this.email = data.user?.email ?? '';
    this.role = data.user?.role ?? 'CONSULTANT';
    this.isActive = data.user?.isActive ?? true;
  }

  submit(form: NgForm): void {
    if (form.invalid || this.saving()) {
      return;
    }

    this.saving.set(true);
    this.errorMessage.set(null);

    // UserDTO.password est @NotBlank côté backend, y compris pour PUT (le service ignore un mot
    // de passe blanc, mais la validation du DTO le refuse avant même d'atteindre le service) :
    // le mot de passe est donc requis à chaque enregistrement, création comme édition.
    const request: UserRequest = {
      username: this.username,
      email: this.email,
      role: this.role,
      isActive: this.isActive,
      password: this.password,
    };

    const call = this.editedUserId !== null
      ? this.userService.update(this.editedUserId, request)
      : this.userService.create(request);

    call.subscribe({
      next: user => {
        this.saving.set(false);
        this.dialogRef.close(user);
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage.set(error.error?.message ?? "Échec de l'enregistrement.");
        this.saving.set(false);
      },
    });
  }

  cancel(): void {
    this.dialogRef.close(undefined);
  }
}
