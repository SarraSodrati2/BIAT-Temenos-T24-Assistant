import { Component, OnInit, computed, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../../core/services/auth.service';
import { ALLOWED_MODULE_CODES, ChatContextService } from '../../core/services/chat-context.service';
import { ConversationsService } from '../../core/services/conversations.service';
import { ModuleService } from '../../core/services/module.service';
import { RecentsService } from '../../core/services/recents.service';
import { TableService } from '../../core/services/table.service';
import { Chat } from '../../core/models/chat-session.model';
import { SearchBoxComponent } from '../../shared/components/search-box/search-box.component';
import { formatWhen } from '../../shared/components/conversation-list/conversation-groups';

const RECENT_CONVERSATIONS_SHOWN = 5;
const RECENT_ENTRIES_SHOWN = 6;

/**
 * Accueil (route /dashboard) : point de départ orienté action — recherche du référentiel en grand,
 * accès direct à l'assistant, reprise des conversations et retour aux éléments réellement consultés.
 * Aucune donnée d'exemple : chaque bloc affiche soit du contenu réel, soit un état vide explicite.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [RouterLink, MatIconModule, SearchBoxComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent implements OnInit {
  readonly assistantModules = ALLOWED_MODULE_CODES;

  /** null = en cours de chargement, -1 = échec (affiché « — »). */
  readonly tableCount = signal<number | null>(null);
  readonly moduleCount = signal<number | null>(null);

  readonly recentConversations = computed(() => this.conversations.conversations()?.slice(0, RECENT_CONVERSATIONS_SHOWN) ?? null);
  readonly recentEntries = computed(() => this.recents.entries().slice(0, RECENT_ENTRIES_SHOWN));

  constructor(
    readonly auth: AuthService,
    readonly conversations: ConversationsService,
    private readonly recents: RecentsService,
    private readonly chatContext: ChatContextService,
    private readonly moduleService: ModuleService,
    private readonly tableService: TableService,
    private readonly router: Router,
  ) {}

  ngOnInit(): void {
    this.tableService.getAll(0, 1).subscribe({ next: p => this.tableCount.set(p.totalElements), error: () => this.tableCount.set(-1) });
    this.moduleService.getAll(0, 1).subscribe({ next: p => this.moduleCount.set(p.totalElements), error: () => this.moduleCount.set(-1) });
  }

  formatCount(value: number | null): string {
    if (value === null) {
      return '…';
    }
    return value < 0 ? '—' : value.toLocaleString('fr-FR');
  }

  when(chat: Chat): string {
    return formatWhen(chat.updatedAt);
  }

  resume(chat: Chat): void {
    this.chatContext.openChat(chat);
    this.router.navigate(['/assistant']);
  }

  openRecent(kind: 'module' | 'table', name: string): void {
    this.router.navigate([kind === 'module' ? '/modules' : '/tables'], { queryParams: { [kind]: name } });
  }

  openModule(code: string): void {
    this.router.navigate(['/modules'], { queryParams: { module: code } });
  }
}
