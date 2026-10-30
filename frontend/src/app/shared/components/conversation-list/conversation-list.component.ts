import { Component, EventEmitter, Input, Output, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ChatContextService } from '../../../core/services/chat-context.service';
import { ConversationsService } from '../../../core/services/conversations.service';
import { Chat } from '../../../core/models/chat-session.model';
import { ConversationGroup, formatWhen, groupByRecency } from './conversation-groups';

/**
 * Liste des conversations de l'utilisateur, en deux usages :
 *  - mode « assistant » : un clic reprend la conversation dans l'Assistant (on peut y continuer d'écrire) ;
 *  - mode « history » : un clic l'affiche en lecture seule dans l'Historique (/chat-history/:id).
 * Renommer et supprimer se font en ligne (pas de fenêtres du navigateur) ; supprimer demande une
 * confirmation explicite dans la ligne. Les données viennent de ConversationsService (une seule source).
 */
@Component({
  selector: 'app-conversation-list',
  standalone: true,
  imports: [FormsModule, MatIconModule, MatTooltipModule],
  templateUrl: './conversation-list.component.html',
  styleUrl: './conversation-list.component.scss',
})
export class ConversationListComponent {
  @Input() mode: 'assistant' | 'history' = 'assistant';
  /** Conversation à mettre en évidence en mode « history » (id de la route). En mode « assistant » on suit ChatContextService. */
  @Input() activeId: number | null = null;

  /** Émis après l'ouverture d'une conversation (ex. pour refermer un tiroir sur mobile). */
  @Output() opened = new EventEmitter<Chat>();
  /** Émis par le bouton « Nouvelle » (mode assistant) : le parent réinitialise son écran de conversation. */
  @Output() newRequested = new EventEmitter<void>();
  /** Émis quand la conversation ACTIVE de l'assistant vient d'être supprimée : le parent doit repartir d'un écran neuf. */
  @Output() activeDeleted = new EventEmitter<void>();

  readonly filter = signal('');
  readonly editingId = signal<number | null>(null);
  readonly confirmingId = signal<number | null>(null);
  editValue = '';

  readonly groups = computed<ConversationGroup[]>(() => {
    const list = this.store.conversations();
    if (!list) {
      return [];
    }
    const term = this.filter().trim().toLowerCase();
    const shown = term ? list.filter(c => c.title.toLowerCase().includes(term)) : list;
    return groupByRecency(shown);
  });

  constructor(
    readonly store: ConversationsService,
    private readonly chatContext: ChatContextService,
    private readonly router: Router,
  ) {}

  isActive(chat: Chat): boolean {
    return this.mode === 'history' ? this.activeId === chat.id : this.chatContext.chatId() === chat.id;
  }

  when(chat: Chat): string {
    return formatWhen(chat.updatedAt);
  }

  open(chat: Chat): void {
    if (this.editingId() === chat.id) {
      return;
    }
    if (this.mode === 'history') {
      this.router.navigate(['/chat-history', chat.id]);
    } else {
      this.chatContext.openChat(chat);
      this.router.navigate(['/assistant']);
    }
    this.opened.emit(chat);
  }

  /** Bascule d'un mode à l'autre pour une même conversation (lecture seule ↔ continuer). */
  switchView(chat: Chat, event: Event): void {
    event.stopPropagation();
    if (this.mode === 'history') {
      this.chatContext.openChat(chat);
      this.router.navigate(['/assistant']);
    } else {
      this.router.navigate(['/chat-history', chat.id]);
    }
    this.opened.emit(chat);
  }

  startRename(chat: Chat, event: Event): void {
    event.stopPropagation();
    this.confirmingId.set(null);
    this.editValue = chat.title;
    this.editingId.set(chat.id);
    // Laisse Angular rendre le champ avant de lui donner le focus.
    setTimeout(() => document.getElementById(this.inputId(chat))?.focus());
  }

  saveRename(chat: Chat): void {
    const title = this.editValue;
    this.editingId.set(null);
    this.store.rename(chat, title);
  }

  cancelRename(): void {
    this.editingId.set(null);
  }

  askDelete(chat: Chat, event: Event): void {
    event.stopPropagation();
    this.editingId.set(null);
    this.confirmingId.set(chat.id);
  }

  cancelDelete(event: Event): void {
    event.stopPropagation();
    this.confirmingId.set(null);
  }

  confirmDelete(chat: Chat, event: Event): void {
    event.stopPropagation();
    const wasActive = this.isActive(chat);
    this.confirmingId.set(null);
    this.store.remove(chat, ok => {
      if (ok && wasActive) {
        if (this.mode === 'assistant') {
          // Le parent repart d'un écran neuf (ChatbotPageComponent.startNewConversation crée la
          // nouvelle conversation) : ne pas la créer ici en plus, sinon on en obtiendrait deux.
          this.activeDeleted.emit();
        } else {
          this.router.navigate(['/chat-history']);
        }
      }
    });
  }

  inputId(chat: Chat): string {
    return `conv-rename-${chat.id}`;
  }

  trackChat(_: number, chat: Chat): number {
    return chat.id;
  }
}
