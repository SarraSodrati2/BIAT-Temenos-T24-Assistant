import { Component, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog } from '@angular/material/dialog';
import { UserService } from '../../core/services/user.service';
import { AuthService } from '../../core/services/auth.service';
import { User } from '../../core/models/user.model';
import { UserFormDialogComponent, UserFormDialogData } from '../../shared/components/user-form-dialog/user-form-dialog.component';

/** Le backend n'expose pas de recherche serveur pour /api/users (contrairement à /api/table-t24/search) :
 * la recherche et la pagination se font donc côté client sur le lot chargé (comme ModulesPageComponent),
 * ce qui reste adapté à une liste d'utilisateurs internes de taille raisonnable. */
const USERS_PAGE_SIZE = 200;

@Component({
  selector: 'app-users-page',
  standalone: true,
  imports: [
    FormsModule,
    MatIconModule,
    MatButtonModule,
    MatTableModule,
    MatPaginatorModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './users-page.component.html',
  styleUrl: './users-page.component.scss',
})
export class UsersPageComponent implements OnInit {
  readonly displayedColumns = ['username', 'email', 'role', 'isActive', 'actions'];
  readonly users = signal<User[] | null>(null);
  searchTerm = '';
  pageIndex = 0;
  pageSize = 10;

  readonly filteredUsers = computed(() => {
    const users = this.users();
    if (!users) {
      return [];
    }
    const term = this.searchTerm.trim().toLowerCase();
    if (!term) {
      return users;
    }
    return users.filter(u => u.username.toLowerCase().includes(term) || u.email.toLowerCase().includes(term));
  });

  readonly pagedUsers = computed(() => {
    const start = this.pageIndex * this.pageSize;
    return this.filteredUsers().slice(start, start + this.pageSize);
  });

  constructor(
    private readonly userService: UserService,
    private readonly dialog: MatDialog,
    public readonly auth: AuthService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  onSearch(): void {
    this.pageIndex = 0;
  }

  onPage(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
  }

  openCreateDialog(): void {
    this.openDialog(null);
  }

  openEditDialog(user: User): void {
    this.openDialog(user);
  }

  deleteUser(user: User): void {
    if (!confirm(`Supprimer l'utilisateur "${user.username}" ? Cette action est irréversible.`)) {
      return;
    }
    this.userService.delete(user.id).subscribe(() => this.load());
  }

  private openDialog(user: User | null): void {
    const data: UserFormDialogData = { user };
    this.dialog
      .open(UserFormDialogComponent, { data, width: '420px' })
      .afterClosed()
      .subscribe(result => {
        if (result) {
          this.load();
        }
      });
  }

  private load(): void {
    this.users.set(null);
    this.userService.getAll(0, USERS_PAGE_SIZE).subscribe(page => this.users.set(page.content));
  }
}
