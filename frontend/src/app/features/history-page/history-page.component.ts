import { Component, ElementRef, Input, OnChanges, OnInit, SimpleChanges, ViewChild, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpContext } from '@angular/common/http';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { SILENT_ERRORS } from '../../core/interceptors/http-context';
import { ChatContextService } from '../../core/services/chat-context.service';
import { ChatHistoryService } from '../../core/services/chat-history.service';
import { ConversationsService } from '../../core/services/conversations.service';
import { LayoutService } from '../../core/services/layout.service';
import { Chat, ChatHistory } from '../../core/models/chat-session.model';
import { formatAnswerCached } from '../../shared/answer-format';

const HISTORY_PAGE_SIZE = 200;
/** Au-delà, une réponse est repliée par défaut (rien n'est retiré) — voir ChatbotPageComponent. */
const LONG_ANSWER_THRESHOLD = 3500;

/**
 * Historique : la conversation choisie en LECTURE SEULE (route /chat-history/:id) ; la liste des conversations
 * est dans la colonne de navigation, sous « Utilisateurs » (RailNavComponent, demande du 2026-10-06). Pour poursuivre l'échange, « Continuer dans l'assistant »
 * la rouvre dans l'espace Assistant avec son contexte.
 */
@Component({
  selector: 'app-history-page',
  standalone: true,
  imports: [DatePipe, MatIconModule],
  templateUrl: './history-page.component.html',
  styleUrl: './history-page.component.scss',
})
export class HistoryPageComponent implements OnInit, OnChanges {
  /** Id de la conversation (route /chat-history/:id, lié par withComponentInputBinding). */
  @Input() id?: string;

  @ViewChild('top') private top?: ElementRef<HTMLElement>;

  readonly chat = signal<Chat | null>(null);
  readonly messages = signal<ChatHistory[] | null>(null);
  readonly failed = signal<'notfound' | 'error' | null>(null);
  readonly expanded = signal<ReadonlySet<number>>(new Set());

  constructor(
    private readonly chatHistoryService: ChatHistoryService,
    private readonly chatContext: ChatContextService,
    private readonly conversations: ConversationsService,
    private readonly router: Router,
    readonly layout: LayoutService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  ngOnChanges(changes: SimpleChanges): void {
    // withComponentInputBinding() réutilise l'instance en changeant seulement `id` quand on passe d'une
    // conversation à l'autre : ngOnInit ne serait pas rejoué.
    if (changes['id'] && !changes['id'].firstChange) {
      this.load();
    }
  }

  format(content: string): string {
    return formatAnswerCached(content);
  }

  isLong(message: ChatHistory): boolean {
    return message.messageType !== 'USER' && message.content.length > LONG_ANSWER_THRESHOLD;
  }

  isExpanded(message: ChatHistory): boolean {
    return this.expanded().has(message.id);
  }

  toggle(message: ChatHistory): void {
    this.expanded.update(current => {
      const next = new Set(current);
      if (!next.delete(message.id)) {
        next.add(message.id);
      }
      return next;
    });
  }

  continueInAssistant(chat: Chat): void {
    this.chatContext.openChat(chat);
    this.router.navigate(['/assistant']);
  }

  reload(): void {
    this.load();
  }

  onAnswerActivate(event: Event): void {
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

  private load(): void {
    this.chat.set(null);
    this.messages.set(null);
    this.failed.set(null);
    this.expanded.set(new Set());
    if (!this.id) {
      return;
    }

    const chatId = Number(this.id);
    if (!Number.isInteger(chatId)) {
      this.failed.set('notfound');
      return;
    }

    this.chatHistoryService.getChatById(chatId, new HttpContext().set(SILENT_ERRORS, true)).subscribe({
      next: chat => {
        this.chat.set(chat);
        this.chatHistoryService.getHistory(chatId, 0, HISTORY_PAGE_SIZE).subscribe({
          next: page => {
            this.messages.set(page.content);
            setTimeout(() => this.top?.nativeElement.scrollIntoView({ block: 'start' }));
          },
          error: () => this.failed.set('error'),
        });
      },
      error: error => this.failed.set(error?.status === 404 || error?.status === 403 ? 'notfound' : 'error'),
    });
  }
}
