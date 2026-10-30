import { Injectable, signal } from '@angular/core';
import { Observable, map } from 'rxjs';
import { AuthService } from './auth.service';
import { UserService } from './user.service';
import { ChatHistoryService } from './chat-history.service';
import { ModuleService } from './module.service';
import { TableService } from './table.service';
import { ModuleT24 } from '../models/module-t24.model';
import { TableT24Dto } from '../models/table-t24.model';
import { Chat } from '../models/chat-session.model';

export type ChatContextStep = 'loading' | 'module' | 'done';

// 500 : le catalogue T24 compte ~416 modules au total (module_t24) — 200 ne couvrait que les
// premiers par ordre alphabétique et faisait passer à la trappe des modules comme LD/TT.
const CATALOG_PAGE_SIZE = 500;

/** Modules T24 proposés au parcours à bulles — les 7 modules prioritaires couverts par l'assistant
 * (AC, LD, LC, TT, EB, DE, MD, voir le prompt système de ChatbotServiceImpl), dans cet ordre. */
export const ALLOWED_MODULE_CODES = ['AC', 'LD', 'LC', 'TT', 'EB', 'DE', 'MD'];

/**
 * État du parcours à bulles de la page /assistant (module uniquement) et de la conversation associée.
 * Le contexte est persisté côté backend sur l'entité Chat (PUT /api/chats/{id}/context), donc
 * survit à un rechargement de page : `initForCurrentUser()` relit la conversation la plus récente
 * de l'utilisateur et saute directement à `done` si un module y est déjà fixé.
 *
 * Ni la table ni la colonne ne sont des étapes à bulles : une fois le module choisi, les tables de
 * ce module sont simplement affichées à titre indicatif (`tables`), puis l'utilisateur pose sa
 * question librement dans le chat — le backend (RagServiceImpl) détecte lui-même la table/colonne
 * mentionnées pour y restreindre sa réponse, sans sortir du module actif.
 */
@Injectable({ providedIn: 'root' })
export class ChatContextService {
  readonly step = signal<ChatContextStep>('loading');
  readonly chatId = signal<number | null>(null);

  readonly modules = signal<ModuleT24[]>([]);
  /** Tables du module choisi, affichées à titre indicatif une fois `selectModule()` résolu. */
  readonly tables = signal<TableT24Dto[]>([]);

  readonly selectedModule = signal<string | null>(null);

  /** true quand la conversation active vient d'être relue depuis la base (rechargement de page, ou
   * juste après `initForCurrentUser()`) plutôt que tout juste sélectionnée dans cette session — le
   * composant s'en sert pour savoir s'il doit recharger l'historique des messages ou repartir des
   * bulles module/annonce des tables. */
  readonly resumedWithHistory = signal(false);

  /** Incrémenté à chaque conversation créée, ouverte ou renommée depuis ce service — SidebarComponent
   * s'y abonne pour rafraîchir sa liste sans lien direct entre les deux composants (voir openChat,
   * renameCurrentChat). */
  readonly conversationsVersion = signal(0);

  /** « Nouvelle conversation » demandée hors de ChatbotPageComponent (liste des conversations de la colonne de
   * navigation, depuis n'importe quelle page) : le composant l'exécute dès qu'il est affiché, puis la remet à false. */
  readonly newConversationRequested = signal(false);

  requestNewConversation(): void {
    this.newConversationRequested.set(true);
  }

  /** Conversation active complète (pas seulement son id) : nécessaire pour renommer (PUT /api/chats/
   * {id} exige `userId` en plus du titre, voir ChatHistoryService.updateChat) sans que l'appelant
   * (ChatbotPageComponent) ait à la re-résoudre lui-même. */
  private currentChat: Chat | null = null;

  constructor(
    private readonly auth: AuthService,
    private readonly userService: UserService,
    private readonly chatHistoryService: ChatHistoryService,
    private readonly moduleService: ModuleService,
    private readonly tableService: TableService,
  ) {}

