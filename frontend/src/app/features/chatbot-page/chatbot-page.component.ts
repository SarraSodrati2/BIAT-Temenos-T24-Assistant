import { Component, ElementRef, EventEmitter, Input, OnDestroy, OnInit, Output, ViewChild, computed, effect, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription } from 'rxjs';
import { ChatbotService } from '../../core/services/chatbot.service';
import { ChatContextService } from '../../core/services/chat-context.service';
import { ChatHistoryService } from '../../core/services/chat-history.service';
import { formatAnswerCached } from '../../shared/answer-format';

interface DisplayMessage {
  /** Id ChatHistory réel si `persisted`, sinon un id local négatif (accueil, erreur réseau...) —
   * jamais 0 pour ne pas collisionner avec un vrai id. */
  id: number;
  sender: 'USER' | 'AI';
  content: string;
  error?: boolean;
  /** true si `id` est un vrai id ChatHistory (autorise copier/modifier/supprimer côté backend). */
  persisted: boolean;
}

const WELCOME_MESSAGE: DisplayMessage = {
  id: -1,
  sender: 'AI',
  content: 'Bonjour, posez-moi une question sur la documentation T24 (HELP_TEXT/HELP_FIELD).',
  persisted: false,
};

/** Délai avant de faire avancer le parcours à bulles, pour laisser voir l'état "sélectionné" de la
 * bulle cliquée avant qu'elle ne disparaisse avec le reste de l'étape courante. */
const CHIP_SELECTION_DELAY_MS = 180;

/** Durée d'affichage de l'icône "Copié" après un clic sur le bouton copier d'un message. */
const COPY_FEEDBACK_MS = 1500;

/** Longueur maximale d'un titre de conversation déduit automatiquement de la première question. */
const AUTO_TITLE_MAX_LENGTH = 48;

/** Au-delà de cette longueur (caractères), une réponse est repliée par défaut (« Afficher la réponse
 * complète ») : rien n'est retiré, seulement replié — les listages complets d'un module sont très longs. */
const LONG_ANSWER_THRESHOLD = 3500;

/** Après ce délai de génération, on explique à l'utilisateur que c'est normal (modèle local, CPU). */
const SLOW_HINT_AFTER_S = 12;

/** Assistant T24 : point d'entrée unique de la consultation conversationnelle (route /assistant),
 * regroupant le chatbot RAG (POST /api/chat/ask), le contexte de conversation (module) et les
 * exemples de questions. La liste des conversations est fournie par AssistantWorkspaceComponent. */
@Component({
  selector: 'app-chatbot-page',
  standalone: true,
  imports: [FormsModule, MatIconModule, MatTooltipModule],
  templateUrl: './chatbot-page.component.html',
  styleUrl: './chatbot-page.component.scss',
})
export class ChatbotPageComponent implements OnInit, OnDestroy {
  @ViewChild('scrollAnchor') private scrollAnchor?: ElementRef<HTMLDivElement>;
  @ViewChild('composer') private composer?: ElementRef<HTMLTextAreaElement>;

  /** Titre de la conversation active (fourni par l'espace Assistant, qui possède la liste). */
  @Input() conversationTitle?: string | null;
  /** Question à pré-remplir (ex. depuis la recherche globale) — jamais envoyée sans action de l'utilisateur. */
  @Input() set prefill(value: string | null | undefined) {
    if (value && value.trim()) {
      this.useQuestion(value);
    }
  }
  /** Émis par le bouton « Conversations » (visible sur petit écran, où la liste est un tiroir). */
  @Output() openConversations = new EventEmitter<void>();
  /** Émis après « Nouvelle conversation » (le parent peut vouloir refermer son tiroir). */
  @Output() conversationStarted = new EventEmitter<void>();

  readonly messages = signal<DisplayMessage[]>([WELCOME_MESSAGE]);
  readonly loading = signal(false);
  /** Secondes écoulées pendant la génération d'une réponse (indication d'attente honnête). */
  readonly elapsed = signal(0);
  readonly slowHintAfter = SLOW_HINT_AFTER_S;

