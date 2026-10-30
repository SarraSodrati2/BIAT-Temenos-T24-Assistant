import { Component, DestroyRef, OnInit, computed } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { filter } from 'rxjs';
import { BackendStatusService } from '../../core/services/backend-status.service';
import { ConversationsService } from '../../core/services/conversations.service';
import { LayoutService } from '../../core/services/layout.service';
import { RecentsService } from '../../core/services/recents.service';
import { BottomNavComponent } from '../bottom-nav/bottom-nav.component';
import { RailNavComponent } from '../rail-nav/rail-nav.component';
import { TopbarComponent } from '../topbar/topbar.component';

/**
 * Coque de l'application connectée : colonne de navigation (tiroir sur mobile), barre du haut,
 * zone de contenu et onglets du bas sur téléphone. Affiche aussi un bandeau clair quand le serveur
 * est injoignable, plutôt que de laisser chaque page échouer en silence.
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, MatIconModule, RailNavComponent, TopbarComponent, BottomNavComponent],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent implements OnInit {
  readonly compact = computed(() => this.layout.sidebarCollapsed() && !this.layout.isMobile());
  readonly drawerOpen = computed(() => this.layout.isMobile() && !this.layout.sidebarCollapsed());

  constructor(
    readonly layout: LayoutService,
    readonly backend: BackendStatusService,
    private readonly recents: RecentsService,
    private readonly conversations: ConversationsService,
    router: Router,
    destroyRef: DestroyRef,
  ) {
    router.events
      .pipe(filter(e => e instanceof NavigationEnd), takeUntilDestroyed(destroyRef))
      .subscribe(() => layout.closeDrawer());
  }

  ngOnInit(): void {
    this.recents.reload();
    this.conversations.load();
  }

  focusMain(event: Event): void {
    event.preventDefault();
    document.getElementById('contenu')?.focus();
  }
}
