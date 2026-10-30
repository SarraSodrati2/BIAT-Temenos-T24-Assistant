import { Injectable, effect, signal } from '@angular/core';
import { AuthService } from './auth.service';
import { ChatContextService } from './chat-context.service';
import { ChatHistoryService } from './chat-history.service';
import { UserService } from './user.service';
import { Chat } from '../models/chat-session.model';

/** Nombre de conversations chargées pour les listes (assistant, historique, accueil). */
const CONVERSATIONS_PAGE_SIZE = 50;

/**
 * Liste des conversations de l'utilisateur courant, partagée par l'accueil, l'espace Assistant et
 * l'Historique (une seule source, donc toujours cohérente entre les écrans). Se rafraîchit
 * automatiquement dès qu'une conversation est créée, ouverte ou renommée (voir
 * ChatContextService.conversationsVersion).
 *
 * Le backend n'expose pas /api/users/me : l'id de l'utilisateur courant est retrouvé une fois par son
 * username dans GET /api/users (même contournement que ChatContextService).
 */
@Injectable({ providedIn: 'root' })
export class ConversationsService {
  /** null = pas encore chargé (affiche un squelette), [] = aucune conversation. */
  readonly conversations = signal<Chat[] | null>(null);
  readonly error = signal<string | null>(null);

  private userId: number | null = null;
  /** Username pour lequel `userId` a été résolu : un autre utilisateur se connectant ensuite invalide le cache. */
  private userIdFor: string | null = null;
  private isFirstVersion = true;

  constructor(
    private readonly auth: AuthService,
    private readonly userService: UserService,
    private readonly chatHistoryService: ChatHistoryService,
    private readonly chatContext: ChatContextService,
  ) {
    effect(() => {
      this.chatContext.conversationsVersion();
      if (this.isFirstVersion) {
        this.isFirstVersion = false;
        return;
      }
      // load() émet des requêtes HTTP dont loadingInterceptor écrit un signal : hors du contexte réactif
      // de l'effet (sinon NG0600, voir le même correctif dans ChatbotPageComponent).
      queueMicrotask(() => this.load());
    });
  }

  /** (Re)charge la liste. Sans effet si l'utilisateur n'est pas connecté. */
  load(): void {
    if (!this.auth.isAuthenticated()) {
      this.conversations.set(null);
      return;
    }
    this.error.set(null);
    this.resolveUserId(userId => {
      if (userId === null) {
        this.conversations.set([]);
        return;
      }
      this.chatHistoryService.getChats(userId, 0, CONVERSATIONS_PAGE_SIZE).subscribe({
        next: page => this.conversations.set(page.content),
        error: () => {
          this.error.set('Impossible de charger vos conversations.');
          if (this.conversations() === null) {
            this.conversations.set([]);
          }
        },
      });
    });
  }

  /** Renomme (PUT /api/chats/{id}) puis met la liste à jour localement, sans recharger. */
  rename(chat: Chat, title: string, onDone?: (ok: boolean) => void): void {
    const clean = title.trim();
    if (!clean || clean === chat.title) {
      onDone?.(false);
      return;
    }
    this.chatHistoryService.updateChat(chat, clean).subscribe({
      next: updated => {
        this.conversations.update(list => list?.map(c => (c.id === updated.id ? { ...c, title: updated.title } : c)) ?? list);
        onDone?.(true);
      },
      error: () => onDone?.(false),
    });
  }

  remove(chat: Chat, onDone?: (ok: boolean) => void): void {
    this.chatHistoryService.deleteChat(chat.id).subscribe({
      next: () => {
        this.conversations.update(list => list?.filter(c => c.id !== chat.id) ?? list);
        onDone?.(true);
      },
      error: () => onDone?.(false),
    });
  }

  private resolveUserId(then: (userId: number | null) => void): void {
    if (this.userId !== null && this.userIdFor === this.auth.username()) {
      then(this.userId);
      return;
    }
    this.userService.getAll(0, 500).subscribe({
      next: page => {
        this.userIdFor = this.auth.username();
        this.userId = page.content.find(u => u.username === this.userIdFor)?.id ?? null;
        then(this.userId);
      },
      error: () => {
        this.error.set('Impossible de charger vos conversations.');
        this.conversations.set([]);
      },
    });
  }
}