  /** Valeur de la bulle tout juste cliquée, pour l'animation de sélection (voir CHIP_SELECTION_DELAY_MS). */
  readonly pendingSelection = signal<string | null>(null);
  /** Id du message dont le contenu vient d'être copié, pour afficher brièvement une icône de confirmation. */
  readonly copiedMessageId = signal<number | null>(null);
  /** Ids des réponses longues dépliées par l'utilisateur. */
  readonly expanded = signal<ReadonlySet<number>>(new Set());
  readonly showTables = signal(false);

  /** Aucun échange réel dans la conversation (seulement l'accueil) → écran d'accueil de l'assistant. */
  readonly isEmpty = computed(() => this.messages().every(m => m.id === WELCOME_MESSAGE.id));

  /** Exemples de questions construits à partir du module actif et de ses VRAIES tables (jamais fictifs). */
  readonly exampleQuestions = computed(() => {
    const module = this.chatContext.selectedModule();
    if (!module) {
      return [];
    }
    const tables = this.chatContext.tables();
    const questions = [`À quoi sert le module ${module} ?`, `Donne-moi toutes les informations du module ${module}`];
    if (tables.length > 0) {
      questions.push(`Décris la table ${tables[0].nomTable}`);
    }
    if (tables.length > 1) {
      questions.push(`Quels champs contient ${tables[1].nomTable} ?`);
    }
    return questions;
  });

  /** Id du dernier message s'il s'agit d'une erreur (seul celui-ci propose « Réessayer »). */
  readonly lastErrorId = computed(() => {
    const all = this.messages();
    const last = all[all.length - 1];
    return last?.error ? last.id : null;
  });

  draft = '';
  /** Compteur décroissant : les vrais id ChatHistory sont toujours positifs, jamais de collision. */
  private nextLocalId = -2;
  /** Id du chat dont l'historique a déjà été chargé (évite un rechargement en boucle). */
  private loadedHistoryForChatId: number | null = null;
  /** true dès que la conversation active a reçu son titre automatique (déduit de son premier
   * message, voir `send()`) — jamais deux fois pour la même conversation, réinitialisé à chaque
   * changement de conversation par l'effet ci-dessous. */
  private hasAutoTitled = false;
  private lastQuestion: string | null = null;
  private pending?: Subscription;
  private ticker?: ReturnType<typeof setInterval>;

