import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { filter, map, startWith } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { ChatContextService } from '../../core/services/chat-context.service';
import { LayoutService } from '../../core/services/layout.service';
import { ConversationListComponent } from '../../shared/components/conversation-list/conversation-list.component';
import { ABOUT_ITEM, sectionsForRole } from '../nav-items';

/**
 * Colonne de navigation « index » : marque, sections nommées (Consulter / Référentiel T24 /
 * Administration selon le rôle), puis LA liste des conversations de l'application, juste sous la dernière
 * section (sous « Utilisateurs » pour un administrateur — demande explicite du 2026-10-06 : l'Assistant et
 * l'Historique n'ont plus leur propre liste). Sur l'Assistant, un clic reprend la conversation pour y écrire
 * et « Nouvelle » en démarre une ; ailleurs, un clic l'affiche en lecture dans l'Historique. Repli en colonne
 * d'icônes sur ordinateur (la liste est alors masquée) ; sur mobile/tablette la colonne devient un tiroir
 * (voir ShellComponent), refermé dès qu'une conversation est ouverte.
 */
@Component({
  selector: 'app-rail-nav',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, MatIconModule, MatTooltipModule, ConversationListComponent],
  templateUrl: './rail-nav.component.html',
  styleUrl: './rail-nav.component.scss',
})
export class RailNavComponent {
  private readonly router = inject(Router);
  private readonly chatContext = inject(ChatContextService);
  readonly about = ABOUT_ITEM;
  readonly sections = computed(() => sectionsForRole(this.auth.role()));
  /** Sur ordinateur, « replié » = colonne d'icônes ; sur mobile la colonne est ouverte quand elle est visible. */
  readonly compact = computed(() => this.layout.sidebarCollapsed() && !this.layout.isMobile());

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map(event => event.urlAfterRedirects),
      startWith(this.router.url),
    ),
    { initialValue: this.router.url },
  );

  /** Sur l'Assistant la liste sert à reprendre/démarrer une conversation ; ailleurs à la relire. */
  readonly listMode = computed<'assistant' | 'history'>(() => (this.url().startsWith('/assistant') ? 'assistant' : 'history'));

  /** Conversation affichée dans l'Historique (/chat-history/:id), mise en évidence dans la liste. */
  readonly activeConversationId = computed(() => {
    const match = /^\/chat-history\/(\d+)/.exec(this.url());
    return match ? Number(match[1]) : null;
  });

  constructor(
    private readonly auth: AuthService,
    readonly layout: LayoutService,
  ) {}

  onConversationOpened(): void {
    this.layout.closeDrawer();
  }

  /** « Nouvelle » (et suppression de la conversation active de l'Assistant) : l'Assistant repart d'un écran neuf. */
  newConversation(): void {
    this.chatContext.requestNewConversation();
    if (!this.url().startsWith('/assistant')) {
      this.router.navigate(['/assistant']);
    }
    this.layout.closeDrawer();
  }
}
