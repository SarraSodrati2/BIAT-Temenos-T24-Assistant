import { Component, DestroyRef, computed, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { filter } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { ThemeService } from '../../core/services/theme.service';
import { SearchBoxComponent } from '../../shared/components/search-box/search-box.component';
import { describeRoute } from '../nav-items';

/**
 * Barre du haut : la bascule clair/sombre est volontairement le TOUT PREMIER contrôle (immédiatement
 * repérable dès l'ouverture), suivie du repère de page, de la recherche globale et du compte
 * (déconnexion toujours visible, jamais cachée dans un menu).
 */
@Component({
  selector: 'app-topbar',
  standalone: true,
  imports: [RouterLink, MatIconModule, MatMenuModule, MatTooltipModule, SearchBoxComponent],
  templateUrl: './topbar.component.html',
  styleUrl: './topbar.component.scss',
})
export class TopbarComponent {
  private readonly routeInfo = signal<{ title: string; section: string | null }>({ title: '', section: null });
  readonly title = computed(() => this.routeInfo().title);
  readonly section = computed(() => this.routeInfo().section);

  constructor(
    private readonly router: Router,
    readonly auth: AuthService,
    readonly theme: ThemeService,
    destroyRef: DestroyRef,
  ) {
    // Initialisé ici (et non dans l'initialiseur du champ) : `router` est un paramètre du constructeur.
    this.routeInfo.set(describeRoute(this.router.url));
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd), takeUntilDestroyed(destroyRef))
      .subscribe(e => this.routeInfo.set(describeRoute(e.urlAfterRedirects)));
  }

  get initials(): string {
    const name = this.auth.username();
    return name ? name.slice(0, 2).toUpperCase() : '?';
  }

  roleLabel(): string {
    switch (this.auth.role()) {
      case 'SUPER_ADMIN': return 'Super administrateur';
      case 'ADMIN': return 'Administrateur';
      case 'CONSULTANT': return 'Consultant';
      default: return '';
    }
  }

  logout(): void {
    this.auth.logout();
  }
}