  constructor(
    private readonly chatbot: ChatbotService,
    private readonly chatHistoryService: ChatHistoryService,
    readonly chatContext: ChatContextService,
    private readonly router: Router,
  ) {
    effect(() => {
      this.messages();
      queueMicrotask(() => this.scrollAnchor?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'end' }));
    });

    // À chaque changement de conversation active (nouvelle, reprise depuis la liste), le titre
    // automatique redevient possible pour SA propre première question — jamais pour une conversation
    // déjà titrée dans un tour précédent.
    effect(() => {
      this.chatContext.chatId();
      this.hasAutoTitled = false;
    });

    // « Nouvelle » cliqué dans la colonne de navigation alors que l'Assistant est déjà affiché. Écriture du signal
    // hors du contexte réactif (queueMicrotask) : Angular 18 refuse l'écriture de signal dans un effect.
    effect(() => {
      if (this.chatContext.newConversationRequested()) {
        queueMicrotask(() => {
          if (this.chatContext.newConversationRequested()) {
            this.chatContext.newConversationRequested.set(false);
            this.startNewConversation();
          }
        });
      }
    });

    // Conversation relue depuis la base (rechargement de page, ou ouverte depuis la liste) : recharge les
    // vrais messages une fois, avec leurs ids ChatHistory (nécessaires pour modifier/supprimer).
    effect(() => {
      const chatId = this.chatContext.chatId();
      if (
        this.chatContext.step() === 'done' &&
        chatId !== null &&
        this.chatContext.resumedWithHistory() &&
        this.loadedHistoryForChatId !== chatId
      ) {
        this.loadedHistoryForChatId = chatId;
        // loadHistory() déclenche un appel HTTP dont loadingInterceptor écrit un signal
        // (LoadingService) : fait de façon synchrone ici, Angular le refuse (NG0600). queueMicrotask
        // sort de ce contexte réactif.
        queueMicrotask(() => this.loadHistory(chatId));
      }
    });
  }

  ngOnInit(): void {
    // « Nouvelle » cliqué dans la colonne de navigation depuis une autre page : on arrive ici pour la créer.
    if (this.chatContext.newConversationRequested()) {
      this.chatContext.newConversationRequested.set(false);
      this.startNewConversation();
      return;
    }
    // Une conversation est peut-être déjà active (ouverte depuis la liste juste avant cette
    // navigation) : ne pas l'écraser en resynchronisant sur "la conversation la plus récente".
    if (this.chatContext.chatId() === null) {
      this.chatContext.initForCurrentUser();
    }
  }

  ngOnDestroy(): void {
    this.pending?.unsubscribe();
    this.stopTicker();
  }

  chooseModule(code: string): void {
    this.pendingSelection.set(code);
    setTimeout(() => this.chatContext.selectModule(code), CHIP_SELECTION_DELAY_MS);
  }

  /** Bouton "Nouvelle conversation" : vide l'affichage et crée une vraie conversation en base. */
  startNewConversation(): void {
    this.cancelPending();
    this.messages.set([WELCOME_MESSAGE]);
    this.loadedHistoryForChatId = null;
    this.draft = '';
    this.lastQuestion = null;
    this.expanded.set(new Set());
    this.showTables.set(false);
    this.resizeComposer();
    this.chatContext.newConversation();
    this.conversationStarted.emit();
  }

  changeModule(): void {
    this.showTables.set(false);
    this.chatContext.reset();
  }

  send(): void {
    const question = this.draft.trim();
    if (!question || this.loading() || this.chatContext.step() !== 'done') {
      return;
    }

    // Décidé avant l'appel : la toute première question d'une conversation neuve (jamais reprise
    // depuis l'historique) lui donne son titre, déduit de cette question — comme un assistant IA
    // moderne, plutôt que de laisser "Nouvelle conversation" partout.
    const shouldAutoTitle = !this.hasAutoTitled && !this.chatContext.resumedWithHistory();

    this.messages.update(msgs => [...msgs, { id: this.nextLocalId--, sender: 'USER', content: question, persisted: false }]);
    this.draft = '';
    this.resizeComposer();
    this.lastQuestion = question;
    this.dispatch(question, shouldAutoTitle);
  }

  ask(question: string): void {
    this.draft = question;
    this.send();
  }

  /** Place une question dans la zone de saisie (sans l'envoyer) et y met le focus. */
  useQuestion(question: string): void {
    this.draft = question.trim();
    this.resizeComposer();
    setTimeout(() => this.focusComposer());
  }

  /** Renvoie la dernière question après une erreur, sans la ré-afficher deux fois. */
  retry(): void {
    if (!this.lastQuestion || this.loading()) {
      return;
    }
    // Retire le message d'erreur affiché en dernier avant de relancer.
    this.messages.update(msgs => (msgs.length > 0 && msgs[msgs.length - 1].error ? msgs.slice(0, -1) : msgs));
    this.dispatch(this.lastQuestion, false);
  }

  /** Interrompt l'attente : la requête est abandonnée côté navigateur (le serveur peut la terminer). */
  stop(): void {
    if (!this.loading()) {
      return;
    }
    this.cancelPending();
    this.pushLocalMessage('AI', 'Génération interrompue. Vous pouvez reformuler ou renvoyer la question.', true);
  }

  private dispatch(question: string, shouldAutoTitle: boolean): void {
    // Garde-fou : jamais deux requêtes simultanées (double clic, Entrée + bouton, « Réessayer » pendant
    // une attente) — la seconde serait un doublon de la première, dont le serveur enregistre déjà l'échange.
    if (this.loading()) {
      return;
    }
    this.loading.set(true);
    this.startTicker();

    this.pending = this.chatbot.ask(question, this.chatContext.chatId()).subscribe({
      next: response => {
        this.messages.update(msgs => {
          const updated = [...msgs];
          // Associe l'id réel du message utilisateur (dernier message USER envoyé).
          const lastUser = updated.map(m => m.sender).lastIndexOf('USER');
          if (lastUser >= 0 && response.userMessageId != null) {
            updated[lastUser] = { ...updated[lastUser], id: response.userMessageId, persisted: true };
          }
          updated.push({
            id: response.assistantMessageId ?? this.nextLocalId--,
            sender: 'AI',
            content: response.answer,
            persisted: response.assistantMessageId != null,
          });
          return updated;
        });
        if (shouldAutoTitle && response.userMessageId != null) {
          this.hasAutoTitled = true;
          this.chatContext.renameCurrentChat(this.deriveTitleFromMessage(question));
        }
        this.finishLoading();
      },
      error: (error: HttpErrorResponse) => {
        this.pushLocalMessage('AI', this.describeError(error), true);
        this.finishLoading();
      },
    });
  }

  private cancelPending(): void {
    this.pending?.unsubscribe();
    this.pending = undefined;
    this.finishLoading();
  }

  private finishLoading(): void {
    this.loading.set(false);
    this.stopTicker();
    setTimeout(() => this.focusComposer());
  }

  private startTicker(): void {
    this.stopTicker();
    this.elapsed.set(0);
    this.ticker = setInterval(() => this.elapsed.update(s => s + 1), 1000);
  }

  private stopTicker(): void {
    if (this.ticker) {
      clearInterval(this.ticker);
      this.ticker = undefined;
    }
  }

  /** Message d'erreur compréhensible, selon la vraie cause (jamais un « erreur inconnue » générique). */
  private describeError(error: HttpErrorResponse): string {
    if (error.status === 0) {
      return "Le serveur est injoignable. Vérifiez qu'il est démarré, puis réessayez.";
    }
    if (error.status === 401) {
      return 'Votre session a expiré. Reconnectez-vous pour continuer.';
    }
    if (error.status === 403) {
      return "Vous n'avez pas les droits nécessaires pour cette action.";
    }
    if (error.status >= 500) {
      return "Le serveur a rencontré une erreur en préparant la réponse. Réessayez dans un instant ; si le problème persiste, prévenez l'équipe technique.";
    }
    return error.error?.message ?? "Une erreur est survenue lors de l'appel à l'assistant.";
  }

  /** Titre court déduit de la première question d'une conversation (espaces normalisés, tronqué
   * avec "…" si trop long) — sans appel LLM, pour rester instantané. */
  private deriveTitleFromMessage(message: string): string {
    const cleaned = message.replace(/\s+/g, ' ').trim();
    return cleaned.length > AUTO_TITLE_MAX_LENGTH
      ? cleaned.slice(0, AUTO_TITLE_MAX_LENGTH).trimEnd() + '…'
      : cleaned;
  }

  // --- Saisie ----------------------------------------------------------------------------------

  onComposerKeydown(event: KeyboardEvent): void {
    // Entrée envoie, Maj+Entrée insère un retour à la ligne ; jamais pendant une composition (IME).
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      this.send();
    }
  }

  onDraftInput(): void {
    this.resizeComposer();
  }

  private resizeComposer(): void {
    setTimeout(() => {
      const el = this.composer?.nativeElement;
      if (el) {
        el.style.height = 'auto';
        el.style.height = Math.min(el.scrollHeight, 200) + 'px';
      }
    });
  }

  private focusComposer(): void {
    this.composer?.nativeElement.focus();
  }

  // --- Affichage des réponses ------------------------------------------------------------------

  isLong(message: DisplayMessage): boolean {
    return message.sender === 'AI' && !message.error && message.content.length > LONG_ANSWER_THRESHOLD;
  }

  isExpanded(message: DisplayMessage): boolean {
    return this.expanded().has(message.id);
  }

  toggleExpanded(message: DisplayMessage): void {
    this.expanded.update(current => {
      const next = new Set(current);
      if (!next.delete(message.id)) {
        next.add(message.id);
      }
      return next;
    });
  }

  /** Clic (ou Entrée) sur une référence « Source » cliquable dans une réponse : ouvre la table dans le référentiel. */
  onAnswerActivate(event: Event): void {
    // Le nom de la table est lu dans le texte de l'élément : le sanitiseur d'Angular supprime les
    // attributs data-* du HTML injecté via [innerHTML], seuls class/role/tabindex/title survivent.
    const target = (event.target as HTMLElement | null)?.closest<HTMLElement>('.msg-ref');
    const table = target?.textContent?.trim();
    if (!target || !table) {
      return;
    }
    if (event instanceof KeyboardEvent && event.key !== 'Enter' && event.key !== ' ') {
      return;
    }
    event.preventDefault();
    this.router.navigate(['/tables'], { queryParams: { table } });
  }

  /** Rendu HTML sûr d'une réponse (voir shared/answer-format.ts, partagé avec l'historique). */
  formatAiContent(content: string): string {
    return formatAnswerCached(content);
  }

  // --- Actions sur un message ------------------------------------------------------------------

  copyMessage(message: DisplayMessage): void {
    navigator.clipboard.writeText(message.content).then(() => {
      this.copiedMessageId.set(message.id);
      setTimeout(() => {
        if (this.copiedMessageId() === message.id) {
          this.copiedMessageId.set(null);
        }
      }, COPY_FEEDBACK_MS);
    });
  }

  /** "Modifier" un message déjà posé : le retire (lui et sa réponse) puis pré-remplit le champ de
   * saisie avec son contenu — il suffit à l'utilisateur de l'ajuster et de renvoyer. */
  editMessage(message: DisplayMessage): void {
    if (!message.persisted || this.loading()) {
      return;
    }
    this.removeMessageAndAnswer(message);
    this.draft = message.content;
    this.resizeComposer();
    setTimeout(() => this.focusComposer());
  }

  deleteMessage(message: DisplayMessage): void {
    if (!message.persisted) {
      return;
    }
    if (!window.confirm('Supprimer ce message et sa réponse ?')) {
      return;
    }
    this.removeMessageAndAnswer(message);
  }

  /** Retire, en base et localement, le message USER `message` et sa réponse IA associée (celle qui
   * le suit immédiatement, si elle existe et est elle aussi persistée). */
  private removeMessageAndAnswer(message: DisplayMessage): void {
    const current = this.messages();
    const index = current.findIndex(m => m.id === message.id);
    if (index < 0) {
      return;
    }
    const next = current[index + 1];
    const toDelete = next && next.sender === 'AI' && next.persisted ? [message, next] : [message];

    toDelete.forEach(m => this.chatHistoryService.deleteMessage(m.id).subscribe({ error: () => undefined }));
    this.messages.update(msgs => msgs.filter(m => !toDelete.some(d => d.id === m.id)));
    if (this.messages().length === 0) {
      this.messages.set([WELCOME_MESSAGE]);
    }
  }

  private pushLocalMessage(sender: 'USER' | 'AI', content: string, error = false): void {
    this.messages.update(msgs => [...msgs, { id: this.nextLocalId--, sender, content, persisted: false, error }]);
  }

  private loadHistory(chatId: number): void {
    this.chatHistoryService.getHistory(chatId, 0, 200).subscribe(page => {
      if (page.content.length === 0) {
        return;
      }
      this.messages.set(
        page.content.map(h => ({
          id: h.id,
          sender: (h.messageType === 'USER' ? 'USER' : 'AI') as 'USER' | 'AI',
          content: h.content,
          persisted: true,
        })),
      );
    });
  }
}