  /** Même limitation/contournement que SidebarComponent.loadConversations() : le backend n'expose
   * pas encore /api/users/me, donc on retrouve l'utilisateur courant par username. */
  initForCurrentUser(): void {
    this.step.set('loading');
    this.currentUserId().subscribe(userId => {
      if (userId === null) {
        this.step.set('module');
        this.loadModules();
        return;
      }

      this.chatHistoryService.getChats(userId, 0, 1).subscribe(chatPage => {
        if (chatPage.content.length > 0) {
          this.applyChat(chatPage.content[0], true);
          return;
        }
        this.chatHistoryService.createChat('Nouvelle conversation', userId).subscribe(chat => this.applyChat(chat, false));
      });
    });
  }

  /** Bouton "Nouvelle conversation" : crée une vraie conversation en base (visible séparément dans
   * l'historique), avec son propre contexte module/table à reconstituer depuis les bulles. */
  newConversation(): void {
    this.step.set('loading');
    this.currentUserId().subscribe(userId => {
      if (userId === null) {
        this.step.set('module');
        this.loadModules();
        return;
      }
      this.chatHistoryService.createChat('Nouvelle conversation', userId).subscribe(chat => this.applyChat(chat, false));
    });
  }

  /** Sélection du module : charge ses tables (affichage indicatif) puis fixe le contexte (module
   * seul, pas de table) dès qu'elles sont disponibles. */
  selectModule(code: string): void {
    this.selectedModule.set(code);
    this.tables.set([]);
    this.tableService.getByModule(code, 0, CATALOG_PAGE_SIZE).subscribe(page => {
      this.tables.set(page.content);
      this.finish();
    });
  }

  /**
   * Ouvre une conversation existante (depuis la liste "Conversations" de la barre latérale) dans
   * l'Assistant T24, avec son historique et son contexte T24 déjà enregistrés — au lieu du
   * visualiseur /chat-history/:id, qui est en lecture seule et ne permet pas d'y répondre.
   */
  openChat(chat: Chat): void {
    this.step.set('loading');
    this.applyChat(chat, true);
  }

  /** Renomme la conversation active (ex. titre déduit automatiquement du premier message, voir
   * ChatbotPageComponent) — même endpoint que le renommage manuel depuis la barre latérale. */
  renameCurrentChat(title: string): void {
    if (!this.currentChat) {
      return;
    }
    this.chatHistoryService.updateChat(this.currentChat, title).subscribe(updated => {
      this.currentChat = updated;
      this.conversationsVersion.update(v => v + 1);
    });
  }

  /** Bouton "Changer le contexte" : revient à l'étape 1, sans perdre la conversation en cours. */
  reset(): void {
    this.selectedModule.set(null);
    this.tables.set([]);
    this.resumedWithHistory.set(false);
    this.step.set('module');
    this.loadModules();
  }

  private finish(): void {
    const chatId = this.chatId();
    const module = this.selectedModule();
    if (chatId === null || module === null) {
      return;
    }
    this.chatHistoryService.updateContext(chatId, { module }).subscribe(() => this.step.set('done'));
  }

  private applyChat(chat: Chat, resumed: boolean): void {
    this.currentChat = chat;
    this.conversationsVersion.update(v => v + 1);
    this.chatId.set(chat.id);
    this.resumedWithHistory.set(resumed && !!chat.selectedModule);
    if (chat.selectedModule) {
      this.selectedModule.set(chat.selectedModule);
      this.step.set('done');
      return;
    }
    this.selectedModule.set(null);
    this.tables.set([]);
    this.step.set('module');
    this.loadModules();
  }

  private loadModules(): void {
    if (this.modules().length > 0) {
      return;
    }
    this.moduleService.getAll(0, CATALOG_PAGE_SIZE).subscribe(page => {
      const allowed = ALLOWED_MODULE_CODES
        .map(code => page.content.find(m => m.nomModule === code))
        .filter((m): m is ModuleT24 => !!m);
      this.modules.set(allowed);
    });
  }

  /** @returns l'id numérique de l'utilisateur courant, ou `null` si non résolvable (backend
   * n'expose pas /api/users/me, voir `initForCurrentUser`). */
  private currentUserId(): Observable<number | null> {
    return this.userService
      .getAll(0, CATALOG_PAGE_SIZE)
      .pipe(map(page => page.content.find(u => u.username === this.auth.username())?.id ?? null));
  }
}
